package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.DiaryBlocks
import com.cleo.cleos.data.db.DiaryDao
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.data.db.TodoDao
import com.cleo.cleos.data.db.TodoEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** What the model may do. Each group is switched on or off in settings. */
enum class ToolGroup { Todos, Diary, Weather }

/** A function offered to the model. [parameters] is a JSON Schema object. */
class ToolSpec(
    val name: String,
    val group: ToolGroup,
    /** What it does, as a verb phrase for the chat: 记待办. */
    val action: String,
    val description: String,
    val parameters: JsonObject,
) {
    val activity: String get() = "在$action"
}

/** A call as the model wrote it. [arguments] is raw JSON text and may not parse. */
@Serializable
data class ToolCall(val id: String, val name: String, val arguments: String)

/** [result] goes back to the model; [note] is the one line the chat shows. */
data class ToolOutcome(val result: String, val note: String)

/**
 * A tool that could not do its job. [result] tells the model what to do instead;
 * [note] is the reason, for the chat line "记待办没成：…".
 */
class ToolFailure(val result: String, val note: String) : Exception(result)

/**
 * The tools and what they tell the model about themselves. Descriptions say when to use
 * a tool and where its inputs come from (an id "from list_todos"); the rules about
 * whether to use tools at all live in the system prompt.
 */
object ToolSpecs {
    val addTodo = ToolSpec(
        name = "add_todo",
        group = ToolGroup.Todos,
        action = "记待办",
        description = "在对方的待办清单里加一条。",
        parameters = schema(
            required = listOf("title"),
            "title" to prop("string", "要做的事，一句话"),
            "due" to prop("string", "哪天要做完，写成 YYYY-MM-DD；没说日期就不填"),
            "note" to prop("string", "补充说明，没有就不填"),
        ),
    )
    val listTodos = ToolSpec(
        name = "list_todos",
        group = ToolGroup.Todos,
        action = "看待办",
        description = "看对方的待办清单：没做完的全部列出，每条带编号。改一条待办之前，先用它找到编号。",
        parameters = schema("include_done" to prop("boolean", "要不要顺带列出最近做完的")),
    )
    val updateTodo = ToolSpec(
        name = "update_todo",
        group = ToolGroup.Todos,
        action = "改待办",
        description = "改一条待办：打勾、改回没做完、改内容、改日期。只填要改的项。",
        parameters = schema(
            required = listOf("id"),
            "id" to prop("integer", "待办编号，来自 list_todos 或 add_todo 的结果"),
            "done" to prop("boolean", "true 是做完了，false 是改回没做完"),
            "title" to prop("string", "新的内容"),
            "due" to prop("string", "新的日期 YYYY-MM-DD；要去掉日期就填 none"),
            "note" to prop("string", "新的补充说明；要去掉就填 none"),
        ),
    )
    val readDiary = ToolSpec(
        name = "read_diary",
        group = ToolGroup.Diary,
        action = "翻日记",
        description = "读对方写的日记。给 date 读那一天的，给 query 按关键词找，都不给就读最近几篇。",
        parameters = schema(
            "date" to prop("string", "哪一天，YYYY-MM-DD"),
            "query" to prop("string", "关键词"),
            "limit" to prop("integer", "最多几篇，默认 3，最多 10"),
        ),
    )
    val getWeather = ToolSpec(
        name = "get_weather",
        group = ToolGroup.Weather,
        action = "查天气",
        description = "查天气：现在的天气和接下来几天的预报。",
        parameters = schema(
            "city" to prop(
                "string",
                "城市。中国的用中文名，不带“市”字，如“杭州”；国外的用英文名，如“Tokyo”。对方没说在哪就不填，会查对方在设置里填的城市",
            ),
            "days" to prop("integer", "预报几天，默认 3，最多 7"),
        ),
    )

    val all = listOf(addTodo, listTodos, updateTodo, readDiary, getWeather)
    val byName = all.associateBy { it.name }

    private fun prop(type: String, description: String) = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun schema(vararg props: Pair<String, JsonObject>) = schema(emptyList(), *props)

    private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (name, p) -> put(name, p) } }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    }
}

interface WeatherSource {
    /** Throws [ToolFailure] when the place is unknown or the service can't be reached. */
    suspend fun report(city: String, days: Int): WeatherReport
}

/** [place] as the chat names it ("杭州"); [text] is what the model reads. */
data class WeatherReport(val place: String, val text: String)

