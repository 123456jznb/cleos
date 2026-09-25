package com.cleo.cleos.ui.chat

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.ai.ChatRepository
import com.cleo.cleos.ai.Recap
import com.cleo.cleos.ai.Prompt
import com.cleo.cleos.ai.StreamingReply
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    /** The TA this conversation is with. */
    val companionId: Long = 0,
    val aiName: String = "",
    val userName: String = "",
    val aiAvatar: String? = null,
    val aiAvatarEmoji: String? = null,
    val userAvatar: String? = null,
    val chatAvatars: Boolean = false,
    val model: String = "",
    /** What the TA keeps of the messages no longer sent verbatim. */
    val recap: String? = null,
    /** The last message folded into [recap]: its time and id. */
    val recapUntil: Pair<Long, Long>? = null,
    val loaded: Boolean = false,
)

class ChatViewModel(private val c: AppContainer) : ViewModel() {
    private val conversationId = MutableStateFlow<Long?>(null)

    init {
        // The conversation shown is always the current TA's: switching TA opens their latest.
        viewModelScope.launch {
            combine(c.settings.currentConversation, c.companions.current.map { it.id }.distinctUntilChanged()) { remembered, ta ->
                remembered to ta
            }.collect { (remembered, ta) ->
                val id = c.chat.resolveConversation(remembered, ta)
                if (id != remembered) c.settings.setCurrentConversation(id)
                conversationId.value = id
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ChatUiState> = conversationId.filterNotNull().flatMapLatest { id ->
        // The TA of the conversation on screen. Right after a switch that is still the
        // previous TA, until their latest conversation has been found.
        val here = combine(c.db.conversations().observe(id), c.companions.all) { conversation, list ->
            (list.firstOrNull { it.id == conversation?.companionId } ?: list.firstOrNull())?.let { conversation to it }
        }.filterNotNull()
        combine(
            c.db.messages().observe(id),
            c.chat.streaming,
            here,
            here.map { it.second.apiBaseUrl }.distinctUntilChanged().flatMapLatest { c.secrets.hasKey(it) },
            c.settings.settings,
        ) { messages, streaming, (conversation, ta), hasKey, s ->
            val live = streaming[id]
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
                companionId = ta.id,
                aiName = ta.name,
                userName = s.userName,
                aiAvatar = ta.avatar,
                aiAvatarEmoji = ta.avatarEmoji,
                userAvatar = s.userAvatar,
                chatAvatars = s.chatAvatars,
                model = ta.apiModel,
                recap = conversation?.recap,
                recapUntil = conversation?.let { cv -> cv.recapUntilAt?.let { at -> at to (cv.recapUntilId ?: Long.MAX_VALUE) } },
                loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

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

    /** False when nothing went out: the text and pictures stay where they are. */
    fun send(text: String): Boolean {
        val id = conversationId.value ?: return false
        if (!c.chat.send(id, text, attachments.toList())) return false
        attachments.clear()
        return true
    }

    override fun onCleared() {
        // Picked but never sent: nothing will ever point at these files.
        val unsent = attachments.map { it.file }
        if (unsent.isNotEmpty()) c.appScope.launch { c.images.delete(unsent) }
    }

    fun stop() {
        conversationId.value?.let { c.chat.stop(it) }
    }

    fun retry(messageId: Long) {
        conversationId.value?.let { c.chat.retry(it, messageId) }
    }

    fun delete(messageId: Long) = c.chat.deleteMessage(messageId)

    /** The person's answer to a card asking whether the TA may use an outside service's tool. */
    fun answerAsk(answer: ChatRepository.Answer) {
        val id = state.value.conversationId ?: return
        c.chat.answer(id, answer)
    }

    /** The person's own version of the recap. Emptied, the TA keeps nothing of what came before. */
    fun saveRecap(text: String) {
        val id = state.value.conversationId ?: return
        viewModelScope.launch { c.db.conversations().editRecap(id, text.trim().take(Recap.MAX_STORED).ifEmpty { null }) }
    }

    fun answerSecret(requestMessageId: Long, grant: Boolean) {
        conversationId.value?.let { c.chat.answerSecretRequest(it, requestMessageId, grant) }
    }

    /** An empty conversation is reused instead of stacking up blank ones. */
    fun newConversation() {
        viewModelScope.launch {
            if (state.value.messages.isEmpty() && conversationId.value != null) return@launch
            c.settings.setCurrentConversation(c.chat.newConversation(c.companions.current.first().id))
        }
    }

    fun switchTo(companionId: Long) {
        viewModelScope.launch { c.companions.select(companionId) }
    }

    /** A new TA, chosen right away; [then] opens their settings to name them and pick a model. */
    fun addCompanion(then: () -> Unit) {
        viewModelScope.launch {
            c.companions.add()
            then()
        }
    }
}
