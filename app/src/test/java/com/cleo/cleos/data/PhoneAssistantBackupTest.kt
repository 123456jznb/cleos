package com.cleo.cleos.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Backups shaped the way phone-ai-assistant writes them (Conversation/ChatMessage/MemoryTopic toJson). */
class PhoneAssistantBackupTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun parse(json: String, limit: Int = Companions.PERSONA_LIMIT) = PhoneAssistantBackup.parse(json, zone, limit)

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0, nanos: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s, nanos).atZone(zone).toInstant().toEpochMilli()

    private fun backup(
        conversations: Map<String, JsonObject> = emptyMap(),
        prefs: Map<String, JsonElement> = emptyMap(),
        app: String = "phone_ai_assistant",
        version: Int = 1,
    ) = buildJsonObject {
        put("app", app)
        put("formatVersion", version)
        put("exportedAt", "2026-09-24T10:00:00.000")
        put("containsSecrets", false)
        put("conversations", JsonObject(conversations))
        put("conversationsTrash", JsonObject(mapOf("gone" to conversation("删掉的", listOf(msg("user", "这段删过了", "2026-08-01T10:00:00.000"))))))
        put("bookConversations", JsonObject(mapOf("book_1" to conversation("读书", listOf(msg("user", "这本书的讨论", "2026-08-02T10:00:00.000"))))))
        put("prefs", JsonObject(prefs))
    }.toString()

    private fun msg(
        role: String,
        content: String,
        at: String,
        images: List<String>? = null,
        metadata: JsonObject? = null,
        toolCall: Boolean = false,
        extra: Map<String, JsonElement> = emptyMap(),
    ) = buildJsonObject {
        put("id", "$role-$at")
        put("role", role)
        put("content", content)
        put("timestamp", at)
        put(
            "toolCalls",
            if (toolCall) {
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "t1")
                            put("name", "write_diary_entry")
                            put("arguments", buildJsonObject { put("content", "写一篇") })
                            put("result", "{\"success\":true}")
                        },
                    )
                }
            } else {
                JsonNull
            },
        )
        put("toolCallId", JsonNull)
        images?.let { put("images", JsonArray(it.map { s -> JsonPrimitive(s) })) }
        metadata?.let { put("metadata", it) }
        extra.forEach { (k, v) -> put(k, v) }
    }

    private fun conversation(
        title: String,
        messages: List<JsonObject>,
        created: String = "2026-09-01T08:00:00.000",
        updated: String = "2026-09-01T09:00:00.000",
        persona: String? = null,
    ) = buildJsonObject {
        put("id", title)
        put("title", title)
        put("createdAt", created)
        put("updatedAt", updated)
        put("messages", JsonArray(messages))
        put("model", "deepseek-v4-flash")
        put("systemPrompt", persona?.let { JsonPrimitive(it) } ?: JsonNull)
        put("titleManuallySet", false)
        put("isPinned", false)
    }

    private fun stringPref(value: String) = buildJsonObject {
        put("type", "string")
        put("value", value)
    }

    private val oneLine = mapOf("c" to conversation("一句", listOf(msg("user", "在吗", "2026-09-01T08:00:00.000"))))

    private fun expectRefused(json: String) {
        try {
            parse(json)
            fail("should have been refused")
        } catch (_: ImportException) {
        }
    }

    @Test
    fun onlyThatAppsBackupIsTaken() {
        expectRefused("not json")
        expectRefused(backup(oneLine, app = "cleos-backup"))
        expectRefused(backup(oneLine, version = 2))
        expectRefused(backup())
    }

    @Test
    fun whatWasSaidComesOverAndTheRestStays() {
        val talk = conversation(
            "雨天",
            listOf(
                msg("user", "早", "2026-09-01T08:00:00.123456"),
                msg("assistant", "", "2026-09-01T08:00:05.000", toolCall = true),
                msg("toolCall", "", "2026-09-01T08:00:05.500"),
                msg("toolResult", "{\"success\":true}", "2026-09-01T08:00:06.000"),
                msg("assistant", " 早呀\n\n今天下雨 ", "2026-09-01T08:00:10.000"),
                msg("user", "", "2026-09-01T08:01:00.000", images = listOf("file:a.jpg", "cleared:")),
                msg("user", "[表情：困了]", "2026-09-01T08:02:00.000", metadata = buildJsonObject { put("sticker", "sleepy") }),
                msg("assistant", "", "2026-09-01T08:03:00.000", metadata = buildJsonObject { put("sticker", "sleepy") }),
                msg(
                    "user",
                    "",
                    "2026-09-01T08:04:00.000",
                    images = listOf("file:screen.jpg"),
                    metadata = buildJsonObject {
                        put("glance", true)
                        put("glanceShot", true)
                    },
                ),
                msg("system", "（系统提示）", "2026-09-01T08:05:00.000"),
                msg("assistant", "我刚才想到你", "2026-09-01T09:00:00.000", metadata = buildJsonObject { put("nudge", true) }),
                // Stored in UTC: 01:20Z is 09:20 here, so it sorts after the 09:00 line.
                msg("user", "嗯", "2026-09-01T01:20:00.000Z"),
                msg("user", "看", "2026-09-01T09:30:00.000", extra = mapOf("imageData" to JsonPrimitive("AAAA"))),
            ),
        )
        val onlySystem = conversation("空的", listOf(msg("system", "只有系统行", "2026-09-02T08:00:00.000")))
        val plan = parse(backup(mapOf("a" to talk, "b" to onlySystem)))

        assertEquals(1, plan.conversations.size)
        val c = plan.conversations.single()
        assertEquals("雨天", c.title)
        assertEquals(
            listOf(
                "user" to "早",
                "assistant" to "早呀\n\n今天下雨",
                "user" to "（这里发过 2 张图，没有一起搬过来）",
                "user" to "[表情：困了]",
                "assistant" to "我刚才想到你",
                "user" to "嗯",
                "user" to "（这里发过 1 张图，没有一起搬过来）\n看",
            ),
            c.messages.map { it.role to it.content },
        )
        assertEquals("microseconds are read, not refused", millis(2026, 9, 1, 8, 0, 0, 123_000_000), c.messages.first().at)
        assertEquals(millis(2026, 9, 1, 9, 20), c.messages[5].at)
        assertEquals("its own screenshots are not counted", 3, plan.picturesLeftBehind)
        assertEquals(7, plan.messageCount)
    }

    @Test
    fun deletedConversationsAndBookDiscussionsStayBehind() {
        val plan = parse(backup(oneLine))
        assertEquals(listOf("一句"), plan.conversations.map { it.title })
    }

    @Test
    fun itsDiaryBecomesTheNewTasOwn() {
        val entries = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", "d1")
                    put("date", "2026-09-01T00:00:00.000")
                    put("content", " 今天下雨，我们聊了很久。 ")
                    put("createdAt", "2026-09-01T23:10:00.000")
                },
            )
            add(
                buildJsonObject {
                    put("id", "d2")
                    put("date", "2026-09-02T00:00:00.000")
                    put("content", "   ")
                    put("createdAt", "2026-09-02T23:10:00.000")
                },
            )
        }
        val plan = parse(backup(prefs = mapOf("diary_entries" to stringPref(entries.toString()))))
        val d = plan.diary.single()
        assertEquals(LocalDate.of(2026, 9, 1).toEpochDay(), d.day)
        assertEquals("今天下雨，我们聊了很久。", d.text)
        assertEquals(millis(2026, 9, 1, 23, 10), d.at)
    }

    private fun topic(
        category: String?,
        name: String?,
        summary: String,
        details: List<String> = emptyList(),
        created: String = "2026-09-01T10:00:00.000",
        updated: String = created,
    ) = buildJsonObject {
        put("id", "$category-$summary")
        category?.let { put("category", it) }
        name?.let { put("name", it) }
        put("summary", summary)
        put("details", JsonArray(details.map { JsonPrimitive(it) }))
        put("source", "ai")
        put("pinned", false)
        put("createdAt", created)
        put("updatedAt", updated)
    }

    @Test
    fun whatItNotedAboutThePersonComesAsMemories() {
        val notes = buildJsonArray {
            add(topic("self", "我自己", "我发现我说话太长"))
            add(topic("interest", "读书", "最近在读小说", listOf("喜欢睡前读")))
            add(topic("profile", "称呼", "喜欢被叫小名", created = "2026-08-01T10:00:00.000"))
            add(topic("recent", "考试", "在准备考试", updated = "2026-09-20T10:00:00.000"))
            // The first, flat version: no kind, content and why.
            add(
                buildJsonObject {
                    put("id", "old")
                    put("content", "不喜欢被反问")
                    put("why", "说过一次")
                    put("pinned", true)
                    put("createdAt", "2026-07-01T10:00:00.000")
                },
            )
            add(topic("rapport", "未命名", "吵架后先道歉"))
            add(topic("mood", "心情", "一种它不认识的分类"))
        }
        val older = conversation("早先", listOf(msg("user", "早", "2026-08-01T08:00:00.000")), updated = "2026-08-01T09:00:00.000", persona = "旧的性格")
        val latest = conversation("最近", listOf(msg("user", "晚", "2026-09-20T22:00:00.000")), updated = "2026-09-20T22:00:00.000", persona = "你说话很短。")
        val plan = parse(backup(mapOf("a" to older, "b" to latest), prefs = mapOf("memory_facts" to stringPref(notes.toString()))))

        assertEquals("the persona is only the one set there", "你说话很短。", plan.persona)
        assertTrue(plan.hadPersona)
        val m = plan.memories
        assertFalse("its notes about itself stay behind", m.any { it.summary.contains("说话太长") })
        assertEquals(
            "kind by kind, oldest first; an unknown kind reads as profile",
            listOf("profile", "profile", "profile", "interest", "recent", "rapport"),
            m.map { it.kind },
        )
        val flat = m.first()
        assertEquals("不喜欢被反问", flat.summary)
        assertEquals("a flat note's name comes from its words", "不喜欢被反问", flat.name)
        assertEquals(listOf("说过一次"), flat.details)
        assertTrue(flat.pinned)
        assertEquals(listOf("喜欢睡前读"), m.first { it.name == "读书" }.details)
        assertEquals("吵架后先道歉", m.first { it.kind == "rapport" }.name)
        assertEquals(millis(2026, 9, 20, 10, 0), m.first { it.kind == "recent" }.updatedAt)
    }

    @Test
    fun onlyTheMemoriesCanBeReadToFillInATa() {
        val notes = buildJsonArray { add(topic("interest", "读书", "最近在读小说", List(14) { "细节$it" })) }
        val text = backup(oneLine, mapOf("memory_facts" to stringPref(notes.toString())))
        val m = PhoneAssistantBackup.parseMemories(text, zone).single()
        assertEquals("every detail comes; the cap is applied when stored", 14, m.details.size)
        try {
            PhoneAssistantBackup.parseMemories(backup(oneLine), zone)
            fail("a backup without notes has nothing to fill in")
        } catch (_: ImportException) {
        }
        try {
            PhoneAssistantBackup.parseMemories("{}", zone)
            fail("not that app's backup")
        } catch (_: ImportException) {
        }
    }

    @Test
    fun aPersonaLongerThanAPersonaHereIsCut() {
        val long = conversation("长", listOf(msg("user", "嗯", "2026-09-01T08:00:00.000")), persona = "性".repeat(300))
        val cut = parse(backup(mapOf("a" to long)), limit = 200)
        assertTrue(cut.personaCut)
        assertEquals(200, cut.persona.length)
    }

    @Test
    fun theServiceItUsedComesAlongWhenItSpeaksTheSameWay() {
        fun service(vararg prefs: Pair<String, String>) =
            parse(backup(oneLine, prefs.associate { (k, v) -> k to stringPref(v) })).let { it.baseUrl to it.model }

        assertEquals(
            "https://api.deepseek.com/v1" to "deepseek-v4-pro",
            service("api_active_provider" to "deepseek", "api_endpoint_deepseek" to "https://api.deepseek.com/v1", "api_model_deepseek" to "deepseek-v4-pro"),
        )
        // Not overridden there: that provider's default, when there is a preset for it.
        assertEquals(
            ApiPresets.DeepSeek.baseUrl to "deepseek-v4-pro",
            service("api_active_provider" to "deepseek", "api_model_deepseek" to "deepseek-v4-pro"),
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4" to "glm-4.7",
            service("api_active_provider" to "zhipu", "api_model_zhipu" to "glm-4.7"),
        )
        // A provider without a preset, and without its address: nothing to go on.
        assertEquals(null to null, service("api_active_provider" to "baichuan", "api_model_baichuan" to "baichuan4"))
        assertNull(service("api_active_provider" to "claude", "api_endpoint_claude" to "https://api.anthropic.com").first)
        assertEquals(null to null, service())
    }
}