/**
 * Runs what the model asked for. Whether a group is allowed is checked here again, at the
 * moment of the call, not only when the tools are offered: the history may hold calls
 * from before a switch was turned off, and a model can call a tool it was not offered.
 */
class ToolBox(
    private val todos: TodoDao,
    private val diary: DiaryDao,
    private val weather: WeatherSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    fun specs(groups: Set<ToolGroup>): List<ToolSpec> = ToolSpecs.all.filter { it.group in groups }

    fun activity(name: String): String = ToolSpecs.byName[name]?.activity ?: "在用工具"

    fun action(name: String): String = ToolSpecs.byName[name]?.action ?: "用工具"

    suspend fun run(call: ToolCall, settings: AppSettings): ToolOutcome {
        val spec = ToolSpecs.byName[call.name]
            ?: return ToolOutcome("没有叫 ${call.name} 的工具。", "想用的工具不存在：${call.name}")
        if (spec.group !in settings.tools) {
            return ToolOutcome("对方在设置里关掉了这项功能，现在用不了。", "${spec.action}没成：设置里关着")
        }
        val args = ToolArgs.parse(call.arguments)
            ?: return ToolOutcome("参数不是合法的 JSON 对象，按参数说明重新调用。", "${spec.action}没成：参数写错了")
        val today = LocalDate.now(zone())
        return try {
            when (spec.name) {
                ToolSpecs.addTodo.name -> addTodo(args, today)
                ToolSpecs.listTodos.name -> listTodos(args, today)
                ToolSpecs.updateTodo.name -> updateTodo(args, today)
                ToolSpecs.readDiary.name -> readDiary(args, today)
                else -> getWeather(args, settings)
            }
        } catch (f: ToolFailure) {
            ToolOutcome(f.result, "${spec.action}没成：${f.note}")
        }
    }

    private suspend fun addTodo(a: JsonObject, today: LocalDate): ToolOutcome {
        val title = ToolArgs.text(a, "title").orEmpty().trim().take(TITLE_MAX)
        if (title.isEmpty()) throw ToolFailure("缺少 title。", "没有内容")
        val due = ToolArgs.optionalDay(a, "due", today)
        val note = ToolArgs.text(a, "note").orEmpty().trim().take(NOTE_MAX)
        val todo = TodoEntity(title = title, note = note, dueDay = due?.toEpochDay(), createdAt = clock())
        val id = todos.insert(todo)
        return ToolOutcome(
            "已添加：" + describe(todo.copy(id = id), today),
            "记下了待办「$title」" + (due?.let { " · " + Describe.monthDay(it) } ?: ""),
        )
    }

    private suspend fun listTodos(a: JsonObject, today: LocalDate): ToolOutcome {
        val all = todos.all()
        // The same order as the todo screen: dated first by date, then in the order added.
        val pending = all.filter { !it.done }
            .sortedWith(compareBy<TodoEntity> { it.dueDay ?: Long.MAX_VALUE }.thenBy { it.createdAt })
        val withDone = ToolArgs.bool(a, "include_done") == true
        val done = all.filter { it.done }.sortedByDescending { it.doneAt ?: 0L }.take(DONE_LIST_MAX)
        val text = buildString {
            if (pending.isEmpty()) {
                append(if (all.isEmpty()) "待办清单是空的。" else "没有没做完的待办。")
            } else {
                append("没做完的 ${pending.size} 条：")
                pending.take(LIST_MAX).forEach { append('\n').append(describe(it, today)) }
                if (pending.size > LIST_MAX) append("\n……还有 ${pending.size - LIST_MAX} 条没列出")
            }
            if (withDone && done.isNotEmpty()) {
                append("\n最近做完的 ${done.size} 条：")
                done.forEach { append('\n').append(describe(it, today)) }
            }
        }
        val note = when {
            all.isEmpty() -> "看了一眼待办：清单是空的"
            pending.isEmpty() -> "看了一眼待办：都做完了"
            else -> "看了一眼待办（${pending.size} 条没做完）"
        }
        return ToolOutcome(text, note)
    }

    private suspend fun updateTodo(a: JsonObject, today: LocalDate): ToolOutcome {
        val id = ToolArgs.id(a["id"]) ?: throw ToolFailure("缺少 id。先用 list_todos 找到编号。", "不知道是哪一条")
        val old = todos.get(id) ?: throw ToolFailure("没有 #$id 这条待办。先用 list_todos 看看现在有哪些。", "没找到这一条")
        var t = old
        // Empty strings change nothing. Some models fill every optional field with ""
        // and would otherwise wipe dates and notes the call never meant to touch.
        ToolArgs.text(a, "title")?.trim()?.takeIf { it.isNotEmpty() }?.let { t = t.copy(title = it.take(TITLE_MAX)) }
        ToolArgs.text(a, "note")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            t = t.copy(note = if (ToolArgs.isNone(it)) "" else it.take(NOTE_MAX))
        }
        ToolArgs.text(a, "due")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            t = t.copy(dueDay = if (ToolArgs.isNone(it)) null else ToolArgs.day(it, today).toEpochDay())
        }
        ToolArgs.bool(a, "done")?.let { done ->
            if (done != t.done) t = t.copy(done = done, doneAt = if (done) clock() else null)
        }
        if (t == old) return ToolOutcome("没有变化：" + describe(old, today), "看了看待办「${old.title}」")
        todos.upsert(t)
        val note = when {
            t.done && !old.done -> "把「${t.title}」打了勾"
            !t.done && old.done -> "把「${t.title}」改回没做完"
            else -> "改了待办「${t.title}」"
        }
        return ToolOutcome("已更新：" + describe(t, today), note)
    }

    private suspend fun readDiary(a: JsonObject, today: LocalDate): ToolOutcome {
        val date = ToolArgs.optionalDay(a, "date", today)
        val query = ToolArgs.text(a, "query").orEmpty().trim()
        val limit = (ToolArgs.int(a["limit"]) ?: 3).coerceIn(1, 10)
        val entries = when {
            date != null -> diary.onDay(date.toEpochDay()).take(limit)
            query.isNotEmpty() -> diary.search(ToolArgs.likePattern(query), SEARCH_CANDIDATES)
                .filter { Describe.diaryContains(it, query) }
                .take(limit)
            else -> diary.recent(limit)
        }
        if (entries.isEmpty()) {
            return when {
                date != null -> ToolOutcome("${Describe.date(date, today)}没有写日记。", "找了${Describe.monthDay(date)}的日记：那天没写")
                query.isNotEmpty() -> ToolOutcome("日记里没有找到「$query」。", "在日记里找了「$query」：没找到")
                else -> ToolOutcome("对方还没有写过日记。", "翻了翻日记：还没有")
            }
        }
        val note = when {
            date != null -> "读了${Describe.monthDay(date)}的日记"
            query.isNotEmpty() -> "在日记里找了「$query」（${entries.size} 篇）"
            else -> "翻了最近的 ${entries.size} 篇日记"
        }
        return ToolOutcome(Describe.diary(entries, today), note)
    }

    private suspend fun getWeather(a: JsonObject, settings: AppSettings): ToolOutcome {
        val city = ToolArgs.text(a, "city")?.trim().orEmpty().ifEmpty { settings.weatherCity.trim() }
        if (city.isEmpty()) {
            throw ToolFailure("不知道对方在哪个城市。先问一下，再带上 city 查。", "不知道在哪个城市")
        }
        val days = (ToolArgs.int(a["days"]) ?: 3).coerceIn(1, 7)
        val report = weather.report(city, days)
        return ToolOutcome(report.text, "查了${report.place}的天气")
    }

    private fun describe(t: TodoEntity, today: LocalDate) = Describe.todo(t, today, zone())

    private companion object {
        const val TITLE_MAX = 200
        const val NOTE_MAX = 1000
        const val LIST_MAX = 50
        const val DONE_LIST_MAX = 10
        const val SEARCH_CANDIDATES = 60
    }
}

