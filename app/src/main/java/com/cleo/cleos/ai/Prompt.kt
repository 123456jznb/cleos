package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.db.MessageEntity
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the model is told, and in which order.
 *
 * The system prompt carries what the user wrote: the two names and the persona. Names
 * are given bare, with no adjectives attached. A label like "gentle" or "a friend" gets
 * performed rather than lived in, and deciding what the relationship is, is not the
 * app's job.
 *
 * The app adds two things of its own. One line about format, because this is a chat
 * screen that shows plain text: markdown headings and bullet lists would arrive as raw
 * symbols. And, when tools are on, when to use them.
 *
 * The current time is not in the system prompt. Providers cache the longest unchanged
 * prefix of a request; a clock ticking in the first message would change the prefix on
 * every turn and throw the whole history out of the cache. So the time rides on the last
 * user message, after everything that stays the same.
 */
object Prompt {
    private const val FORMAT_RULE = "这是手机上的聊天。像平常发消息那样回复，不用 Markdown 标题、列表和加粗。"

    fun system(settings: AppSettings, tools: Set<ToolGroup> = emptySet()): String = buildList {
        if (settings.aiName.isNotBlank()) add("你叫${settings.aiName.trim()}。")
        if (settings.userName.isNotBlank()) add("和你说话的人叫${settings.userName.trim()}。")
        if (settings.persona.isNotBlank()) add(settings.persona.trim())
        toolRule(tools)?.let(::add)
        add(FORMAT_RULE)
    }.joinToString("\n\n")

    /**
     * Offering tools without saying when to use them fails in two ways: the model says
     * "记好啦" without calling anything, or it goes through the diary unasked. Only the
     * rules for the tools actually offered are included.
     */
    private fun toolRule(tools: Set<ToolGroup>): String? = buildList {
        if (ToolGroup.Todos in tools) {
            add("对方让你记下、修改、完成或查看待办时，用工具去做，做完再告诉对方；没有调用工具，就不要说已经做好了。")
        }
        if (ToolGroup.Diary in tools) add("你可以读对方的日记，但只在对方提起或问到日记里写过的事时才去读。")
        if (ToolGroup.Weather in tools) add("问到天气时用工具查，不要凭印象说。")
    }.takeIf { it.isNotEmpty() }?.joinToString("")

    /**
     * [history] oldest first, already trimmed to the window. With [tools] empty, tool
     * calls and their results are left out and only what was said remains, so a model
     * without tool support can read a conversation that used them.
     */
    fun messages(
        settings: AppSettings,
        history: List<MessageEntity>,
        now: ZonedDateTime,
        tools: Set<ToolGroup> = emptySet(),
    ): List<ApiMessage> {
        val withTools = tools.isNotEmpty()
        val sendable = history.mapNotNull { it.toApi(withTools) }
        val paired = if (withTools) pairCalls(sendable) else sendable
        // The window can start mid-exchange; begin at a user turn, which every endpoint accepts.
        val fromUser = paired.dropWhile { it.role != "user" }.ifEmpty { paired }

        // Two user messages in a row happen whenever a reply failed in between. Some
        // endpoints reject consecutive same-role turns, so they are joined into one.
        val merged = mutableListOf<ApiMessage>()
        for (m in fromUser) {
            val last = merged.lastOrNull()
            if (last != null && last.role == m.role && m.role != "tool" && last.toolCalls.isEmpty()) {
                merged[merged.lastIndex] = m.copy(content = listOf(last.content, m.content).filter { it.isNotEmpty() }.joinToString("\n\n"))
            } else {
                merged += m
            }
        }
        val lastUser = merged.indexOfLast { it.role == "user" }
        // Reasoning goes back only within the turn still under way; earlier turns' is
        // dropped (DeepSeek asks for exactly this, and it is dead weight anywhere else).
        for (i in 0 until lastUser) {
            if (merged[i].reasoning != null) merged[i] = merged[i].copy(reasoning = null)
        }
        if (lastUser >= 0) {
            merged[lastUser] = merged[lastUser].let { it.copy(content = "（${timeLine(now)}）\n${it.content}") }
        }
        return listOf(ApiMessage("system", system(settings, tools))) + merged
    }

    private fun MessageEntity.toApi(withTools: Boolean): ApiMessage? = when (role) {
        "user" -> content.takeIf { it.isNotBlank() }?.let { ApiMessage("user", it) }
        "assistant" -> {
            val calls = if (withTools) ToolCallCodec.decode(toolCalls) else emptyList()
            when {
                // A half reply ending mid-sentence invites the model to continue it.
                error != null -> null
                calls.isNotEmpty() -> ApiMessage("assistant", content, calls, reasoning = reasoning)
                content.isNotBlank() -> ApiMessage("assistant", content)
                else -> null
            }
        }
        "tool" -> if (withTools && toolCallId != null) ApiMessage("tool", content, toolCallId = toolCallId) else null
        // "note" lines are for the person reading the chat, not for the model.
        else -> null
    }

    /**
     * Endpoints reject a call without its result and a result without its call. Both
     * happen: the history window can cut between them, and stopping mid-tool leaves a
     * call unanswered. Unanswered calls are dropped from their turn (the text stays),
     * and results are kept only right after the call they answer.
     */
    private fun pairCalls(list: List<ApiMessage>): List<ApiMessage> {
        val out = ArrayList<ApiMessage>(list.size)
        var i = 0
        while (i < list.size) {
            val m = list[i]
            if (m.role == "tool") {
                i++
                continue
            }
            if (m.toolCalls.isEmpty()) {
                out += m
                i++
                continue
            }
            var j = i + 1
            val results = ArrayList<ApiMessage>()
            while (j < list.size && list[j].role == "tool") results += list[j++]
            val answered = m.toolCalls.filter { c -> results.any { it.toolCallId == c.id } }
            if (answered.isNotEmpty()) {
                out += m.copy(toolCalls = answered)
                out += results.filter { r -> answered.any { it.id == r.toolCallId } }.distinctBy { it.toolCallId }
            } else if (m.content.isNotBlank()) {
                out += m.copy(toolCalls = emptyList(), reasoning = null)
            }
            i = j
        }
        return out
    }

    // With the year: tools take dates as YYYY-MM-DD, and a model left to guess the year
    // puts a deadline in the past.
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINA)

    fun timeLine(now: ZonedDateTime): String = "现在是" + now.format(timeFormat)
}
