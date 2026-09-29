package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.MessageQuote
import com.cleo.cleos.data.MessageQuotes
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.cleo.cleos.data.db.MemoryEntity
import java.time.ZoneId
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
     * When to note something down (ai/Later.kt). A tool's description only says how to use it;
     * without a rule saying when, a model either never does or does it for everything. The
     * things it must not note are the ones that would make reaching out a demand.
     */
    private const val LATER_RULE =
        "你可以用 note_for_later 给自己记一笔：一件过一阵想跟对方说或问的事，和大概多久以后再想起来。" +
            "对方说要去做什么（做饭、考试、看病、出门、睡觉），有件事要等结果，或者你自己有句话想晚点再说，就记下来；" +
            "到时候你会再看到这一笔和这之间聊的，再决定说不说。只记具体的事；" +
            "别记「问问在干嘛」「看看回没回我」这种，对方正和你聊着的事也不用记。"

    /**
     * Pictures sent along with a request, the most recent first. Every picture goes out
     * again with every turn while it is included, so only the last few are; older ones
     * are named in the text but not attached.
     */
    const val MAX_IMAGES = 4

    /** How much of a quoted message the model is shown: enough to know which one it is. */
    private const val QUOTE_SHOWN = 100

    /**
     * What changes as the conversation goes on comes last: what the TA remembers, which
     * changes whenever they remember something, then the [recap], which changes every so many
     * messages. Everything before stays the same, so the cached prefix survives both.
     */
    fun system(
        settings: AppSettings,
        ta: CompanionEntity,
        tools: Set<ToolGroup> = emptySet(),
        memories: List<MemoryEntity> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
        recap: String? = null,
        outside: List<McpTool> = emptyList(),
    ): String = buildList {
        if (ta.name.isNotBlank()) add("你叫${ta.name.trim()}。")
        if (settings.userName.isNotBlank()) add("和你说话的人叫${settings.userName.trim()}。")
        if (ta.persona.isNotBlank()) add(ta.persona.trim())
        toolRule(tools, outside)?.let(::add)
        add(FORMAT_RULE)
        if (ToolGroup.Memory in tools) MemoryDigest.forChat(memories, zone)?.let(::add)
        Recap.forChat(recap)?.let(::add)
    }.joinToString("\n\n")

    /**
     * Offering tools without saying when to use them fails in two ways: the model says
     * "记好啦" without calling anything, or it goes through the diary unasked. Only the
     * rules for the tools actually offered are included.
     */
    private fun toolRule(tools: Set<ToolGroup>, outside: List<McpTool> = emptyList()): String? = buildList {
        if (ToolGroup.Messages in tools) {
            add("想分成几条消息说的时候，用 send_message 一条一条发：一条只说一件事，要发几条就在同一次回复里调用几次。只说一句就直接回复。用 send_message 发过的话，别再在回复里写一遍，也别说「发好了」。")
            add("对方连着发了几条、你想一条条回的时候，send_message 可以带 quote（照抄你在回的那句里的几个字），对方就知道这条回的是哪句；回到前面说过的某句时也可以。平常一问一答不要引用。")
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
        if (ToolGroup.Memory in tools) add(MemoryDigest.RULES)
        if (ToolGroup.Letters in tools) {
            add("你们之间也写信（在 App 的信箱里）。对方提到信的时候，用 read_letters 看了再说；别在聊天里整段复述信。")
        }
        if (ToolGroup.Avatar in tools) add("你可以用 set_my_avatar 换自己的头像：用对方发来的一张图，或者一个表情。")
        if (ToolGroup.Weather in tools) add("问到天气时用工具查，不要凭印象说。")
        if (ToolGroup.Speak in tools) {
            add("你能用 send_voice 发语音条，对方听到的是你的声音。想用声音说的时候再用（道晚安、撒娇、情绪浓的时候），一条一两句话，写成说出口的样子；平常还是打字。发了语音条就别再把同样的话打一遍。")
        }
        if (ToolGroup.Location in tools) {
            add("你能用 get_location 查对方现在在哪。需要的时候再查：对方问附近、问路，或者问天气没说城市；别无缘无故去查，也别把坐标念给对方。")
        }
        if (ToolGroup.Later in tools) add(LATER_RULE)
        if (ToolGroup.Alarm in tools) {
            add("对方让你定闹钟、叫醒、计时的时候，用 set_alarm 或 set_timer 在对方手机的时钟里定，定好了再说；没调用就别说定好了。对方没让，别自己给对方定闹钟。")
        }
        if (ToolGroup.Calendar in tools) {
            add(
                "对方问起安排、行程、哪天有没有空，先用 read_calendar 看了再说。对方让你记下某个安排、到时候提醒，用 add_event 加进日历，要提醒就带 remind。" +
                    "对方自己的日程你只能看；改和删只限你加的。",
            )
        }
        // A reminder the person asks for has to go off; a TA's own note may be stretched or dropped by the phone.
        if (ToolGroup.Later in tools && (ToolGroup.Alarm in tools || ToolGroup.Calendar in tools)) {
            add("对方要你到点提醒的事，用闹钟或日历，那样手机到点一定会响；note_for_later 是你自己想过一阵再说的话，不用来替对方办提醒。")
        }
        if (outside.isNotEmpty()) {
            val names = outside.map { it.serverName }.distinct().joinToString("、")
            add(
                "你还接了外部服务：$names，它们的工具名以 mcp_ 开头。对方要做和它们有关的事时再用。" +
                    "下单、叫车、付款这类真花钱的，先在聊天里把要什么、多少、送到哪跟对方说清楚，对方同意了再调用；" +
                    "结果照实说，没成就说没成，别编。",
            )
        }
    }.takeIf { it.isNotEmpty() }?.joinToString("")

    /**
     * [history] oldest first, already trimmed to the window. With [tools] empty, tool
     * calls and their results are left out and only what was said remains, so a model
     * without tool support can read a conversation that used them. With [images] false
     * no picture is attached (the text still says one was sent). [due]: what the TA noted that
     * has come due, said beside the time (LaterRules.dueLine).
     */
    fun messages(
        settings: AppSettings,
        ta: CompanionEntity,
        history: List<MessageEntity>,
        now: ZonedDateTime,
        tools: Set<ToolGroup> = emptySet(),
        images: Boolean = false,
        memories: List<MemoryEntity> = emptyList(),
        recap: String? = null,
        outside: List<McpTool> = emptyList(),
        due: List<String> = emptyList(),
    ): List<ApiMessage> {
        val withTools = tools.isNotEmpty() || outside.isNotEmpty()
        val attached = if (images) attachedPictures(history) else emptySet()
        val converted = history.mapNotNull { m -> m.toApi(withTools, images, attached)?.let { m.id to it } }
        val sendable = asSentMessages(converted, texts = ToolGroup.Messages in tools, voices = ToolGroup.Speak in tools)
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
            val noted = LaterRules.dueLine(due)?.let { "$it\n" }.orEmpty()
            merged[lastUser] = merged[lastUser].let { it.copy(content = "（${timeLine(now)}）\n$noted${it.content}") }
        }
        return listOf(ApiMessage("system", system(settings, ta, tools, memories, now.zone, recap, outside))) + merged
    }

    /**
     * [messages] with a wake's [text] as the last turn (ai/Later.kt): on the person's side, the
     * only place endpoints take something new, and it says itself that the person never sees it.
     * After a turn of theirs (a reply that failed) the two are joined, since some endpoints turn
     * down two user turns in a row. Every earlier turn's reasoning goes: it is sent back only
     * within the turn still under way, and the wake starts a new one.
     */
    fun withWake(messages: List<ApiMessage>, text: String): List<ApiMessage> {
        val cleared = messages.map { if (it.reasoning != null) it.copy(reasoning = null) else it }
        val last = cleared.last()
        return if (last.role == "user") cleared.dropLast(1) + last.copy(content = last.content + "\n\n" + text)
        else cleared + ApiMessage("user", text)
    }

    /**
     * With [texts] (send_message offered), the TA's messages in a row go back as the
     * send_message calls they are (or, from before, could have been), each with its result:
     * the model sees itself sending separate messages, the way it is asked to, instead of one
     * text with blank lines. A single message stays a plain reply. With [voices] (send_voice
     * offered), a voice message goes back as the send_voice call it was, even on its own, so
     * the model knows it spoke; otherwise as a line marked （语音）, never as typed words it
     * might copy. Ids come from the rows, so they stay the same from one request to the next.
     */
    private fun asSentMessages(list: List<Pair<Long, ApiMessage>>, texts: Boolean, voices: Boolean): List<ApiMessage> {
        fun ApiMessage.said() = role == "assistant" && toolCalls.isEmpty() && content.isNotBlank() && (if (spoken) voices else texts)
        val out = ArrayList<ApiMessage>(list.size)
        var i = 0
        while (i < list.size) {
            var j = i
            while (j < list.size && list[j].second.said()) j++
            if (j == i || (j - i == 1 && !list[i].second.spoken && list[i].second.quoted == null)) {
                val m = list[i].second
                out += if (m.spoken) m.copy(content = "（语音）${m.content}") else m
                i++
                continue
            }
            val calls = list.subList(i, j).map { (id, m) ->
                val name = if (m.spoken) ToolSpecs.sendVoice.name else ToolSpecs.sendMessage.name
                val args = buildJsonObject {
                    put("text", m.content)
                    m.quoted?.let { put("quote", it.replace('\n', ' ').take(QUOTE_SHOWN)) }
                }
                ToolCall("send_$id", name, args.toString())
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
        // A voice message goes as what it said; one not turned into text says nothing yet.
        val said = if (audio != null && content.isNotBlank()) "（语音）$content" else content
        val count = MessageImages.decode(images).size
        val body = if (count == 0) {
            said
        } else {
            val ids = (1..count).joinToString(" ") { "#$id-$it" }
            val line = when {
                attached -> "（附图 $ids）"
                !canSee -> "（发了 $count 张图 $ids，你这边看不到图片）"
                else -> "（早先发的 $count 张图 $ids，这里没再附上）"
            }
            if (said.isBlank()) line else "$line\n$said"
        }
        // The message it answers goes first, when it answers one (and says something itself).
        val quote = MessageQuotes.decode(quote)?.takeIf { body.isNotBlank() } ?: return body
        return quoteLine(quote) + "\n" + body
    }

    /** How the model is told which message the person is answering. */
    private fun quoteLine(q: MessageQuote): String {
        val words = q.text.replace('\n', ' ').take(QUOTE_SHOWN)
        return if (q.role == "assistant") "（回复你说的：「$words」）" else "（接着自己说的：「$words」）"
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
                content.isNotBlank() -> ApiMessage("assistant", content, spoken = audio != null, quoted = MessageQuotes.decode(quote)?.text)
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
