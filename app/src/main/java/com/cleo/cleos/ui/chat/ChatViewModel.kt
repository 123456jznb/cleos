package com.cleo.cleos.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.ai.StreamingReply
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
    val streaming: StreamingReply? = null,
    val hasApiKey: Boolean = true,
    val aiName: String = "",
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
            ChatUiState(
                conversationId = id,
                messages = messages,
                // Show the live bubble only for this conversation, and only until the
                // stored copy of the same reply has appeared in the list.
                streaming = streaming?.takeIf { live ->
                    live.conversationId == id && (live.savedId == null || messages.none { it.id == live.savedId })
                },
                hasApiKey = hasKey,
                aiName = s.aiName,
                model = s.apiModel,
                loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    val busy: Boolean get() = c.chat.busy

    fun send(text: String) {
        conversationId.value?.let { c.chat.send(it, text) }
    }

    fun stop() = c.chat.stop()

    fun retry(messageId: Long) {
        conversationId.value?.let { c.chat.retry(it, messageId) }
    }

    fun delete(messageId: Long) = c.chat.deleteMessage(messageId)

    /** An empty conversation is reused instead of stacking up blank ones. */
    fun newConversation() {
        viewModelScope.launch {
            if (state.value.messages.isEmpty() && conversationId.value != null) return@launch
            c.settings.setCurrentConversation(c.chat.newConversation())
        }
    }
}
