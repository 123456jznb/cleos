package com.cleo.cleos.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.ConversationEntity
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

class ImportException(message: String) : Exception(message)

data class ImportedMessage(val role: String, val content: String, val at: Long)

data class ImportedConversation(
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ImportedMessage>,
)

data class ImportedDiary(val day: Long, val text: String, val at: Long)

/** What a file from another app would bring in, shown to the person before anything is written. */
data class ImportPlan(
    val conversations: List<ImportedConversation>,
    val diary: List<ImportedDiary>,
    /** The new TA's persona: the one set in that app's conversations, then what the model noted about the person. */
    val persona: String,
    /** Whether that app's conversations had a persona of their own. Its default one lives in its code, not in the backup. */
    val hadPersona: Boolean,
    /** That persona was longer than a persona here can be, and was cut. */
    val personaCut: Boolean,
    val memoryWritten: Int,
    val memoryLeftOut: Int,
    /** The service and model that app was using, when they could be told; else the current TA's are used. */
    val baseUrl: String?,
    val model: String?,
    /** Pictures the backup only names: it refers to files that stay in that app. */
    val picturesLeftBehind: Int,
) {
    val messageCount: Int get() = conversations.sumOf { it.messages.size }
}

/**
 * The JSON backup of phone-ai-assistant, a companion app written in Flutter
 * ("日记备份-….json", `app` = phone_ai_assistant, `formatVersion` 1).
 *
 * Its conversations are one TA's, so they all go to one new TA, with what the person and
 * the model said. Left out: that app's tool calls and their results (for tools that don't
 * exist here), system lines, the screenshots the model took of the phone screen, and
 * stickers the model sent (a key with no words). Pictures the person sent are only named
 * in that backup, so a line says one was sent; stickers the person sent are already words
 * ("[表情：困了]"). Deleted conversations and the per-book discussions stay behind.
 *
 * Its diary is written by the model, so it becomes the new TA's diary. Of what the model
 * noted (memory topics), the four kinds about the person go into the persona, so the TA
 * still knows them here; its notes about itself stay behind.
 */
object PhoneAssistantBackup {
    const val APP = "phone_ai_assistant"
    private const val FORMAT_VERSION = 1

    /** The kinds of note that are about the person, in that app's order, and how they are headed here. */
    private val ABOUT_THE_PERSON = listOf(
        "profile" to "对方的基本情况",
        "interest" to "对方在意的事",
        "recent" to "对方最近的情况（记下的时候是这样，可能已经过去了）",
        "rapport" to "对方希望你怎么相处",
    )

    private const val MEMORY_HEADER = "你以前记下的关于对方的事（从原来那个 App 带过来）："

    fun parse(text: String, zone: ZoneId, personaLimit: Int): ImportPlan {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw ImportException("读不出来：这不是一个 JSON 备份文件")
        if (root.str("app") != APP) throw ImportException("认不出这个文件：现在只认 $APP 导出的备份（日记备份-….json）")
        val version = root.int("formatVersion")
        if (version == null || version > FORMAT_VERSION) throw ImportException("这份备份的格式（版本 $version）比这里认得的新")
        val prefs = root["prefs"] as? JsonObject ?: JsonObject(emptyMap())

        var pictures = 0
        val personas = mutableListOf<Pair<Long, String>>()
        val conversations = (root["conversations"] as? JsonObject)?.values.orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val created = time(c.str("createdAt"), zone) ?: return@mapNotNull null
            val updated = time(c.str("updatedAt"), zone) ?: created
            c.str("systemPrompt")?.trim()?.takeIf { it.isNotEmpty() }?.let { personas += updated to it }
            val messages = (c["messages"] as? JsonArray).orEmpty().mapNotNull { m ->
                message(m, zone)?.let { (said, pics) ->
                    pictures += pics
                    said
                }
            }.sortedBy { it.at }
            if (messages.isEmpty()) return@mapNotNull null
            ImportedConversation(c.str("title")?.trim().orEmpty().ifEmpty { "新对话" }, created, updated, messages)
        }.sortedBy { it.createdAt }

        val diary = prefList(prefs, "diary_entries").mapNotNull { e ->
            val d = e as? JsonObject ?: return@mapNotNull null
            val body = d.str("content")?.trim().orEmpty()
            val day = day(d.str("date"), zone) ?: return@mapNotNull null
            if (body.isEmpty()) return@mapNotNull null
            val at = time(d.str("createdAt"), zone) ?: day.atStartOfDay(zone).toInstant().toEpochMilli()
            ImportedDiary(day.toEpochDay(), body, at)
        }.sortedBy { it.at }

        val topics = prefList(prefs, "memory_facts").mapNotNull { topic(it, zone) }
            .sortedWith(compareBy<Topic> { it.kind }.thenBy { it.created })

        // A persona set in a conversation replaced that app's default one there. The latest
        // one set is the one the TA was last talking with.
        val set = personas.maxByOrNull { it.first }?.second
        val base = set?.take(personaLimit).orEmpty()
        val room = personaLimit - base.length - (if (base.isEmpty()) 0 else 2)
        val memory = renderMemory(topics, room)

