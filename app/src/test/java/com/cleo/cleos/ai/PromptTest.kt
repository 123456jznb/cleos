package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PromptTest {
    private val now = ZonedDateTime.of(2026, 9, 22, 21, 5, 0, 0, ZoneId.of("Asia/Shanghai"))
    private var id = 0L
    private fun msg(role: String, content: String, error: String? = null) =
        MessageEntity(id = ++id, conversationId = 1, role = role, content = content, createdAt = id, error = error)

    @Test
    fun systemHasNamesButNoTimeAndNoInventedRelationship() {
        val s = Prompt.system(AppSettings(aiName = "沐", userName = "Cleo"))
        assertTrue(s.contains("你叫沐。"))
        assertTrue(s.contains("和你说话的人叫Cleo。"))
        assertFalse("time must stay out of the cached prefix", s.contains("现在是"))
        for (label in listOf("朋友", "恋人", "温柔")) assertFalse(s.contains(label))
    }

    @Test
    fun emptySettingsStillProduceOnlyTheFormatRule() {
        val s = Prompt.system(AppSettings())
        assertFalse(s.contains("你叫"))
        assertTrue(s.contains("Markdown"))
    }

    @Test
    fun timeRidesOnTheLastUserMessageOnly() {
        val out = Prompt.messages(
            AppSettings(),
            listOf(msg("user", "早"), msg("assistant", "早呀"), msg("user", "今天好累")),
            now,
        )
        assertEquals(listOf("system", "user", "assistant", "user"), out.map { it.role })
        assertEquals("早", out[1].content)
        assertTrue(out[3].content.startsWith("（现在是9月22日 星期二 21:05）"))
        assertTrue(out[3].content.endsWith("今天好累"))
    }

    @Test
    fun failedRepliesAreLeftOutAndTheUserTurnsAroundThemMerged() {
        val out = Prompt.messages(
            AppSettings(),
            listOf(msg("user", "第一句"), msg("assistant", "说到一半", error = "网络出错"), msg("user", "第二句")),
            now,
        )
        assertEquals(listOf("system", "user"), out.map { it.role })
        assertTrue(out[1].content.contains("第一句"))
        assertTrue(out[1].content.endsWith("第二句"))
    }
}
