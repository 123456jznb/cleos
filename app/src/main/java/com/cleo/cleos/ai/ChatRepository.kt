package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.Companions
import com.cleo.cleos.data.ImageStore
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.SecretStore
import com.cleo.cleos.data.SettingsRepository
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.ConversationEntity
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/** The reply being written right now. */
data class StreamingReply(
    val conversationId: Long,
    val text: String,
    val thinking: Boolean,
    /** Set once [text] has been stored; the screen then shows the stored copy instead. */
    val savedId: Long? = null,
    /** A tool running between two parts of the reply: 在查天气. */
    val activity: String? = null,
    /** The reply is over; this is only the hand-over to the stored bubble. */
    val finished: Boolean = false,
    /** A call to an outside service waiting for the person to allow it. */
    val asking: McpAsk? = null,
)

/**
 * Sending and receiving. Replies run in the app-wide scope, not a screen's: switching
 * to the diary mid-reply should not cut the reply off.
 *
 * One reply at a time per conversation, but conversations don't wait for each other: the
 * person can go and talk to another TA while the first one is still answering.
 *
 * A reply with tools is a loop: the model answers with text, tool calls or both; the
 * calls run, their results go back, and the model continues, until it answers without
 * calling anything. Each step is stored as it happens (the text, the calls, every
 * result), so what was done survives a crash or a stop, and a retry continues from the
 * results instead of adding the same todo twice.
 */
class ChatRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val client: ChatClient,
    private val tools: ToolBox,
    private val images: ImageStore,
    private val companions: Companions,
    private val recaps: Recaps,
    private val mcp: McpHub,
    private val scope: CoroutineScope,
) {
    /** The replies being written, by conversation. */
    private val _streaming = MutableStateFlow<Map<Long, StreamingReply>>(emptyMap())
    val streaming: StateFlow<Map<Long, StreamingReply>> = _streaming.asStateFlow()
    private val jobs = ConcurrentHashMap<Long, Job>()

    /**
     * Endpoint and model pairs that turned down a request with tools, in this run of the
     * app. They are asked without tools from then on instead of failing every message.
     */
    private val refusesTools = ConcurrentHashMap.newKeySet<String>()

    /** The same for pictures: models that can't look at images are sent the text only. */
    private val refusesImages = ConcurrentHashMap.newKeySet<String>()

    /** And for the thinking switch: models that don't take it are asked the plain way. */
    private val refusesThinking = ConcurrentHashMap.newKeySet<String>()

    /** The person's answer to a card asking whether the TA may use an outside service's tool. */
    enum class Answer { Yes, Always, No }

    /** Calls waiting on the person, by conversation. */
    private val asks = ConcurrentHashMap<Long, CompletableDeferred<Answer>>()

    /** Whether a reply is under way in [conversationId], including while its tools run. */
    fun busy(conversationId: Long): Boolean = jobs[conversationId]?.isActive == true

    /** Runs [block] as [conversationId]'s reply; false, and nothing runs, while one is under way there. */
    private fun start(conversationId: Long, block: suspend () -> Unit): Boolean {
        if (busy(conversationId)) return false
        // Registered before it starts, so a quick reply can't finish before it is on the map.
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        jobs[conversationId] = job
        job.invokeOnCompletion { jobs.remove(conversationId, job) }
        job.start()
        return true
    }

    private fun show(reply: StreamingReply) = _streaming.update { it + (reply.conversationId to reply) }

    private fun hide(conversationId: Long) = _streaming.update { it - conversationId }

    suspend fun newConversation(companionId: Long): Long {
        val now = System.currentTimeMillis()
        return db.conversations().insert(
            ConversationEntity(title = DEFAULT_TITLE, createdAt = now, updatedAt = now, companionId = companionId),
        )
    }

    /**
     * The conversation to show with TA [companionId]: the remembered one if it is still
     * there and theirs, else their latest, else a new one.
     */
    suspend fun resolveConversation(remembered: Long?, companionId: Long): Long {
        if (remembered != null && db.conversations().get(remembered)?.companionId == companionId) return remembered
        return db.conversations().latestFor(companionId)?.id ?: newConversation(companionId)
    }

    /** The TA a conversation is with (the first one if the conversation is gone). */
    private suspend fun taOf(conversationId: Long) =
        db.conversations().get(conversationId)?.companionId?.let { companions.get(it) } ?: companions.current()

    /** False when nothing was sent (a reply is still under way here): the text stays in the box. */
    fun send(conversationId: Long, text: String, pictures: List<MessageImage> = emptyList()): Boolean {
        val content = text.trim()
        if (content.isEmpty() && pictures.isEmpty()) return false
        return start(conversationId) {
            val now = System.currentTimeMillis()
            db.messages().insert(
                MessageEntity(
                    conversationId = conversationId,
                    role = "user",
                    content = content,
                    createdAt = now,
                    images = MessageImages.encode(pictures),
                ),
            )
            val conversation = db.conversations().get(conversationId)
            if (conversation != null && conversation.title == DEFAULT_TITLE) {
                db.conversations().rename(conversationId, content.lineSequence().first().take(24).ifBlank { "[图片]" })
            }
            db.conversations().touch(conversationId, now)
            reply(conversationId)
        }
    }

    /** Throw away [assistantMessageId] (a failed or unwanted reply) and ask again. */
    fun retry(conversationId: Long, assistantMessageId: Long) {
        start(conversationId) {
            db.messages().delete(assistantMessageId)
            reply(conversationId)
        }
    }

    fun stop(conversationId: Long) {
        jobs[conversationId]?.cancel()
    }

    /**
     * Stops the replies in these conversations and waits until they have let go. A reply
     * still writing into a conversation that is being deleted would fail on the missing
     * row, so this comes first.
     */
    suspend fun stopReplies(conversationIds: Collection<Long>) {
        conversationIds.mapNotNull { jobs[it] }.forEach { it.cancelAndJoin() }
    }

    /** Before one TA goes, with every conversation they had. */
    suspend fun stopRepliesOf(companionId: Long) = stopReplies(db.conversations().idsFor(companionId))

    /** Before a restore replaces every conversation. */
    suspend fun stopAll() = stopReplies(jobs.keys.toList())

    /** The row and the pictures sent with it: nothing else points at those files. */
    fun deleteMessage(id: Long) {
        scope.launch {
            val pictures = db.messages().get(id)?.images
            db.messages().delete(id)
            images.delete(MessageImages.decode(pictures).map { it.file })
        }
    }

    /** A conversation and its pictures: the rows go by cascade, the files would stay behind. */
    fun deleteConversation(id: Long) {
        scope.launch {
            stopReplies(listOf(id))
            val pictures = db.messages().imagesIn(id).flatMap { MessageImages.decode(it) }.map { it.file }
            db.conversations().delete(id)
            images.delete(pictures)
        }
    }

    /**
     * The person's answer to a request card: show that secret this once, or don't. Either
     * way it is their turn in the conversation, so the model answers it.
     */
    fun answerSecretRequest(conversationId: Long, requestMessageId: Long, grant: Boolean) {
        start(conversationId) answer@{
            val row = db.messages().get(requestMessageId) ?: return@answer
            val request = SecretRequests.decode(row.content)?.takeIf { it.status == SecretRequest.PENDING } ?: return@answer
            val ai = taOf(conversationId).name.trim().ifEmpty { "TA" }
            val day = LocalDate.ofEpochDay(request.day)
            val entry = db.diary().get(request.diaryId)
            if (grant && entry == null) {
                db.messages().setContent(row.id, SecretRequests.encode(request.copy(status = SecretRequest.GONE)))
                note(conversationId, "这个小秘密已经删掉了，没法给${ai}看")
                return@answer
            }
            val status = if (grant) SecretRequest.GRANTED else SecretRequest.DECLINED
            db.messages().setContent(row.id, SecretRequests.encode(request.copy(status = status)))
            val now = System.currentTimeMillis()
            val today = LocalDate.now()
            db.messages().insert(
                MessageEntity(
                    conversationId = conversationId,
                    role = "user",
                    content = if (entry != null && grant) SecretRequests.shared(entry, today) else SecretRequests.declined(day, today),
                    createdAt = now,
                    note = (if (grant) "给${ai}看了" else "没给${ai}看") + "${Describe.monthDay(day)}的小秘密",
                ),
            )
            db.conversations().touch(conversationId, now)
            reply(conversationId)
        }
    }

    private suspend fun reply(conversationId: Long) {
        val s = settings.current()
        val ta = taOf(conversationId)
        val key = secrets.key(ta.apiBaseUrl)
        if (key.isNullOrBlank()) {
            db.messages().insert(
                MessageEntity(
                    conversationId = conversationId,
                    role = "assistant",
                    content = "",
                    createdAt = System.currentTimeMillis(),
                    error = "还没有填 API Key。去设置里填上，就能聊了。",
                ),
            )
            return
        }
        val endpoint = ApiEndpoint(ta.apiBaseUrl, key, ta.apiModel)
        val endpointKey = endpoint.chatUrl + "|" + endpoint.model
        // What isn't folded into the recap yet, as much of it as the window takes. The recap
        // stands in for everything before.
        val conversation = db.conversations().get(conversationId)
        val history = if (conversation == null) emptyList() else Recap.sent(recaps.live(conversation), s.historySize)
        val recap = conversation?.recap
        val now = ZonedDateTime.now()
        var groups = if (endpointKey in refusesTools) emptySet() else s.tools
        // The tools of the MCP services switched on come along whenever tools do.
        var outside = if (endpointKey in refusesTools) emptyList() else mcp.tools()
        var withImages = endpointKey !in refusesImages && history.any { it.role == "user" && it.images != null }
        var thinking = ta.deepThinking && endpointKey !in refusesThinking
        // What the TA remembers, read once for this reply.
        val memories = if (ToolGroup.Memory in s.tools) db.memories().allFor(ta.id) else emptyList()
        var messages = prepare(Prompt.messages(s, ta, history, now, groups, withImages, memories, recap, outside))
        var rounds = 0
        // What the last refusal made this reply leave out, and when.
        var leftOut: LeftOut? = null
        try {
            while (true) {
                val mayRefuse = rounds == 0 && (groups.isNotEmpty() || outside.isNotEmpty() || withImages || thinking)
                val specs = tools.specs(groups) + outside.map { it.spec }
                when (val step = step(conversationId, endpoint, messages, specs, mayRefuse, thinking)) {
                    is Step.Ended -> {
                        if (step.ok) {
                            remember(leftOut, conversationId, endpointKey)
                            recaps.foldLater(conversationId)
                        }
                        return
                    }
                    Step.Refused -> {
                        // The thinking switch goes first: it was asked for on top of the rest. Then
                        // pictures: many more models take tools than take pictures.
                        val what = when {
                            thinking -> Left.Thinking
                            withImages -> Left.Images
                            else -> Left.Tools
                        }
                        leftOut = LeftOut(what, at = System.currentTimeMillis())
                        when (what) {
                            Left.Thinking -> thinking = false
                            Left.Images -> withImages = false
                            Left.Tools -> {
                                groups = emptySet()
                                outside = emptyList()
                            }
                        }
                        messages = prepare(Prompt.messages(s, ta, history, now, groups, withImages, memories, recap, outside))
                    }
                    is Step.Called -> {
                        if (rounds == MAX_TOOL_ROUNDS) {
                            note(conversationId, TOO_MANY_ROUNDS)
                            hide(conversationId)
                            return
                        }
                        val results = runTools(conversationId, step, s, ta.id, outside, ta.name)
                        // Only messages sent: that was the whole reply. Asking again would bring
                        // nothing new, or a "发好了".
                        if (step.message.toolCalls.all { it.name == ToolSpecs.sendMessage.name }) {
                            remember(leftOut, conversationId, endpointKey)
                            recaps.foldLater(conversationId)
                            hide(conversationId)
                            return
                        }
                        messages = messages + step.message + results
                        rounds++
                    }
                }
            }
        } catch (e: CancellationException) {
            // Stopped while a tool ran: no stream is open to clear the live row on its way out.
            if (_streaming.value[conversationId]?.finished != true) hide(conversationId)
            throw e
        }
    }

    private enum class Left { Thinking, Images, Tools }

    private class LeftOut(val what: Left, val at: Long)

    /**
     * Once a reply got through without what a refusal made it leave out, that is what the
     * model can't take: it is left out from now on, and the chat says so.
     */
    private suspend fun remember(out: LeftOut?, conversationId: Long, endpointKey: String) {
        if (out == null) return
        val line = when (out.what) {
            Left.Thinking -> THINKING_REFUSED.also { refusesThinking += endpointKey }
            Left.Images -> IMAGES_REFUSED.also { refusesImages += endpointKey }
            Left.Tools -> TOOLS_REFUSED.also { refusesTools += endpointKey }
        }
        note(conversationId, line, at = out.at - 1)
    }

    /** Pictures become data: URLs just before sending; one that can't be read is left out. */
    private suspend fun prepare(messages: List<ApiMessage>): List<ApiMessage> = messages.map { m ->
        if (m.images.isEmpty()) m else m.copy(images = m.images.mapNotNull { images.dataUrl(it) })
    }

    private sealed interface Step {
        class Ended(val ok: Boolean) : Step

        /**
         * The first request failed the way requests fail on a model that can't take what
         * was in them: tools, or pictures.
         */
        data object Refused : Step

        /** [savedId]: the row the text said before the calls went into, if there was any to store. */
        class Called(val message: ApiMessage, val savedId: Long?) : Step
    }

    /** One request: streams it to the screen, stores what came back, says what's next. */
    private suspend fun step(
        conversationId: Long,
        endpoint: ApiEndpoint,
        messages: List<ApiMessage>,
        specs: List<ToolSpec>,
        mayRefuse: Boolean,
        thinking: Boolean,
    ): Step {
        val startedAt = System.currentTimeMillis()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var calls = emptyList<ToolCall>()
        var error: String? = null
        var status: Int? = null
        show(StreamingReply(conversationId, "", thinking = false))
        try {
            client.stream(endpoint, messages, specs, thinking).collect { event ->
                when (event) {
                    is ChatEvent.Delta -> {
                        text.append(event.text)
                        show(StreamingReply(conversationId, text.toString(), thinking = false))
                    }
                    is ChatEvent.Reasoning -> {
                        reasoning.append(event.text)
                        if (text.isEmpty()) show(StreamingReply(conversationId, "", thinking = true))
                    }
                    is ChatEvent.ToolCalls -> calls = event.calls
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { finish(conversationId, startedAt, text.toString(), STOPPED, keepEmpty = false) }
            throw e
        } catch (e: ChatException) {
            error = e.message
            status = e.status
        } catch (e: Exception) {
            error = "出错了：${e.message ?: e.javaClass.simpleName}"
        }
        // A model that can't take tools, pictures or the thinking switch turns the first
        // request down before writing a word. The caller asks again without them.
        if (error != null && mayRefuse && text.isEmpty() && status in REFUSED_STATUSES) return Step.Refused

        return withContext(NonCancellable) {
            if (error == null && calls.isNotEmpty()) {
                val body = text.toString()
                val thought = reasoning.toString().ifEmpty { null }
                // Sent messages are stored as bubbles of their own, not as calls (Prompt turns
                // bubbles in a row back into calls). With some sent, the words said first go in
                // now as a bubble too, and the other calls only after the messages (runTools):
                // a call has to stay right before its results.
                val speaks = calls.any { it.name == ToolSpecs.sendMessage.name }
                val id = when {
                    !speaks -> db.messages().insert(
                        MessageEntity(
                            conversationId = conversationId,
                            role = "assistant",
                            content = body,
                            createdAt = startedAt,
                            toolCalls = ToolCallCodec.encode(calls),
                            reasoning = thought,
                        ),
                    )
                    body.isBlank() -> null
                    else -> db.messages().insert(
                        MessageEntity(conversationId = conversationId, role = "assistant", content = body, createdAt = startedAt),
                    )
                }
                db.conversations().touch(conversationId, System.currentTimeMillis())
                Step.Called(ApiMessage("assistant", body, calls, reasoning = thought), savedId = id)
            } else {
                finish(conversationId, startedAt, text.toString(), error, keepEmpty = true)
                Step.Ended(ok = error == null)
            }
        }
    }

    /**
     * Runs the calls, storing each result with its line for the chat. Sent messages come
     * first: each is the TA speaking and becomes a bubble, a moment after the one before, the
     * way messages arrive when someone types them one by one. Then the other calls, in order.
     * The results go back in the order of the calls.
     */
    private suspend fun runTools(
        conversationId: Long,
        step: Step.Called,
        s: AppSettings,
        companionId: Long,
        outside: List<McpTool>,
        ai: String,
    ): List<ApiMessage> {
        val said = step.message.content
        val (sends, others) = step.message.toolCalls.partition { it.name == ToolSpecs.sendMessage.name }
        val results = HashMap<String, ApiMessage>()
        var previous = said.takeIf { it.isNotBlank() }
        var sent = 0
        for (call in sends) {
            val words = ToolArgs.parse(call.arguments)?.let { ToolArgs.text(it, "text") }?.trim().orEmpty()
            val result = when {
                words.isEmpty() -> "没有内容，没发出去。"
                sent >= MAX_MESSAGES -> "一次最多发 $MAX_MESSAGES 条，这条没发出去。"
                else -> {
                    previous?.let {
                        // Typing the next one: the dots, for a moment that grows a little with what was just said.
                        show(StreamingReply(conversationId, said, thinking = false, savedId = step.savedId.takeIf { said.isNotEmpty() }))
                        delay((400L + it.length * 25L).coerceAtMost(1500L))
                    }
                    withContext(NonCancellable) {
                        val at = System.currentTimeMillis()
                        db.messages().insert(MessageEntity(conversationId = conversationId, role = "assistant", content = words, createdAt = at))
                        db.conversations().touch(conversationId, at)
                    }
                    previous = words
                    sent++
                    ToolSpecs.SENT
                }
            }
            results[call.id] = ApiMessage("tool", result, toolCallId = call.id)
        }
        if (sends.isNotEmpty() && others.isNotEmpty()) {
            // Held back in step(): stored now, after the messages and right before the results.
            withContext(NonCancellable) {
                db.messages().insert(
                    MessageEntity(
                        conversationId = conversationId,
                        role = "assistant",
                        content = "",
                        createdAt = System.currentTimeMillis(),
                        toolCalls = ToolCallCodec.encode(others),
                        reasoning = step.message.reasoning,
                    ),
                )
            }
        }
        for (call in others) {
            val outer = outside.firstOrNull { it.fnName == call.name }
            // What was said before the calls stays up; the screen switches to the stored
            // copy (savedId) as soon as it is in the list.
            val live = StreamingReply(
                conversationId,
                said,
                thinking = false,
                savedId = step.savedId.takeIf { said.isNotEmpty() },
                activity = outer?.let { "在用${it.serverName}" } ?: tools.activity(call.name),
            )
            show(live)
            val outcome = try {
                if (outer != null) runOutside(conversationId, outer, call, live, ai) else tools.run(call, s, conversationId, companionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolOutcome("工具出错了：${e.message ?: e.javaClass.simpleName}", "${outer?.spec?.action ?: tools.action(call.name)}出错了")
            }
            // The tool has acted (a todo exists now), so its result is stored even if
            // stop was pressed meanwhile: the history should match what happened.
            withContext(NonCancellable) {
                val at = System.currentTimeMillis()
                db.messages().insert(
                    MessageEntity(
                        conversationId = conversationId,
                        role = "tool",
                        content = outcome.result,
                        createdAt = at,
                        toolCallId = call.id,
                        note = outcome.note,
                    ),
                )
                outcome.request?.let {
                    db.messages().insert(
                        MessageEntity(conversationId = conversationId, role = "request", content = SecretRequests.encode(it), createdAt = at),
                    )
                }
            }
            results[call.id] = ApiMessage("tool", outcome.result, toolCallId = call.id)
        }
        return step.message.toolCalls.mapNotNull { results[it.id] }
    }

    /**
     * A call to an MCP service's tool. Unless the tool only reads, or the person said to always
     * allow it, they are asked first, on a card in the chat: a service can take orders and
     * payments, and a TA must not do that on its own.
     */
    private suspend fun runOutside(conversationId: Long, tool: McpTool, call: ToolCall, live: StreamingReply, ai: String): ToolOutcome {
        val what = "${tool.serverName}的${tool.title}"
        val server = mcp.servers.get(tool.serverId)?.takeIf { it.enabled }
            ?: return ToolOutcome("这个服务在设置里关掉了，现在用不了。", "用${what}没成：设置里关掉了")
        val args = ToolArgs.parse(call.arguments)
            ?: return ToolOutcome("参数不是合法的 JSON 对象，按参数说明重新调用。", "用${what}没成：参数写错了")
        if (server.askFirst && !tool.readOnly && tool.name !in server.allowed) {
            when (ask(conversationId, live, McpAsk(tool.serverName, tool.title, Mcp.preview(args)))) {
                Answer.No -> return ToolOutcome(
                    "对方没有同意，这次没有调用。需要的话在聊天里问问对方。",
                    "没让${ai.ifBlank { "TA" }}用$what",
                )
                Answer.Always -> mcp.servers.allow(server.id, tool.name)
                Answer.Yes -> Unit
            }
            show(live)
        }
        return try {
            ToolOutcome(mcp.call(server, tool, args), "用了$what")
        } catch (e: McpException) {
            ToolOutcome("没有调用成功：${e.message}。照实告诉对方没成，别编结果。", "用${what}没成：${e.message.orEmpty().lineSequence().first()}")
        }
    }

    /** Shows the card and waits for the person. No answer in [ASK_WAIT] counts as no. */
    private suspend fun ask(conversationId: Long, live: StreamingReply, question: McpAsk): Answer {
        val answer = CompletableDeferred<Answer>()
        asks[conversationId] = answer
        show(live.copy(activity = null, asking = question))
        return try {
            withTimeoutOrNull(ASK_WAIT) { answer.await() } ?: Answer.No
        } finally {
            asks.remove(conversationId, answer)
        }
    }

    /** The person answered the card in [conversationId]. */
    fun answer(conversationId: Long, answer: Answer) {
        asks[conversationId]?.complete(answer)
    }

    private suspend fun finish(conversationId: Long, startedAt: Long, body: String, error: String?, keepEmpty: Boolean) {
        if (body.isNotEmpty() || (keepEmpty && error != null)) {
            val id = db.messages().insert(
                MessageEntity(
                    conversationId = conversationId,
                    role = "assistant",
                    content = body,
                    createdAt = startedAt,
                    error = error,
                ),
            )
            db.conversations().touch(conversationId, System.currentTimeMillis())
            // Hand over from the live bubble to the stored one without a gap:
            // the screen hides the live bubble once it sees savedId in its list.
            // The cleanup runs on its own so this job (and `busy`) ends now.
            val handover = StreamingReply(conversationId, body, thinking = false, savedId = id, finished = true)
            show(handover)
            scope.launch {
                delay(1500)
                // Unless the next reply here has begun meanwhile.
                _streaming.update { if (it[conversationId] == handover) it - conversationId else it }
            }
        } else {
            hide(conversationId)
        }
    }

    private suspend fun note(conversationId: Long, text: String, at: Long = System.currentTimeMillis()) {
        withContext(NonCancellable) {
            db.messages().insert(MessageEntity(conversationId = conversationId, role = "note", content = "", createdAt = at, note = text))
        }
    }

    companion object {
        const val DEFAULT_TITLE = "新对话"
        const val STOPPED = "已停止"

        /** Round trips with tools in one reply before it is cut off, against a model stuck calling. */
        const val MAX_TOOL_ROUNDS = 5

        /** Messages sent in one go: past this it is a flood, not a conversation. */
        const val MAX_MESSAGES = 8

        /** How long a call to an outside service waits for the person to allow it. */
        private const val ASK_WAIT = 10 * 60_000L

        /**
         * How endpoints turn down tools or pictures a model can't take: 400 (SiliconFlow,
         * vLLM), 404 (OpenRouter finds no endpoint for it), 422 (strict validators).
         */
        private val REFUSED_STATUSES = setOf(400, 404, 422)
        private const val TOOLS_REFUSED = "这个模型不接受工具调用，这次没带工具。想让 TA 记待办、查天气，换一个支持工具的模型。"
        private const val IMAGES_REFUSED = "这个模型看不了图片，这次只发了文字。想让 TA 看图，换一个能看图的模型。"
        private const val THINKING_REFUSED = "这个模型不认深度思考的开关，这次照常回复了，之后也不再带这个开关。"
        private const val TOO_MANY_ROUNDS = "连着用了太多次工具，先停在这里。"
    }
}
