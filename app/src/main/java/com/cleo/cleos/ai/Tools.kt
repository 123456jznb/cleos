package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.Companions
import com.cleo.cleos.data.DiaryBlock
import com.cleo.cleos.data.DiaryBlocks
import com.cleo.cleos.data.db.DiaryDao
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.data.db.MemoryDao
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

/**
 * What the model may do. Each group is switched on or off in settings. [Diary] is reading
 * the person's diary; [AiDiary] is the model's own entries, writing and reading back.
 * [Later] is not among the switches in settings: a TA gets it while its own "reach out"
 * switch is on (CompanionEntity.proactive), and it is never stored with the others.
 */
enum class ToolGroup { Todos, Diary, AiDiary, Secrets, Avatar, Weather, Messages, Letters, Memory, Location, Speak, Later }

/**
 * A function offered to the model, when any of its [groups] is on. [parameters] is a
 * JSON Schema object.
 */
data class ToolSpec(
    val name: String,
    val groups: Set<ToolGroup>,
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

/**
 * [result] goes back to the model; [note] is the one line the chat shows (none when
 * blank). A [request] is put to the person as a card in the chat.
 */
data class ToolOutcome(val result: String, val note: String, val request: SecretRequest? = null)

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
        groups = setOf(ToolGroup.Todos),
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
        groups = setOf(ToolGroup.Todos),
        action = "看待办",
        description = "看对方的待办清单：没做完的全部列出，每条带编号。改一条待办之前，先用它找到编号。",
        parameters = schema("include_done" to prop("boolean", "要不要顺带列出最近做完的")),
    )
    val updateTodo = ToolSpec(
        name = "update_todo",
        groups = setOf(ToolGroup.Todos),
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
    /** Its description depends on whose entries it can reach; see [offered]. */
    val readDiary = ToolSpec(
        name = "read_diary",
        groups = setOf(ToolGroup.Diary, ToolGroup.AiDiary),
        action = "翻日记",
        description = readDiaryDescription(setOf(ToolGroup.Diary, ToolGroup.AiDiary)),
        parameters = schema(
            "date" to prop("string", "哪一天，YYYY-MM-DD"),
            "query" to prop("string", "关键词"),
            "limit" to prop("integer", "最多几篇，默认 3，最多 10"),
        ),
    )
    val writeDiary = ToolSpec(
        name = "write_diary",
        groups = setOf(ToolGroup.AiDiary),
        action = "写日记",
        description = "写一篇你自己的日记，记在今天。和对方的日记在同一个本子里，标着是你写的；对方能看，但改不了。",
        parameters = schema(
            required = listOf("text"),
            "title" to prop("string", "标题，可以不写"),
            "text" to prop("string", "正文：你自己的所见所想，用第一人称"),
        ),
    )
    val listSecrets = ToolSpec(
        name = "list_secrets",
        groups = setOf(ToolGroup.Secrets),
        action = "数小秘密",
        description = "看对方有哪些小秘密：只有编号和日期，标题和内容都看不到。也会说哪些你问过、对方怎么答的。",
        parameters = schema(),
    )
    val requestSecret = ToolSpec(
        name = "request_secret",
        groups = setOf(ToolGroup.Secrets),
        action = "请求看小秘密",
        description = "请对方给你看一个小秘密。对方会在聊天里决定；同意了，内容会跟着对方的下一条消息给你。",
        parameters = schema(
            required = listOf("id"),
            "id" to prop("integer", "小秘密的编号，来自 list_secrets"),
            "reason" to prop("string", "想看的理由，一句话，对方会看到"),
        ),
    )
    /**
     * Speaking in several messages, the way people do in a chat: one call, one bubble.
     * Optional on purpose. Plain text is shown too, as one bubble, so a model that doesn't
     * call it still gets heard: a chat that only shows what goes through a tool falls
     * silent whenever the model answers in plain text.
     */
    val sendMessage = ToolSpec(
        name = "send_message",
        groups = setOf(ToolGroup.Messages),
        action = "发消息",
        description = "发一条消息给对方。想分成几条说的时候用：一条只说一件事，要发几条就在这一次回复里调用几次，按顺序。只说一句的话直接回复就行。",
        parameters = schema(
            required = listOf("text"),
            "text" to prop("string", "这一条消息的内容"),
            "quote" to prop(
                "string",
                "可不填。这条在回对方的哪句话：照抄那句话里的几个字。对方连着发了几条、你一条条回的时候，或者回到前面某句话时才用；一问一答不要填",
            ),
        ),
    )

    val sendVoice = ToolSpec(
        name = "send_voice",
        groups = setOf(ToolGroup.Speak),
        action = "发语音",
        description = "用声音说：发一条语音条给对方，对方听到的是你的声音，也看得到文字。想用声音说的时候用，比如道晚安、撒娇、情绪浓的时候；平常还是打字。一条一两句话。",
        parameters = schema(
            required = listOf("text"),
            "text" to prop("string", "要说的话，写成说出口的样子，不要括号里的动作和神情"),
        ),
    )

    /** The calls that are the TA speaking: each becomes a bubble of its own. */
    val speaking = setOf(sendMessage.name, sendVoice.name)

    /** What a sent message answers the model with. */
    const val SENT = "已发出。"

    /**
     * One tool with four actions rather than four tools: they belong together, and the
     * model knows the four words (remember, open, update, forget) either way.
     */
    val memory = ToolSpec(
        name = "memory",
        groups = setOf(ToolGroup.Memory),
        action = "记东西",
        description = "长期记忆：关于对方是谁的事，以及关于你自己的事。一条记忆是一个话题：名字、一行摘要、若干条细节。" +
            "摘要一直在你上下文里，细节要 open 才看得到。\n" +
            "action 四选一：\n" +
            "· open：取出一条的细节。要说到具体内容就先 open，别照着摘要猜。\n" +
            "· remember：开一条新的。先看已有的，能归进某个已有话题就用 update 加细节，别另开一条。\n" +
            "· update：改一条：加细节、改摘要，或整体重写细节。事情变了、当初记错了就改它，别留两条互相矛盾的；「最近」那类过期了尤其要改。\n" +
            "· forget：整条删掉。只是变了就用 update；对方明确说别记了才删。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") { listOf("open", "remember", "update", "forget").forEach { add(it) } }
                    put("description", "要做哪件事")
                }
                putJsonObject("id") {
                    put("type", "integer")
                    put("description", "open / update / forget 要：那条前面方括号里的编号，比如 12")
                }
                putJsonObject("category") {
                    put("type", "string")
                    putJsonArray("enum") { MemoryKinds.all.forEach { add(it.key) } }
                    put("description", "remember 要；update 只在换分类时给。\n" + MemoryKinds.all.joinToString("\n") { "${it.key}：${it.help}" })
                }
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "话题名，短，像个标题：「怎么称呼」「读书口味」。remember 要；update 不改名就别给。")
                }
                putJsonObject("summary") {
                    put("type", "string")
                    put(
                        "description",
                        "一行，说清这条讲什么，不是内容本身。它会一直在你上下文里，也是你以后判断要不要打开这条的唯一依据。remember 要；update 不改就别给。",
                    )
                }
                putJsonObject("details") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "remember 用：具体内容，一条一件事。写具体的，不写性格判断；尽量带上从哪儿知道的（「对方自己说的」）。")
                }
                putJsonObject("add_detail") {
                    put("type", "string")
                    put("description", "update 用：追加一条细节。最常用，不动已有的。")
                }
                putJsonObject("set_details") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "update 用：整体替换所有细节。要删掉或改写某条细节时才用；先 open 读出来，把要留的一起写回去。")
                }
            }
            putJsonArray("required") { add("action") }
        },
    )

    val readLetters = ToolSpec(
        name = "read_letters",
        groups = setOf(ToolGroup.Letters),
        action = "翻信",
        description = "看你和对方之间写过的信，最新的在前。对方提到信的时候用。",
        parameters = schema("limit" to prop("integer", "最多几封，默认 3，最多 10")),
    )

    val setMyAvatar = ToolSpec(
        name = "set_my_avatar",
        groups = setOf(ToolGroup.Avatar),
        action = "换头像",
        description = "换你自己的头像：用对方在这段聊天里发来的一张图（取正中间的方块），或者一个表情。两者给一个。",
        parameters = schema(
            "image" to prop("string", "图的编号，比如 #45-1；写 latest 就是对方最近发来的那张"),
            "emoji" to prop("string", "一个表情，比如 🌙"),
        ),
    )
    val getWeather = ToolSpec(
        name = "get_weather",
        groups = setOf(ToolGroup.Weather),
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

    val getLocation = ToolSpec(
        name = "get_location",
        groups = setOf(ToolGroup.Location),
        action = "查位置",
        description = "查对方手机现在在哪：省、市、区（查得到的话再细些），和坐标。对方问附近有什么、怎么走、天气，或者你需要知道对方在哪时用。",
        parameters = schema(),
    )

    /**
     * Noting something to come back to (ai/Later.kt). It leaves no line in the chat, not even when
     * it fails: what the TA means to bring up later is for later.
     */
    val noteForLater = ToolSpec(
        name = "note_for_later",
        groups = setOf(ToolGroup.Later),
        action = "记一笔",
        description = "给以后的自己记一笔：一件过一阵想跟对方说或问的事，和大概多久以后再想起来。到时候你会看到这一笔和这之间聊的，再决定说不说。",
        parameters = schema(
            required = listOf("what", "minutes"),
            "what" to prop("string", "想说或想问的事，写具体，到时候的你要看得懂：比如「问问糖醋排骨做成没有」「对方今晚考完试，问问考得怎样」"),
            "minutes" to prop("integer", "多少分钟以后再想起来。对方说去做饭了，大概 40；明早的事，就算到明早。最少 1，最多 10080（七天）"),
            "why" to prop("string", "可不填。当时的情形，给到时候的自己看"),
        ),
    )

    /** Tools that leave no trace in the chat, neither a line nor "在…" while they run. */
    val quiet = setOf(noteForLater.name)

    val all = listOf(
        sendMessage,
        sendVoice,
        addTodo,
        listTodos,
        updateTodo,
        readDiary,
        writeDiary,
        listSecrets,
        requestSecret,
        readLetters,
        memory,
        setMyAvatar,
        getWeather,
        getLocation,
        noteForLater,
    )
    val byName = all.associateBy { it.name }

    /** What to offer for the groups that are on, each worded for what it can reach. */
    fun offered(groups: Set<ToolGroup>): List<ToolSpec> = all
        .filter { spec -> spec.groups.any { it in groups } }
        .map { if (it.name == readDiary.name) it.copy(description = readDiaryDescription(groups)) else it }

    private fun readDiaryDescription(groups: Set<ToolGroup>): String {
        val whose = when {
            ToolGroup.Diary in groups && ToolGroup.AiDiary in groups -> "读日记本：对方写的和你自己写的都在里面，小秘密除外。"
            ToolGroup.Diary in groups -> "读对方写的日记，小秘密除外。"
            else -> "读你自己以前写的日记。"
        }
        return whose + "给 date 读那一天的，给 query 按关键词找，都不给就读最近几篇。"
    }

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

/** Where the model's own avatar is kept; the app side of set_my_avatar. */
interface SelfAvatar {
    /** The file of the picture [ref] names in this conversation ("latest", "#45-1"), if there is one. */
    suspend fun picture(conversationId: Long, ref: String): String?

    /** Crops [file] and makes it TA [companionId]'s avatar; false when the picture can't be read. */
    suspend fun usePicture(companionId: Long, file: String): Boolean

    suspend fun useEmoji(companionId: Long, emoji: String)
}

/**
 * Runs what the model asked for. Whether a group is allowed is checked here again, at the
 * moment of the call, not only when the tools are offered: the history may hold calls
 * from before a switch was turned off, and a model can call a tool it was not offered.
 */
class ToolBox(
    private val todos: TodoDao,
    private val diary: DiaryDao,
    private val weather: WeatherSource,
    /** One TA's requests to see a secret so far, oldest first, from any of their conversations. */
    private val requests: suspend (companionId: Long) -> List<SecretRequest> = { emptyList() },
    private val avatar: SelfAvatar? = null,
    /** The letters between one TA and the person, any order. */
    private val letters: suspend (companionId: Long) -> List<LetterEntity> = { emptyList() },
    memories: MemoryDao? = null,
    /** Where the phone is, for get_location. */
    private val location: LocationSource? = null,
    /** Where note_for_later keeps its notes; asked for at the call, since it is made after this. */
    private val later: () -> LaterBook? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val book = memories?.let { MemoryBook(it, clock) }

    fun specs(groups: Set<ToolGroup>): List<ToolSpec> = ToolSpecs.offered(groups)

    /** "在…" while [name] runs; null for a quiet one, which shows only the dots. */
    fun activity(name: String): String? = if (name in ToolSpecs.quiet) null else ToolSpecs.byName[name]?.activity ?: "在用工具"

    fun action(name: String): String = ToolSpecs.byName[name]?.action ?: "用工具"

    /** [companionId]: the TA making the call; what it can read and change is theirs. */
    suspend fun run(
        call: ToolCall,
        settings: AppSettings,
        conversationId: Long = 0,
        companionId: Long = Companions.FIRST,
    ): ToolOutcome {
        val spec = ToolSpecs.byName[call.name]
            ?: return ToolOutcome("没有叫 ${call.name} 的工具。", "想用的工具不存在：${call.name}")
        // A quiet tool's failures leave no line either: "记一笔没成" would say there was something to note.
        fun failed(result: String, why: String) = ToolOutcome(result, if (spec.name in ToolSpecs.quiet) "" else "${spec.action}没成：$why")
        if (spec.groups.none { it in settings.tools }) return failed("对方在设置里关掉了这项功能，现在用不了。", "设置里关着")
        val args = ToolArgs.parse(call.arguments) ?: return failed("参数不是合法的 JSON 对象，按参数说明重新调用。", "参数写错了")
        // From the same clock as every "now" in here, not the wall clock beside it.
        val today = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate()
        return try {
            when (spec.name) {
                ToolSpecs.addTodo.name -> addTodo(args, today)
                ToolSpecs.listTodos.name -> listTodos(args, today)
                ToolSpecs.updateTodo.name -> updateTodo(args, today)
                ToolSpecs.readDiary.name -> readDiary(args, today, settings.tools, companionId)
                ToolSpecs.writeDiary.name -> writeDiary(args, today, companionId)
                ToolSpecs.listSecrets.name -> listSecrets(today, companionId)
                ToolSpecs.requestSecret.name -> requestSecret(args, companionId)
                ToolSpecs.setMyAvatar.name -> setMyAvatar(args, conversationId, companionId)
                // Sent messages become bubbles in ChatRepository; this is only reached by mistake.
                ToolSpecs.sendMessage.name, ToolSpecs.sendVoice.name -> ToolOutcome(ToolSpecs.SENT, "")
                ToolSpecs.readLetters.name -> readLetters(args, today, companionId)
                ToolSpecs.memory.name -> (book ?: throw ToolFailure("现在记不了。", "这里记不了")).act(args, companionId)
                ToolSpecs.getLocation.name -> getLocation()
                ToolSpecs.noteForLater.name -> noteForLater(args, conversationId, companionId)
                else -> getWeather(args, settings)
            }
        } catch (f: ToolFailure) {
            failed(f.result, f.note)
        }
    }

    private suspend fun noteForLater(a: JsonObject, conversationId: Long, companionId: Long): ToolOutcome {
        val notes = later() ?: throw ToolFailure("现在记不了。", "这里记不了")
        val what = ToolArgs.text(a, "what").orEmpty().trim().take(LATER_WHAT_MAX)
        if (what.isEmpty()) throw ToolFailure("缺少 what：写上想说或想问的事。", "没有内容")
        val minutes = ToolArgs.int(a["minutes"]) ?: throw ToolFailure("缺少 minutes：多少分钟以后，写个整数。", "没说多久")
        val why = ToolArgs.text(a, "why").orEmpty().trim().take(LATER_WHY_MAX)
        return ToolOutcome(notes.note(companionId, conversationId, what, why, minutes), "")
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

    private suspend fun readDiary(a: JsonObject, today: LocalDate, groups: Set<ToolGroup>, companionId: Long): ToolOutcome {
        // The person's entries only with their permission; this TA's own always, and no
        // other TA's. Secrets are kept out by the queries themselves, not by a filter here
        // that could be missed.
        val mine = ToolGroup.Diary in groups
        val own = if (ToolGroup.AiDiary in groups) companionId else -1L
        val date = ToolArgs.optionalDay(a, "date", today)
        val query = ToolArgs.text(a, "query").orEmpty().trim()
        val limit = (ToolArgs.int(a["limit"]) ?: 3).coerceIn(1, 10)
        val entries = when {
            date != null -> diary.onDay(date.toEpochDay(), mine, own).take(limit)
            query.isNotEmpty() -> diary.search(ToolArgs.likePattern(query), mine, own, SEARCH_CANDIDATES)
                .filter { Describe.diaryContains(it, query) }
                .take(limit)
            else -> diary.recent(mine, own, limit)
        }
        // A secret written that day is mentioned, never shown: the model can't ask about
        // what it doesn't know is there.
        val locked = if (date != null && ToolGroup.Secrets in groups) diary.secretsOnDay(date.toEpochDay()) else 0
        val lockedLine = if (locked > 0) "这天对方还写了 $locked 个小秘密，锁着，你看不到。" else ""
        if (entries.isEmpty()) {
            val nobody = when {
                mine && own < 0 -> "对方"
                !mine -> "你"
                else -> ""
            }
            return when {
                date != null -> ToolOutcome(
                    "${Describe.date(date, today)}${nobody}没有写日记。$lockedLine",
                    "找了${Describe.monthDay(date)}的日记：" + if (locked > 0) "只有小秘密" else "那天没写",
                )
                query.isNotEmpty() -> ToolOutcome("日记里没有找到「$query」。", "在日记里找了「$query」：没找到")
                else -> ToolOutcome("${nobody.ifEmpty { "日记本里" }}还没有写过日记。", "翻了翻日记：还没有")
            }
        }
        val note = when {
            date != null -> "读了${Describe.monthDay(date)}的日记"
            query.isNotEmpty() -> "在日记里找了「$query」（${entries.size} 篇）"
            else -> "翻了最近的 ${entries.size} 篇日记"
        }
        val text = Describe.diary(entries, today) + if (lockedLine.isEmpty()) "" else "\n\n$lockedLine"
        return ToolOutcome(text, note)
    }

    private suspend fun writeDiary(a: JsonObject, today: LocalDate, companionId: Long): ToolOutcome {
        val text = ToolArgs.text(a, "text").orEmpty().trim().take(DIARY_MAX)
        if (text.isEmpty()) throw ToolFailure("缺少 text。", "没有内容")
        val title = ToolArgs.text(a, "title").orEmpty().trim().take(TITLE_MAX)
        val now = clock()
        val id = diary.insert(
            DiaryEntryEntity(
                day = today.toEpochDay(),
                title = title,
                blocks = DiaryBlocks.encode(listOf(DiaryBlock.Text(text))),
                createdAt = now,
                updatedAt = now,
                author = DiaryEntryEntity.AUTHOR_AI,
                companionId = companionId,
            ),
        )
        return ToolOutcome(
            "写好了，记在 ${Describe.date(today, today)}：#$id ${title.ifEmpty { "（没有标题）" }}",
            if (title.isEmpty()) "写了一篇日记" else "写了一篇日记「$title」",
        )
    }

    private suspend fun listSecrets(today: LocalDate, companionId: Long): ToolOutcome {
        val secrets = diary.secrets()
        if (secrets.isEmpty()) return ToolOutcome("对方现在没有小秘密。", "数了数你的小秘密：还没有")
        // The latest request about each one: later ones replace earlier ones.
        val asked = requests(companionId).associateBy { it.diaryId }
        val text = buildString {
            append("对方有 ${secrets.size} 个小秘密，标题和内容你都看不到；想看就用 request_secret 问。")
            for (e in secrets) {
                append("\n#").append(e.id).append(' ').append(Describe.date(LocalDate.ofEpochDay(e.day), today))
                when (asked[e.id]?.status) {
                    SecretRequest.PENDING -> append(" · 你问过了，还在等对方决定")
                    SecretRequest.GRANTED -> append(" · 对方给你看过一次")
                    SecretRequest.DECLINED -> append(" · 你问过，对方没给看")
                }
            }
        }
        return ToolOutcome(text, "数了数你的小秘密：${secrets.size} 个")
    }

    private suspend fun requestSecret(a: JsonObject, companionId: Long): ToolOutcome {
        val id = ToolArgs.id(a["id"]) ?: throw ToolFailure("缺少 id。先用 list_secrets 看看有哪些。", "不知道是哪一个")
        val entry = diary.get(id)?.takeIf { it.secret }
            ?: throw ToolFailure("#$id 不是小秘密。先用 list_secrets 看看有哪些。", "没找到这个小秘密")
        // One card per secret at a time: asking again while the first card waits is nagging.
        if (requests(companionId).any { it.diaryId == id && it.status == SecretRequest.PENDING }) {
            return ToolOutcome("这个你已经问过了，对方还没决定，先别再问。", "")
        }
        val reason = ToolArgs.text(a, "reason").orEmpty().trim().take(REASON_MAX)
        return ToolOutcome(
            "请求已经发给对方了，对方会在聊天里决定给不给你看。同意了，内容会跟着对方的下一条消息给你。现在别追问，简单说一句就好。",
            "",
            SecretRequest(diaryId = id, day = entry.day, title = entry.title, reason = reason),
        )
    }

    private suspend fun setMyAvatar(a: JsonObject, conversationId: Long, companionId: Long): ToolOutcome {
        val port = avatar ?: throw ToolFailure("现在换不了头像。", "这里换不了")
        val image = ToolArgs.text(a, "image")?.trim().orEmpty()
        val emoji = ToolArgs.text(a, "emoji")?.trim().orEmpty()
        return when {
            image.isNotEmpty() -> {
                val file = port.picture(conversationId, image)
                    ?: throw ToolFailure("找不到「$image」这张图。用对方发来的图的编号（像 #45-1），或者写 latest。", "找不到那张图")
                if (!port.usePicture(companionId, file)) throw ToolFailure("这张图读不出来，换一张。", "那张图读不出来")
                ToolOutcome("换好了：现在的头像是对方发来的那张图。", "换了新头像")
            }
            emoji.isNotEmpty() -> {
                // An emoji is a few code points at most (a family, a flag); a sentence is not an avatar.
                if (emoji.codePointCount(0, emoji.length) > EMOJI_MAX) throw ToolFailure("emoji 只放一个表情。", "表情太长了")
                port.useEmoji(companionId, emoji)
                ToolOutcome("换好了：现在的头像是 $emoji。", "换了新头像：$emoji")
            }
            else -> throw ToolFailure("image 和 emoji 给一个。", "没说换成什么")
        }
    }

    /**
     * What has arrived: the person's sent letters and the TA's delivered ones. Drafts are
     * not letters yet, and a letter still on its way is not in the person's hands.
     */
    private suspend fun readLetters(a: JsonObject, today: LocalDate, companionId: Long): ToolOutcome {
        val now = clock()
        val limit = (ToolArgs.int(a["limit"]) ?: 3).coerceIn(1, 10)
        val shown = letters(companionId)
            .filter { !it.draft && (it.author == LetterEntity.AUTHOR_ME || (it.deliverAt ?: Long.MAX_VALUE) <= now) }
            .sortedByDescending { it.deliverAt ?: it.createdAt }
            .take(limit)
        if (shown.isEmpty()) return ToolOutcome("你们还没有写过信。", "翻了翻信：还没有")
        val text = shown.joinToString("\n\n") { l ->
            val mine = l.author == LetterEntity.AUTHOR_AI
            val day = Instant.ofEpochMilli(l.deliverAt ?: l.createdAt).atZone(zone()).toLocalDate()
            val unread = if (mine && l.readAt == null) "（对方还没拆开）" else ""
            val body = l.content.trim().let { if (it.length > LETTER_MAX) it.take(LETTER_MAX) + "……（后面还有 ${it.length - LETTER_MAX} 字）" else it }
            "【${Describe.date(day, today)} · ${if (mine) "你写的" else "对方写的"}】$unread\n$body"
        }
        return ToolOutcome(text, "翻了翻你们的信（${shown.size} 封）")
    }

    /** The chat line names the area, so the person sees each time the TA looked. */
    private suspend fun getLocation(): ToolOutcome {
        val place = (location ?: throw ToolFailure("这里查不了位置。", "这里查不了")).here()
        return ToolOutcome(Locations.describe(place), "查了你的位置" + (place.area?.let { "：$it" } ?: ""))
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
        const val DIARY_MAX = 5000
        const val REASON_MAX = 120
        const val EMOJI_MAX = 8
        const val LETTER_MAX = 2000
        const val LATER_WHAT_MAX = 200
        const val LATER_WHY_MAX = 200
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
                append('【').append(date(LocalDate.ofEpochDay(e.day), today))
                append(if (e.author == DiaryEntryEntity.AUTHOR_AI) " · 你写的" else " · 对方写的").append('】')
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
