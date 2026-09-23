package com.cleo.cleos.ui.chat

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.ai.Prompt
import com.cleo.cleos.ai.StreamingReply
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversationId: Long? = null,
    val messages: List<MessageEntity> = emptyList(),
    /** What to draw live below the stored messages; text already stored is blanked out. */
    val streaming: StreamingReply? = null,
    /** A reply is under way here (the stop button), including while its tools run. */
    val replying: Boolean = false,
    val hasApiKey: Boolean = true,
    val aiName: String = "",
    val userName: String = "",
    val aiAvatar: String? = null,
    val aiAvatarEmoji: String? = null,
    val userAvatar: String? = null,
    val chatAvatars: Boolean = false,
    val model: String = "",
    val loaded: Boolean = false,
)

class ChatViewModel(private val c: AppContainer) : ViewModel() {
    private val conversationId = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            c.settings.currentConversation.collect { remembered ->
                val id = c.chat.resolveConversation(remembered)
                if (id != remembered) c.settings.setCurrentConversation(id)
                conversationId.value = id
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ChatUiState> = conversationId.filterNotNull().flatMapLatest { id ->
        combine(
            c.db.messages().observe(id),
            c.chat.streaming,
            c.secrets.hasApiKey,
            c.settings.settings,
        ) { messages, streaming, hasKey, s ->
            val live = streaming?.takeIf { it.conversationId == id }
            // Once the stored copy of the live text is in the list, the live one steps
            // aside: all of it when the reply is over, only the text while tools still run.
            val stored = live?.savedId != null && messages.any { it.id == live.savedId }
            ChatUiState(
                conversationId = id,
                messages = messages,
                streaming = when {
                    live == null -> null
                    stored && live.finished -> null
                    stored -> live.copy(text = "")
                    else -> live
                },
                replying = live != null && !live.finished,
                hasApiKey = hasKey,
                aiName = s.aiName,
                userName = s.userName,
                aiAvatar = s.aiAvatar,
                aiAvatarEmoji = s.aiAvatarEmoji,
                userAvatar = s.userAvatar,
                chatAvatars = s.chatAvatars,
                model = s.apiModel,
                loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    val busy: Boolean get() = c.chat.busy

    /** Pictures picked for the next message, already copied into the app's storage. */
    val attachments = mutableStateListOf<MessageImage>()
    var attaching by mutableStateOf(false)
        private set

    fun attach(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            attaching = true
            for (uri in uris.take(Prompt.MAX_IMAGES - attachments.size)) {
                runCatching { c.images.import(uri, maxEdge = 2048, prefix = "chat-") }
                    .onSuccess { attachments += MessageImage(it.file, it.width, it.height) }
            }
            attaching = false
        }
    }

    fun detach(image: MessageImage) {
        attachments.remove(image)
        c.appScope.launch { c.images.delete(listOf(image.file)) }
    }

    fun send(text: String) {
        val id = conversationId.value ?: return
        c.chat.send(id, text, attachments.toList())
        attachments.clear()
    }

    override fun onCleared() {
        // Picked but never sent: nothing will ever point at these files.
        val unsent = attachments.map { it.file }
        if (unsent.isNotEmpty()) c.appScope.launch { c.images.delete(unsent) }
    }

    fun stop() = c.chat.stop()

    fun retry(messageId: Long) {
        conversationId.value?.let { c.chat.retry(it, messageId) }
    }

    fun delete(messageId: Long) = c.chat.deleteMessage(messageId)

    fun answerSecret(requestMessageId: Long, grant: Boolean) {
        conversationId.value?.let { c.chat.answerSecretRequest(it, requestMessageId, grant) }
    }

    /** An empty conversation is reused instead of stacking up blank ones. */
    fun newConversation() {
        viewModelScope.launch {
            if (state.value.messages.isEmpty() && conversationId.value != null) return@launch
            c.settings.setCurrentConversation(c.chat.newConversation())
        }
    }
}