/** Reading what the model wrote. Lenient where models are commonly sloppy. */
internal object ToolArgs {
    private val json = Json { ignoreUnknownKeys = true }

    /** Blank means no arguments: some models send "" for a call without parameters. */
    fun parse(raw: String): JsonObject? {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    }

    fun text(a: JsonObject, key: String): String? = (a[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    fun bool(a: JsonObject, key: String): Boolean? =
        (a[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }

    fun int(e: JsonElement?): Int? =
        (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.content.trim().toIntOrNull() }

    /** 12, "12" and "#12" all mean #12: the ids are shown to the model with a #. */
    fun id(e: JsonElement?): Long? =
        (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.content.trim().removePrefix("#").toLongOrNull() }

    fun isNone(s: String) = s.equals("none", ignoreCase = true) || s == "无"

    /** Absent, blank or "none" is no date. */
    fun optionalDay(a: JsonObject, key: String, today: LocalDate): LocalDate? {
        val s = text(a, key)?.trim().orEmpty()
        return if (s.isEmpty() || isNone(s)) null else day(s, today)
    }

    /**
     * YYYY-MM-DD, also with one-digit month or day, or / as the separator. M-D alone means
     * this year. Anything else is sent back with an example rather than guessed at.
     */
    fun day(s: String, today: LocalDate): LocalDate {
        val nums = s.trim().split('-', '/', '.').map { it.trim().toIntOrNull() }
        val date = runCatching {
            when {
                nums.size == 3 && nums.all { it != null } -> LocalDate.of(nums[0]!!, nums[1]!!, nums[2]!!)
                nums.size == 2 && nums.all { it != null } -> LocalDate.of(today.year, nums[0]!!, nums[1]!!)
                else -> null
            }
        }.getOrNull()
        return date ?: throw ToolFailure("日期「$s」看不懂，写成 YYYY-MM-DD，比如 ${today.plusDays(1)}。", "日期没写对")
    }

    /** A LIKE pattern for [q] anywhere, `!` escaping the wildcards (the query's ESCAPE char). */
    fun likePattern(q: String): String =
        "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
}

/** How todos, dates and diary entries are written out for the model. */
internal object Describe {
    private val monthDay = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private val weekday = DateTimeFormatter.ofPattern("EEEE", Locale.CHINA)

    /** Per entry and in total, so a long diary can't crowd the conversation out. */
    private const val ENTRY_MAX = 1500
    private const val TOTAL_MAX = 6000

    fun monthDay(d: LocalDate): String = d.format(monthDay)

    fun weekday(d: LocalDate): String = d.format(weekday).replace("星期", "周")

    /** 2026-09-24（周四，明天）: the ISO date to copy into calls, and a check on the arithmetic. */
    fun date(d: LocalDate, today: LocalDate): String {
        val relative = when (ChronoUnit.DAYS.between(today, d)) {
            0L -> "今天"
            1L -> "明天"
            2L -> "后天"
            -1L -> "昨天"
            -2L -> "前天"
            else -> null
        }
        return "$d（${weekday(d)}${relative?.let { "，$it" }.orEmpty()}）"
    }

    fun todo(t: TodoEntity, today: LocalDate, zone: ZoneId): String = buildString {
        append('#').append(t.id).append(' ').append(t.title)
        t.dueDay?.let { day ->
            val due = LocalDate.ofEpochDay(day)
            append(" · 截止 ").append(date(due, today))
            if (!t.done && due.isBefore(today)) append(" · 已过期")
        }
        if (t.note.isNotBlank()) append(" · 备注：").append(t.note)
        if (t.done) {
            append(" · 已做完")
            t.doneAt?.let { append("（").append(monthDay(Instant.ofEpochMilli(it).atZone(zone).toLocalDate())).append("）") }
        }
    }

    fun diaryContains(e: DiaryEntryEntity, q: String): Boolean =
        e.title.contains(q, ignoreCase = true) ||
            DiaryBlocks.plainText(DiaryBlocks.decode(e.blocks)).contains(q, ignoreCase = true)

    fun diary(entries: List<DiaryEntryEntity>, today: LocalDate): String {
        var used = 0
        return entries.joinToString("\n\n") { e ->
            val blocks = DiaryBlocks.decode(e.blocks)
            val body = DiaryBlocks.plainText(blocks)
            val images = DiaryBlocks.images(blocks).size
            val room = (TOTAL_MAX - used).coerceIn(0, ENTRY_MAX)
            val shown = when {
                body.length <= room -> body
                room == 0 -> "（正文太长没放下，要读就按日期单独读这一天）"
                else -> body.take(room) + "……（后面还有 ${body.length - room} 字）"
            }
            used += minOf(body.length, room)
            buildString {
                append('【').append(date(LocalDate.ofEpochDay(e.day), today)).append('】')
                append(e.title.ifBlank { "（没有标题）" })
                if (shown.isNotEmpty()) append('\n').append(shown)
                if (images > 0) append("\n（配了 $images 张图）")
            }
        }
    }
}

internal object ToolCallCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(calls: List<ToolCall>): String = json.encodeToString(calls)

    fun decode(raw: String?): List<ToolCall> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString<List<ToolCall>>(raw) }.getOrDefault(emptyList())
}
