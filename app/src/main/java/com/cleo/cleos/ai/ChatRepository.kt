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
        val history = db.messages().newest(conversationId, s.historySize).reversed()
        val now = ZonedDateTime.now()
        var groups = if (endpointKey in refusesTools) emptySet() else s.tools
        var withImages = endpointKey !in refusesImages && history.any { it.role == "user" && it.images != null }
        var messages = prepare(Prompt.messages(s, ta, history, now, groups, withImages))
        var rounds = 0
        // What the last refusal made this reply leave out, and when.
        var leftOut: LeftOut? = null
        try {
            while (true) {
                val mayRefuse = rounds == 0 && (groups.isNotEmpty() || withImages)
                when (val step = step(conversationId, endpoint, messages, tools.specs(groups), mayRefuse)) {
                    is Step.Ended -> {
                        val out = leftOut
                        // Only now is it clear what the model couldn't take: without it, it worked.
                        if (out != null && step.ok) {
                            if (out.images) refusesImages += endpointKey else refusesTools += endpointKey
                            note(conversationId, if (out.images) IMAGES_REFUSED else TOOLS_REFUSED, at = out.at - 1)
                        }
                        return
                    }
                    Step.Refused -> {
                        // Pictures go first: many more models take tools than take pictures.
                        leftOut = LeftOut(images = withImages, at = System.currentTimeMillis())
                        if (withImages) withImages = false else groups = emptySet()
                        messages = prepare(Prompt.messages(s, ta, history, now, groups, withImages))
                    }
                    is Step.Called -> {
                        if (rounds == MAX_TOOL_ROUNDS) {
                            note(conversationId, TOO_MANY_ROUNDS)
                            hide(conversationId)
                            return
                        }
                        messages = messages + step.message + runTools(conversationId, step, s, ta.id)
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

    private class LeftOut(val images: Boolean, val at: Long)

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

        class Called(val message: ApiMessage, val savedId: Long) : Step
    }

    /** One request: streams it to the screen, stores what came back, says what's next. */
    private suspend fun step(
        conversationId: Long,
        endpoint: ApiEndpoint,
        messages: List<ApiMessage>,
        specs: List<ToolSpec>,
        mayRefuse: Boolean,
    ): Step {
        val startedAt = System.currentTimeMillis()
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var calls = emptyList<ToolCall>()
        var error: String? = null
        var status: Int? = null
        show(StreamingReply(conversationId, "", thinking = false))
        try {
            client.stream(endpoint, messages, specs).collect { event ->
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
        // A model that can't take tools or pictures turns the first request down before
        // writing a word. The caller asks again without them.
        if (error != null && mayRefuse && text.isEmpty() && status in REFUSED_STATUSES) return Step.Refused

        return withContext(NonCancellable) {
            if (error == null && calls.isNotEmpty()) {
                val body = text.toString()
                val thought = reasoning.toString().ifEmpty { null }
                val id = db.messages().insert(
                    MessageEntity(
                        conversationId = conversationId,
                        role = "assistant",
                        content = body,
                        createdAt = startedAt,
                        toolCalls = ToolCallCodec.encode(calls),
                        reasoning = thought,
                    ),
                )
                db.conversations().touch(conversationId, System.currentTimeMillis())
                Step.Called(ApiMessage("assistant", body, calls, reasoning = thought), savedId = id)
            } else {
                finish(conversationId, startedAt, text.toString(), error, keepEmpty = true)
                Step.Ended(ok = error == null)
            }
        }
    }

    /** Runs the calls in order, storing each result with its line for the chat. */
    private suspend fun runTools(conversationId: Long, step: Step.Called, s: AppSettings, companionId: Long): List<ApiMessage> {
        val said = step.message.content
        val results = ArrayList<ApiMessage>(step.message.toolCalls.size)
        for (call in step.message.toolCalls) {
            // What was said before the calls stays up; the screen switches to the stored
            // copy (savedId) as soon as it is in the list.
            show(
                StreamingReply(
                    conversationId,
                    said,
                    thinking = false,
                    savedId = step.savedId.takeIf { said.isNotEmpty() },
                    activity = tools.activity(call.name),
                ),
            )
            val outcome = try {
                tools.run(call, s, conversationId, companionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolOutcome("工具出错了：${e.message ?: e.javaClass.simpleName}", "${tools.action(call.name)}出错了")
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
            results += ApiMessage("tool", outcome.result, toolCallId = call.id)
        }
        return results
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

        /**
         * How endpoints turn down tools or pictures a model can't take: 400 (SiliconFlow,
         * vLLM), 404 (OpenRouter finds no endpoint for it), 422 (strict validators).
         */
        private val REFUSED_STATUSES = setOf(400, 404, 422)
        private const val TOOLS_REFUSED = "这个模型不接受工具调用，这次没带工具。想让 TA 记待办、查天气，换一个支持工具的模型。"
        private const val IMAGES_REFUSED = "这个模型看不了图片，这次只发了文字。想让 TA 看图，换一个能看图的模型。"
        private const val TOO_MANY_ROUNDS = "连着用了太多次工具，先停在这里。"
    }
}
