package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    /**
     * Pictures sent along with a request, the most recent first. Every picture goes out
     * again with every turn while it is included, so only the last few are; older ones
     * are named in the text but not attached.
     */
    const val MAX_IMAGES = 4

    fun system(settings: AppSettings, ta: CompanionEntity, tools: Set<ToolGroup> = emptySet()): String = buildList {
        if (ta.name.isNotBlank()) add("你叫${ta.name.trim()}。")
        if (settings.userName.isNotBlank()) add("和你说话的人叫${settings.userName.trim()}。")
        if (ta.persona.isNotBlank()) add(ta.persona.trim())
        toolRule(tools)?.let(::add)
        add(FORMAT_RULE)
    }.joinToString("\n\n")

    /**
     * Offering tools without saying when to use them fails in two ways: the model says
     * "记好啦" without calling anything, or it goes through the diary unasked. Only the
     * rules for the tools actually offered are included.
     */
    private fun toolRule(tools: Set<ToolGroup>): String? = buildList {
        if (ToolGroup.Messages in tools) {
            add("想分成几条消息说的时候，用 send_message 一条一条发：一条只说一件事，要发几条就在同一次回复里调用几次。只说一句就直接回复。用 send_message 发过的话，别再在回复里写一遍，也别说「发好了」。")
        }
        if (ToolGroup.Todos in tools) {
            add("对方让你记下、修改、完成或查看待办时，用工具去做，做完再告诉对方；没有调用工具，就不要说已经做好了。")
        }
        if (ToolGroup.Diary in tools) add("你可以读对方的日记，但只在对方提起或问到日记里写过的事时才去读。")
        if (ToolGroup.AiDiary in tools) {
            add("你有自己的日记，和对方的写在同一个本子里。对方让你写，或者你真有想记下来的事，就用 write_diary 写：写你自己的所见所想，用第一人称，不是替对方写。")
        }
        if (ToolGroup.Secrets in tools) {
            add("对方可以把日记设成小秘密，你看不到。想看就用 request_secret 问，对方点头才会给你看；被拒绝了就别追着要。")
        }
        if (ToolGroup.Avatar in tools) add("你可以用 set_my_avatar 换自己的头像：用对方发来的一张图，或者一个表情。")
        if (ToolGroup.Weather in tools) add("问到天气时用工具查，不要凭印象说。")
    }.takeIf { it.isNotEmpty() }?.joinToString("")

    /**
     * [history] oldest first, already trimmed to the window. With [tools] empty, tool
     * calls and their results are left out and only what was said remains, so a model
     * without tool support can read a conversation that used them. With [images] false
     * no picture is attached (the text still says one was sent).
     */
    fun messages(
        settings: AppSettings,
        ta: CompanionEntity,
        history: List<MessageEntity>,
        now: ZonedDateTime,
        tools: Set<ToolGroup> = emptySet(),
        images: Boolean = false,
    ): List<ApiMessage> {
        val withTools = tools.isNotEmpty()
        val attached = if (images) attachedPictures(history) else emptySet()
        val converted = history.mapNotNull { m -> m.toApi(withTools, images, attached)?.let { m.id to it } }
        val sendable = if (ToolGroup.Messages in tools) asSentMessages(converted) else converted.map { it.second }
        val paired = if (withTools) pairCalls(sendable) else sendable
        // The window can start mid-exchange; begin at a user turn, which every endpoint accepts.
        val fromUser = paired.dropWhile { it.role != "user" }.ifEmpty { paired }

        // Two user messages in a row happen whenever a reply failed in between. Some
        // endpoints reject consecutive same-role turns, so they are joined into one.
        val merged = mutableListOf<ApiMessage>()
        for (m in fromUser) {
            val last = merged.lastOrNull()
            if (last != null && last.role == m.role && m.role != "tool" && last.toolCalls.isEmpty()) {
                merged[merged.lastIndex] = m.copy(
                    content = listOf(last.content, m.content).filter { it.isNotEmpty() }.joinToString("\n\n"),
                    images = last.images + m.images,
                )
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
        return listOf(ApiMessage("system", system(settings, ta, tools))) + merged
    }

    /**
     * The TA's messages in a row go back as the send_message calls they are (or, from before,
     * could have been), each with its result: the model sees itself sending separate messages,
     * the way it is asked to, instead of one text with blank lines. A single message stays a
     * plain reply. Ids come from the rows, so they stay the same from one request to the next.
     */
    private fun asSentMessages(list: List<Pair<Long, ApiMessage>>): List<ApiMessage> {
        fun ApiMessage.said() = role == "assistant" && toolCalls.isEmpty() && content.isNotBlank()
        val out = ArrayList<ApiMessage>(list.size)
        var i = 0
        while (i < list.size) {
            var j = i
            while (j < list.size && list[j].second.said()) j++
            if (j - i < 2) {
                out += list[i].second
                i++
                continue
            }
            val calls = list.subList(i, j).map { (id, m) ->
                ToolCall("send_$id", ToolSpecs.sendMessage.name, buildJsonObject { put("text", m.content) }.toString())
            }
            out += ApiMessage("assistant", "", calls)
            calls.forEach { out += ApiMessage("tool", ToolSpecs.SENT, toolCallId = it.id) }
            i = j
        }
        return out
    }

    /** Ids of the messages whose pictures go along: the newest, whole messages, up to [MAX_IMAGES]. */
    private fun attachedPictures(history: List<MessageEntity>): Set<Long> {
        val out = mutableSetOf<Long>()
        var budget = MAX_IMAGES
        for (m in history.asReversed()) {
            if (m.role != "user" || m.note != null) continue
            val n = MessageImages.decode(m.images).size
            if (n == 0) continue
            if (n > budget) break
            out += m.id
            budget -= n
        }
        return out
    }

    /**
     * A person's message as the model reads it. Pictures are named by id (#45-1: message
     * 45, first picture), so the model can point at one, e.g. to use it as its avatar.
     */
    private fun MessageEntity.userText(canSee: Boolean, attached: Boolean): String {
        val count = MessageImages.decode(images).size
        if (count == 0) return content
        val ids = (1..count).joinToString(" ") { "#$id-$it" }
        val line = when {
            attached -> "（附图 $ids）"
            !canSee -> "（发了 $count 张图 $ids，你这边看不到图片）"
            else -> "（早先发的 $count 张图 $ids，这里没再附上）"
        }
        return if (content.isBlank()) line else "$line\n$content"
    }

    private fun MessageEntity.toApi(withTools: Boolean, canSee: Boolean, attached: Set<Long>): ApiMessage? = when (role) {
        "user" -> {
            val attach = id in attached
            userText(canSee, attach).takeIf { it.isNotBlank() }?.let { text ->
                ApiMessage("user", text, images = if (attach) MessageImages.decode(images).map { it.file } else emptyList())
            }
        }
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
        // "note" lines and "request" cards are for the person reading the chat, not for the model.
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
