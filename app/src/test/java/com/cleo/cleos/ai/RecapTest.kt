package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RecapTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = LocalDateTime.of(2026, 9, 24, 21, 0).atZone(zone).toInstant().toEpochMilli()
    private val minute = 60_000L
    private val ta = CompanionEntity(id = 1, name = "星", apiBaseUrl = "", apiModel = "", createdAt = 0)

    /** Turns taking turns, the person first, a minute apart. */
    private fun chat(n: Int): List<MessageEntity> = (0 until n).map { i ->
        MessageEntity(
            id = i + 1L,
            conversationId = 1,
            role = if (i % 2 == 0) "user" else "assistant",
            content = "第${i + 1}条",
            createdAt = start + i * minute,
        )
    }

    @Test
    fun nothingIsFoldedWhileTheWindowHasRoom() {
        assertEquals(20, Recap.step(40))
        assertEquals(10, Recap.step(10))
        assertTrue(Recap.toFold(chat(60), 40).isEmpty())
        // What goes out verbatim: the window and its step, the newest.
        val sent = Recap.sent(chat(80), 40)
        assertEquals(60, sent.size)
        assertEquals(80L, sent.last().id)
    }

    @Test
    fun aFoldLeavesTheWindowAndEndsBeforeTheirTurn() {
        val live = chat(61)
        val batch = Recap.toFold(live, 40)
        // 61 - 40 = 21 would open the window on a reply; stopping one earlier keeps the pair together.
        assertEquals(20, batch.size)
        assertEquals(1L, batch.first().id)
        assertEquals("user", live[batch.size].role)
    }

    @Test
    fun aLongBacklogIsCaughtUpInPieces() {
        val live = chat(2000)
        val first = Recap.toFold(live, 40)
        // Only the last CATCH_UP messages before the window; the ones before them stay out.
        assertEquals(live[2000 - 40 - Recap.CATCH_UP].id, first.first().id)
        assertTrue(first.size in Recap.MAX_FOLD..Recap.MAX_FOLD + 1)
        assertEquals("user", live[live.indexOf(first.last()) + 1].role)
        // After it, the rest in the same way, until the window is all that is left.
        var rest = live.drop(live.indexOf(first.last()) + 1)
        var rounds = 1
        while (true) {
            val next = Recap.toFold(rest, 40)
            if (next.isEmpty()) break
            rest = rest.drop(next.size)
            rounds++
        }
        assertEquals(40, rest.size)
        assertEquals(4, rounds)
    }

    @Test
    fun theTranscriptReadsLikeTheChat() {
        val morning = start + 12 * 60 * minute
        val batch = listOf(
            MessageEntity(id = 1, conversationId = 1, role = "user", content = "今天好累\n想早点睡", createdAt = start),
            MessageEntity(id = 2, conversationId = 1, role = "assistant", content = "那就早点睡", createdAt = start + minute),
            // A call that said nothing, and its result: no line.
            MessageEntity(id = 3, conversationId = 1, role = "assistant", content = "", createdAt = start + minute + 1, toolCalls = "[]"),
            MessageEntity(id = 4, conversationId = 1, role = "tool", content = "已记下", createdAt = start + minute + 2, note = "记下了待办"),
            MessageEntity(
                id = 5,
                conversationId = 1,
                role = "user",
                content = "早",
                createdAt = morning,
                images = MessageImages.encode(listOf(MessageImage("a.jpg", 10, 10))),
            ),
            // Their answer to a request for a secret: the line, never the entry itself.
            MessageEntity(id = 6, conversationId = 1, role = "user", content = "（日记的内容）", createdAt = morning + minute, note = "给星看了9月3日的小秘密"),
            MessageEntity(id = 7, conversationId = 1, role = "assistant", content = "", createdAt = morning + 2 * minute, error = "网络出错"),
        )
        assertEquals(
            "9月24日\n21:00 对方：今天好累 想早点睡\n21:01 我：那就早点睡\n9月25日\n09:00 对方：早 [发了1张图]\n09:01 （给星看了9月3日的小秘密）",
            Recap.transcript(batch, zone),
        )
    }

    @Test
    fun theFoldIsWrittenOnTopOfTheOldRecap() {
        val request = Recap.request(ta, "小雨", "9月20日一起看了电影。", chat(2), zone)
        assertEquals(listOf("system", "user"), request.map { it.role })
        assertTrue(request[0].content.startsWith("你是星。你和小雨一直在手机上聊天。"))
        assertTrue(request[0].content.contains("不超过 ${Recap.LENGTH} 字"))
        assertEquals("【旧的前情提要】\n9月20日一起看了电影。\n\n【接下来的聊天记录】\n9月24日\n21:00 对方：第1条\n21:01 我：第2条", request[1].content)
        assertTrue(Recap.request(ta, "", null, chat(2), zone)[1].content.startsWith("【旧的前情提要】\n（还没有）"))
        // What the model sends back, without the heading it sometimes puts on.
        assertEquals("我们聊了猫。", Recap.clean("前情提要：我们聊了猫。\n"))
        assertEquals("我们聊了猫。", Recap.clean("【前情提要】\n我们聊了猫。"))
        assertNull(Recap.clean("  "))
    }

    @Test
    fun theRecapComesLastInTheSystemPrompt() {
        val s = Prompt.system(AppSettings(), ta, recap = "我们聊了猫。")
        assertTrue(s.substringAfterLast("\n\n").startsWith("【前情提要】"))
        assertTrue(s.endsWith("我们聊了猫。"))
        assertFalse(Prompt.system(AppSettings(), ta).contains("前情提要"))
        val now = LocalDateTime.of(2026, 9, 25, 1, 0).atZone(zone)
        val out = Prompt.messages(AppSettings(), ta, chat(2), now, recap = "我们聊了猫。")
        assertTrue(out.first().content.endsWith("我们聊了猫。"))
    }
}
