package com.cleo.cleos.ai

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
import java.time.ZonedDateTime

/** The reply being written right now. [savedId] is set once it has been stored. */
data class StreamingReply(
    val conversationId: Long,
    val text: String,
    val thinking: Boolean,
    val savedId: Long? = null,
)

/**
 * Sending and receiving. Replies run in the app-wide scope, not a screen's: switching
 * to the diary mid-reply should not cut the reply off.
 */
class ChatRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val client: ChatClient,
    private val scope: CoroutineScope,
) {
    private val _streaming = MutableStateFlow<StreamingReply?>(null)
    val streaming: StateFlow<StreamingReply?> = _streaming.asStateFlow()
    private var job: Job? = null

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

    fun send(conversationId: Long, text: String) {
        if (busy) return
        val content = text.trim()
        if (content.isEmpty()) return
        job = scope.launch {
            val now = System.currentTimeMillis()
            db.messages().insert(MessageEntity(conversationId = conversationId, role = "user", content = content, createdAt = now))
            val conversation = db.conversations().get(conversationId)
            if (conversation != null && conversation.title == DEFAULT_TITLE) {
                db.conversations().rename(conversationId, content.lineSequence().first().take(24))
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

    fun deleteMessage(id: Long) {
        scope.launch { db.messages().delete(id) }
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
        val history = db.messages().newest(conversationId, s.historySize).reversed()
        val messages = Prompt.messages(s, history, ZonedDateTime.now())
        val endpoint = ApiEndpoint(s.apiBaseUrl, key, s.apiModel)

        val startedAt = System.currentTimeMillis()
        val text = StringBuilder()
        var error: String? = null
        var stopped = false
        _streaming.value = StreamingReply(conversationId, "", thinking = false)
        try {
            client.stream(endpoint, messages).collect { event ->
                when (event) {
                    is ChatEvent.Delta -> {
                        text.append(event.text)
                        _streaming.value = StreamingReply(conversationId, text.toString(), thinking = false)
                    }
                    ChatEvent.Thinking -> _streaming.value = StreamingReply(conversationId, text.toString(), thinking = true)
                }
            }
        } catch (e: CancellationException) {
            stopped = true
            throw e
        } catch (e: ChatException) {
            error = e.message
        } catch (e: Exception) {
            error = "出错了：${e.message ?: e.javaClass.simpleName}"
        } finally {
            withContext(NonCancellable) {
                val body = text.toString()
                val keep = body.isNotEmpty() || (!stopped && error != null)
                if (keep) {
                    val id = db.messages().insert(
                        MessageEntity(
                            conversationId = conversationId,
                            role = "assistant",
                            content = body,
                            createdAt = startedAt,
                            error = if (stopped) STOPPED else error,
                        ),
                    )
                    db.conversations().touch(conversationId, System.currentTimeMillis())
                    // Hand over from the live bubble to the stored one without a gap:
                    // the screen hides the live bubble once it sees savedId in its list.
                    // The cleanup runs on its own so this job (and `busy`) ends now.
                    val handover = StreamingReply(conversationId, body, thinking = false, savedId = id)
                    _streaming.value = handover
                    scope.launch {
                        delay(1500)
                        _streaming.compareAndSet(handover, null)
                    }
                } else {
                    _streaming.value = null
                }
            }
        }
    }

    companion object {
        const val DEFAULT_TITLE = "新对话"
        const val STOPPED = "已停止"
    }
}
