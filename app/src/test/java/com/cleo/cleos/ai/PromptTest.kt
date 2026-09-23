package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PromptTest {
    private val now = ZonedDateTime.of(2026, 9, 22, 21, 5, 0, 0, ZoneId.of("Asia/Shanghai"))
    private val allTools = setOf(ToolGroup.Todos, ToolGroup.Diary, ToolGroup.Weather)
    private var id = 0L
    private fun msg(role: String, content: String, error: String? = null) =
        MessageEntity(id = ++id, conversationId = 1, role = role, content = content, createdAt = id, error = error)

    private fun calling(content: String, vararg calls: ToolCall, reasoning: String? = null) =
        msg("assistant", content).copy(toolCalls = ToolCallCodec.encode(calls.toList()), reasoning = reasoning)

    private fun result(callId: String, content: String) =
        msg("tool", content).copy(toolCallId = callId, note = "记下了待办")

    private val add = ToolCall("c1", "add_todo", """{"title":"交报告"}""")

    @Test
    fun systemHasNamesButNoTimeAndNoInventedRelationship() {
        val s = Prompt.system(AppSettings(aiName = "沐", userName = "Cleo"))
        assertTrue(s.contains("你叫沐。"))
        assertTrue(s.contains("和你说话的人叫Cleo。"))
        assertFalse("time must stay out of the cached prefix", s.contains("现在是"))
        for (label in listOf("朋友", "恋人", "温柔")) assertFalse(s.contains(label))
    }

    @Test
    fun withoutToolsTheOnlyAddedLineIsTheFormatRule() {
        val s = Prompt.system(AppSettings())
        assertFalse(s.contains("你叫"))
        assertFalse(s.contains("工具"))
        assertTrue(s.contains("Markdown"))
    }

    @Test
    fun toolRulesComeOnlyForToolsOffered() {
        val todosOnly = Prompt.system(AppSettings(), setOf(ToolGroup.Todos))
        assertTrue(todosOnly.contains("没有调用工具，就不要说已经做好了"))
        assertFalse(todosOnly.contains("日记"))
        assertTrue(Prompt.system(AppSettings(), setOf(ToolGroup.Diary)).contains("只在对方提起或问到日记里写过的事时"))
    }

    @Test
    fun itsOwnDiaryAndTheSecretsComeWithTheirRules() {
        val s = Prompt.system(AppSettings(), setOf(ToolGroup.AiDiary, ToolGroup.Secrets))
        assertTrue(s.contains("不是替对方写"))
        assertTrue(s.contains("被拒绝了就别追着要"))
        assertFalse("reading the person's diary is a separate permission", s.contains("只在对方提起或问到日记里写过的事时"))
    }

    @Test
    fun timeRidesOnTheLastUserMessageOnlyAndCarriesTheYear() {
        val out = Prompt.messages(
            AppSettings(),
            listOf(msg("user", "早"), msg("assistant", "早呀"), msg("user", "今天好累")),
            now,
        )
        assertEquals(listOf("system", "user", "assistant", "user"), out.map { it.role })
        assertEquals("早", out[1].content)
        assertTrue(out[3].content.startsWith("（现在是2026年9月22日 星期二 21:05）"))
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

    @Test
    fun aToolRoundGoesBackAsCallThenResult() {
        val out = Prompt.messages(
            AppSettings(),
            listOf(
                msg("user", "明天交报告，帮我记一下"),
                calling("", add, reasoning = "要记待办"),
                result("c1", "已添加：#3 交报告"),
                msg("assistant", "记好啦"),
                msg("user", "谢谢"),
            ),
            now,
            allTools,
        )
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant", "user"), out.map { it.role })
        assertEquals(listOf(add), out[2].toolCalls)
        assertEquals("c1", out[3].toolCallId)
        assertNull("an earlier turn's reasoning is not sent back", out[2].reasoning)
    }

    @Test
    fun theTurnStillUnderWayKeepsItsReasoning() {
        // A retry after the final answer failed: the calls already ran and are not redone.
        val out = Prompt.messages(
            AppSettings(),
            listOf(msg("user", "记一下"), calling("", add, reasoning = "要记待办"), result("c1", "已添加")),
            now,
            allTools,
        )
        assertEquals(listOf("system", "user", "assistant", "tool"), out.map { it.role })
        assertEquals("要记待办", out[2].reasoning)
    }

    @Test
    fun unansweredCallsAndStrayResultsAreDropped() {
        val out = Prompt.messages(
            AppSettings(),
            listOf(
                result("gone", "the window cut its call off"),
                msg("user", "查天气"),
                calling("我看看", ToolCall("w", "get_weather", "{}")),
                // stopped before the tool ran: no result
                msg("user", "算了"),
            ),
            now,
            allTools,
        )
        assertEquals(listOf("system", "user", "assistant", "user"), out.map { it.role })
        assertTrue(out[2].toolCalls.isEmpty())
        assertEquals("我看看", out[2].content)
    }

    @Test
    fun withoutToolsOnlyWhatWasSaidRemains() {
        val out = Prompt.messages(
            AppSettings(),
            listOf(msg("user", "记一下"), calling("好", add), result("c1", "已添加"), msg("assistant", "记好了"), msg("user", "嗯")),
            now,
        )
        assertEquals(listOf("system", "user", "assistant", "user"), out.map { it.role })
        assertEquals("好\n\n记好了", out[2].content)
        assertTrue(out.all { it.toolCalls.isEmpty() && it.toolCallId == null })
    }

    @Test
    fun notesAreNeverSentAndTheWindowStartsAtAUserTurn() {
        val out = Prompt.messages(
            AppSettings(),
            listOf(
                msg("assistant", "the window starts here, mid-exchange"),
                msg("user", "你好"),
                msg("note", "").copy(note = "这个模型不接受工具调用"),
                msg("assistant", "你好呀"),
            ),
            now,
            allTools,
        )
        assertEquals(listOf("system", "user", "assistant"), out.map { it.role })
        assertEquals("你好呀", out[2].content)
    }
}