        val (baseUrl, model) = service(prefs)
        if (conversations.isEmpty() && diary.isEmpty() && memory.written == 0) {
            throw ImportException("这份备份里没有能带过来的对话、日记或记忆")
        }
        return ImportPlan(
            conversations = conversations,
            diary = diary,
            persona = listOf(base, memory.text).filter { it.isNotEmpty() }.joinToString("\n\n"),
            hadPersona = set != null,
            personaCut = set != null && set.length > personaLimit,
            memoryWritten = memory.written,
            memoryLeftOut = memory.leftOut,
            baseUrl = baseUrl,
            model = model,
            picturesLeftBehind = pictures,
        )
    }

    /** A message as it goes in, and how many pictures it had that don't come along. */
    private fun message(e: JsonElement, zone: ZoneId): Pair<ImportedMessage, Int>? {
        val m = e as? JsonObject ?: return null
        // The model's own screenshots of the phone screen: stored as the person's, said by no one.
        if ((m["metadata"] as? JsonObject)?.bool("glanceShot") == true) return null
        val at = time(m.str("timestamp"), zone) ?: return null
        val content = m.str("content").orEmpty().trim()
        return when (m.str("role")) {
            "user" -> {
                // Older messages kept one picture under imageData.
                val pics = (m["images"] as? JsonArray)?.size ?: if (m.str("imageData") != null) 1 else 0
                val note = if (pics > 0) "（这里发过 $pics 张图，没有一起搬过来）" else ""
                val text = listOf(note, content).filter { it.isNotEmpty() }.joinToString("\n")
                if (text.isEmpty()) null else ImportedMessage("user", text, at) to pics
            }
            "assistant" -> if (content.isEmpty()) null else ImportedMessage("assistant", content, at) to 0
            // "system" lines, "toolCall" and "toolResult": about that app, not something said.
            else -> null
        }
    }

    internal class Topic(val kind: Int, val line: String, val details: List<String>, val created: Long)

    private fun topic(e: JsonElement, zone: ZoneId): Topic? {
        val t = e as? JsonObject ?: return null
        // "self" is what the model noted about itself. Any other kind it doesn't know (and the
        // first, flat version, which had none) that app reads as "profile", and so does this.
        val category = t.str("category")
        if (category == "self") return null
        val kind = ABOUT_THE_PERSON.indexOfFirst { it.first == category }.coerceAtLeast(0)
        val name = t.str("name")?.trim().orEmpty().takeUnless { it == "未命名" }.orEmpty()
        val summary = (t.str("summary") ?: t.str("content"))?.trim().orEmpty()
        var line = listOf(name, summary).filter { it.isNotEmpty() }.joinToString("：")
        if (line.isEmpty()) return null
        val created = time(t.str("createdAt"), zone) ?: 0L
        val updated = time(t.str("updatedAt"), zone) ?: created
        // How recent "recent" is: without a date it reads as true today.
        if (ABOUT_THE_PERSON[kind].first == "recent" && updated > 0) {
            val d = java.time.Instant.ofEpochMilli(updated).atZone(zone).toLocalDate()
            line += "（${d.monthValue}月${d.dayOfMonth}日记下）"
        }
        val details = (t["details"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?: listOfNotNull(t.str("why")?.trim())
        return Topic(kind, line, details.filter { it.isNotEmpty() }, created)
    }

    internal class Memory(val text: String, val written: Int, val leftOut: Int)

    /**
     * The notes as persona text, no longer than [budget]. Every note's one line first, so the
     * TA knows what there is; then the details, as far as they fit.
     */
    internal fun renderMemory(topics: List<Topic>, budget: Int): Memory {
        if (topics.isEmpty()) return Memory("", 0, 0)
        var used = MEMORY_HEADER.length
        if (used > budget) return Memory("", 0, topics.size)
        val kept = mutableListOf<Topic>()
        val opened = mutableSetOf<Int>()
        var leftOut = 0
        for (t in topics) {
            val heading = if (t.kind in opened) 0 else ABOUT_THE_PERSON[t.kind].second.length + 2 // "\n" + heading + "："
            val cost = heading + 3 + t.line.length // "\n- " + line
            if (used + cost <= budget) {
                kept += t
                opened += t.kind
                used += cost
            } else {
                leftOut++
            }
        }
        if (kept.isEmpty()) return Memory("", 0, leftOut)
        val details = kept.map { mutableListOf<String>() }
        kept.forEachIndexed { i, t ->
            for (d in t.details) {
                val cost = 5 + d.length // "\n  · " + detail
                if (used + cost <= budget) {
                    details[i] += d
                    used += cost
                }
            }
        }
        val text = buildString {
            append(MEMORY_HEADER)
            var kind = -1
            kept.forEachIndexed { i, t ->
                if (t.kind != kind) {
                    append('\n').append(ABOUT_THE_PERSON[t.kind].second).append('：')
                    kind = t.kind
                }
                append("\n- ").append(t.line)
                for (d in details[i]) append("\n  · ").append(d)
            }
        }
        return Memory(text, kept.size, leftOut)
    }

    /**
     * The service that app was set to, if it speaks the same (OpenAI-compatible) way as this
     * one. An endpoint it didn't override is that provider's default, known here only for
     * the providers there are presets for.
     */
    private fun service(prefs: JsonObject): Pair<String?, String?> {
        val active = prefString(prefs, "api_active_provider")?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
        val endpoint = prefString(prefs, "api_endpoint_$active")?.trim()?.takeIf { usable(it) }
        val preset = if (endpoint == null) presetFor(active) else null
        val baseUrl = endpoint ?: preset?.baseUrl ?: return null to null
        val model = prefString(prefs, "api_model_$active")?.trim()?.takeIf { it.isNotEmpty() } ?: preset?.defaultModel
        return baseUrl to model
    }

    // Anthropic's own API speaks a different format; the chat here only speaks OpenAI's.
    private fun usable(url: String) =
        (url.startsWith("https://") || url.startsWith("http://")) && "anthropic.com" !in url.lowercase()

    private fun presetFor(id: String): ApiPreset? {
        val k = id.lowercase()
        val name = when {
            "deepseek" in k -> "DeepSeek"
            "openrouter" in k -> "OpenRouter"
            "openai" in k -> "OpenAI"
            "silicon" in k -> "硅基流动"
            "moonshot" in k || "kimi" in k -> "Kimi"
            else -> return null
        }
        return ApiPresets.all.firstOrNull { it.name == name }
    }

    /** A kept preference's value: `{"type": "string", "value": …}`. */
    private fun prefValue(prefs: JsonObject, key: String): JsonElement? = (prefs[key] as? JsonObject)?.get("value")

    private fun prefString(prefs: JsonObject, key: String): String? = (prefValue(prefs, key) as? JsonPrimitive)?.contentOrNull

    /** A list kept as a JSON string (the diary, the notes). */
    private fun prefList(prefs: JsonObject, key: String): List<JsonElement> = when (val v = prefValue(prefs, key)) {
        is JsonArray -> v
        is JsonPrimitive -> v.contentOrNull?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }.orEmpty()
        else -> emptyList()
    }

    /**
     * Dart's toIso8601String: local time without an offset ("2026-09-23T21:05:12.345678"),
     * or UTC with a Z.
     */
    internal fun time(s: String?, zone: ZoneId): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun day(s: String?, zone: ZoneId): LocalDate? {
        if (s.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(s).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(s.take(10)) }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}

/**
 * Brings a backup from another app in as a new TA. Nothing already here is touched, so
 * undoing it is deleting that TA.
 */
class ForeignImport(
    context: Context,
    private val db: AppDatabase,
    private val companions: Companions,
) {
    private val resolver = context.contentResolver

    suspend fun read(uri: Uri): ImportPlan = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { readAtMost(it, MAX_BYTES) } ?: throw ImportException("打不开这个文件")
        PhoneAssistantBackup.parse(bytes.decodeToString(), ZoneId.systemDefault(), Companions.PERSONA_LIMIT)
    }

    /** Writes [plan] as a new TA called [name], all in one transaction, and makes them the one being talked to. */
    suspend fun import(plan: ImportPlan, name: String): CompanionEntity = withContext(Dispatchers.IO) {
        val from = companions.current()
        val ta = CompanionEntity(
            name = name.trim(),
            persona = plan.persona,
            apiBaseUrl = plan.baseUrl ?: from.apiBaseUrl,
            apiModel = plan.model ?: from.apiModel,
            createdAt = System.currentTimeMillis(),
        )
        val id = db.withTransaction {
            val id = db.companions().insert(ta)
            for (c in plan.conversations) {
                val conversation = db.conversations().insert(
                    ConversationEntity(title = c.title, createdAt = c.createdAt, updatedAt = c.updatedAt, companionId = id),
                )
                db.messages().insertAll(
                    c.messages.map { MessageEntity(conversationId = conversation, role = it.role, content = it.content, createdAt = it.at) },
                )
            }
            db.diary().insertAll(
                plan.diary.map {
                    DiaryEntryEntity(
                        day = it.day,
                        title = "",
                        blocks = DiaryBlocks.encode(listOf(DiaryBlock.Text(it.text))),
                        createdAt = it.at,
                        updatedAt = it.at,
                        author = DiaryEntryEntity.AUTHOR_AI,
                        companionId = id,
                    )
                },
            )
            id
        }
        companions.select(id)
        ta.copy(id = id)
    }

    private fun readAtMost(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) {
                throw ImportException("文件太大了（超过 ${max / 1024 / 1024} MB），多半里面还夹着老的图片。在那边重新导出一份再试。")
            }
        }
        return out.toByteArray()
    }

    companion object {
        // Text only, even years of chat stay far below this. Old exports with pictures inside
        // ran to hundreds of MB, more than a phone can hold as one JSON tree.
        private const val MAX_BYTES = 32 * 1024 * 1024
    }
}
