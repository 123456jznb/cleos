package com.cleo.cleos.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.cleo.cleos.AppContainer
import com.cleo.cleos.ai.ApiEndpoint
import com.cleo.cleos.ai.ChatException
import com.cleo.cleos.data.ApiPreset
import com.cleo.cleos.data.ApiPresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One model's connection as it is being edited: the address, a key for it, the model, and the
 * test that lists what the address has. The TA's model is one of these; the one it has for words
 * that are heard (CompanionEntity.spokenModelOn) is another, edited the same way. The key is only
 * ever written, encrypted and filed by address, never read back into the field.
 */
@Stable
class EndpointFields(private val c: AppContainer, private val scope: CoroutineScope) {
    var baseUrl by mutableStateOf("")
    var model by mutableStateOf("")
    var keyInput by mutableStateOf("")
    var models by mutableStateOf<List<String>?>(null)
    var checking by mutableStateOf(false)
        private set
    var checkResult by mutableStateOf<String?>(null)
        private set

    /** Whether the address being edited has a key yet: keys are filed by address. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val hasKey: StateFlow<Boolean> = snapshotFlow { baseUrl }
        .flatMapLatest { c.secrets.hasKey(it) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Another TA's, or the same one's afresh: what was typed or listed for the last one goes. */
    fun load(url: String, m: String) {
        baseUrl = url
        model = m
        keyInput = ""
        models = null
        checkResult = null
    }

    /** A key typed and not saved yet, with the address it goes with, to save on the way out. */
    fun pendingKey(): Pair<String, String>? = keyInput.trim().takeIf { it.isNotEmpty() }?.let { baseUrl to it }

    fun applyPreset(p: ApiPreset) {
        baseUrl = p.baseUrl
        model = p.defaultModel
        // A service that lists no models: the ones it is known to have, to pick from at once.
        models = p.models.ifEmpty { null }
        checkResult = null
    }

    fun saveKey() {
        val k = keyInput.trim()
        if (k.isEmpty()) return
        scope.launch {
            c.secrets.setKey(baseUrl, k)
            keyInput = ""
        }
    }

    fun clearKey() {
        scope.launch { c.secrets.setKey(baseUrl, null) }
    }

    /**
     * Sends one real message and reports whether a reply came back. Filling the model
     * picker happens after that, and only as a convenience.
     *
     * The test used to be "can this address list its models", which sent at least one
     * person round in circles: her address was right and her key was missing half of
     * itself, but the list came back in an unexpected shape, so the app talked about the
     * address and never once about the key. Asking the chat endpoint directly lets a bad
     * key say "bad key".
     */
    fun check() {
        scope.launch {
            checking = true
            checkResult = null
            val key = keyInput.trim().ifEmpty { c.secrets.key(baseUrl).orEmpty() }
            if (key.isEmpty()) {
                checkResult = "先填 API Key"
                checking = false
                return@launch
            }
            val endpoint = ApiEndpoint(baseUrl, key, model)
            checkResult = try {
                if (model.isBlank()) {
                    // Nothing to send a message *as* yet. Offer the list so there is
                    // something to pick from, and say plainly that this was not the test.
                    fillModels(endpoint)
                    "先填一个模型名再测一次——没有模型名就发不出消息，也就试不出来"
                } else {
                    c.chatClient.probe(endpoint)
                    val n = fillModels(endpoint)
                    if (n == null) "连上了，说得上话。这家不给模型列表，模型名自己填就行"
                    else "连上了，说得上话。有 $n 个模型可选"
                }
            } catch (e: ChatException) {
                // Saying hello fails for things that leave the address and the key both right,
                // and a model name the service has never heard of is the usual one — a relay
                // answers those with a 404 and its own complaint. The names it does know are
                // then the answer to "so what is it called here?", so they are fetched and
                // offered; the verdict stays what the test found, not what the list says.
                val n = fillModels(endpoint)
                if (n == null) e.message else "${e.message}\n\n它认的模型名，可以从上面「从列表里选」里挑一个。"
            } catch (e: Exception) {
                "出错了：${e.message ?: e.javaClass.simpleName}"
            }
            checking = false
        }
    }

    /**
     * Fills the model picker if anything can fill it, and says with how many.
     *
     * Never throws, and never decides the verdict: listing models and chatting are two
     * different endpoints, and a service is allowed to serve only the second. Falls back
     * to the models a preset already knows of (智谱 keeps no list).
     */
    private suspend fun fillModels(endpoint: ApiEndpoint): Int? {
        val list = runCatching { c.chatClient.models(endpoint) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: ApiPresets.at(baseUrl)?.models?.takeIf { it.isNotEmpty() }
            ?: return null
        models = list
        return list.size
    }
}
