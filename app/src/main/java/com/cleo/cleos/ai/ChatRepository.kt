package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val scope: CoroutineScope,
) {
    private val _streaming = MutableStateFlow<StreamingReply?>(null)
    val streaming: StateFlow<StreamingReply?> = _streaming.asStateFlow()
    private var job: Job? = null

    /**
     * Endpoint and model pairs that turned down a request with tools, in this run of the
     * app. They are asked without tools from then on instead of failing every message.
     */
    private val refusesTools = ConcurrentHashMap.newKeySet<String>()

    /** The same for pictures: models that can't look at images are sent the text only. */
    private val refusesImages = ConcurrentHashMap.newKeySet<String>()

    val busy: Boolean get() = job?.isActive == true

    suspend fun newConversation(): Long {
        val now = System.currentTimeMillis()
        return db.conversations().insert(ConversationEntity(title = DEFAULT_TITLE, createdAt = now, updatedAt = now))
    }

    /** The conversation to show: the remembered one if it still exists, else the latest, else a new one. */
    suspend fun resolveConversation(remembered: Long?): Long {
        if (remembered != null && db.conversations().get(remembered) != null) return remembered
        return db.conversations().latest()?.id ?: newConversation()
    }

    fun send(conversationId: Long, text: String, pictures: List<MessageImage> = emptyList()) {
        if (busy) return
        val content = text.trim()
        if (content.isEmpty() && pictures.isEmpty()) return
        job = scope.launch {
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
        if (busy) return
        job = scope.launch {
            db.messages().delete(assistantMessageId)
            reply(conversationId)
        }
    }

    fun stop() {
        job?.cancel()
    }

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
        if (busy) return
        job = scope.launch {
            val row = db.messages().get(requestMessageId) ?: return@launch
            val request = SecretRequests.decode(row.content)?.takeIf { it.status == SecretRequest.PENDING } ?: return@launch
            val ai = settings.current().aiName.trim().ifEmpty { "TA" }
            val day = LocalDate.ofEpochDay(request.day)
            val entry = db.diary().get(request.diaryId)
            if (grant && entry == null) {
                db.messages().setContent(row.id, SecretRequests.encode(request.copy(status = SecretRequest.GONE)))
                note(conversationId, "这个小秘密已经删掉了，没法给${ai}看")
                return@launch
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
        val key = secrets.apiKey()
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
        val endpoint = ApiEndpoint(s.apiBaseUrl, key, s.apiModel)
        val endpointKey = endpoint.chatUrl + "|" + endpoint.model
        val history = db.messages().newest(conversationId, s.historySize).reversed()
        val now = ZonedDateTime.now()
        var groups = if (endpointKey in refusesTools) emptySet() else s.tools
        var withImages = endpointKey !in refusesImages && history.any { it.role == "user" && it.images != null }
        var messages = prepare(Prompt.messages(s, history, now, groups, withImages))
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
                        messages = prepare(Prompt.messages(s, history, now, groups, withImages))
                    }
                    is Step.Called -> {
                        if (rounds == MAX_TOOL_ROUNDS) {
                            note(conversationId, TOO_MANY_ROUNDS)
                            _streaming.value = null
                            return
                        }
                        messages = messages + step.message + runTools(conversationId, step, s)
                        rounds++
                    }
                }
            }
        } catch (e: CancellationException) {
            // Stopped while a tool ran: no stream is open to clear the live row on its way out.
            if (_streaming.value?.finished != true) _streaming.value = null
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
        _streaming.value = StreamingReply(conversationId, "", thinking = false)
        try {
            client.stream(endpoint, messages, specs).collect { event ->
                when (event) {
                    is ChatEvent.Delta -> {
                        text.append(event.text)
                        _streaming.value = StreamingReply(conversationId, text.toString(), thinking = false)
                    }
                    is ChatEvent.Reasoning -> {
                        reasoning.append(event.text)
                        if (text.isEmpty()) _streaming.value = StreamingReply(conversationId, "", thinking = true)
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
    private suspend fun runTools(conversationId: Long, step: Step.Called, s: AppSettings): List<ApiMessage> {
        val said = step.message.content
        val results = ArrayList<ApiMessage>(step.message.toolCalls.size)
        for (call in step.message.toolCalls) {
            // What was said before the calls stays up; the screen switches to the stored
            // copy (savedId) as soon as it is in the list.
            _streaming.value = StreamingReply(
                conversationId,
                said,
                thinking = false,
                savedId = step.savedId.takeIf { said.isNotEmpty() },
                activity = tools.activity(call.name),
            )
            val outcome = try {
                tools.run(call, s, conversationId)
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
            _streaming.value = handover
            scope.launch {
                delay(1500)
                _streaming.compareAndSet(handover, null)
            }
        } else {
            _streaming.value = null
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
