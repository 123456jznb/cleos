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

    /** Lists the endpoint's models; that the list comes back at all is the connection test. */
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
            checkResult = try {
                val list = c.chatClient.models(ApiEndpoint(baseUrl, key, model))
                models = list
                if (list.isEmpty()) "连上了，但这个地址没有列出模型" else "连上了，有 ${list.size} 个模型可选"
            } catch (e: ChatException) {
                // No such page, past the key's check (a wrong key is a 401): connected, to a service
                // that lists no models (智谱). Its known ones instead.
                val known = ApiPresets.at(baseUrl)?.models.orEmpty()
                if (e.status in NO_LIST && known.isNotEmpty()) {
                    models = known
                    "连上了。这家不列出模型，下面是它常用的几个"
                } else {
                    e.message
                }
            } catch (e: Exception) {
                "出错了：${e.message ?: e.javaClass.simpleName}"
            }
            checking = false
        }
    }
}

/** How an address answers being asked for its models when it keeps no list: no such page, or not asked that way. */
private val NO_LIST = setOf(404, 405)
