package com.cleo.cleos.ui.settings

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleo.cleos.AppContainer
import com.cleo.cleos.ai.ApiEndpoint
import com.cleo.cleos.ai.ChatException
import com.cleo.cleos.ai.ToolGroup
import com.cleo.cleos.data.ApiPreset
import android.media.MediaPlayer
import com.cleo.cleos.ai.McpTool
import com.cleo.cleos.ai.Speech
import com.cleo.cleos.ai.SpeechEngine
import com.cleo.cleos.ai.SpeechPreset
import com.cleo.cleos.ai.Voice
import kotlinx.coroutines.CancellationException
import java.io.File
import com.cleo.cleos.ai.VoicePreset
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.McpServer
import com.cleo.cleos.data.Companions
import com.cleo.cleos.data.GlassMode
import com.cleo.cleos.data.ImportException
import com.cleo.cleos.data.ImportPlan
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.ui.wallpaper.WallpaperAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PERSONA_LIMIT = Companions.PERSONA_LIMIT

/**
 * Text fields are edited locally and written back after a short pause (and once more
 * on leaving), so typing does not hit the disk on every keystroke. The API key is the
 * exception: it is only ever written, encrypted, and never read back into a field.
 *
 * The model, the TA's name and persona belong to the TA that was current when the screen
 * opened (or the one just imported); the rest is the person's and the app's.
 */
class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    var companionId by mutableLongStateOf(0L)
        private set
    var companionCount by mutableIntStateOf(1)
        private set
    private var deleted = false
    var baseUrl by mutableStateOf("")
    var model by mutableStateOf("")
    var aiName by mutableStateOf("")
    var userName by mutableStateOf("")
    var persona by mutableStateOf("")
    var deepThinking by mutableStateOf(false)
        private set
    var historySize by mutableIntStateOf(40)
    var weatherCity by mutableStateOf("")
    var voiceBaseUrl by mutableStateOf("")
    var voiceModel by mutableStateOf("")
    var voiceKeyInput by mutableStateOf("")
    var voiceTesting by mutableStateOf(false)
        private set
    var voiceResult by mutableStateOf<String?>(null)
        private set
    var speechEngine by mutableStateOf(SpeechEngine.Api)
    var speechBaseUrl by mutableStateOf("")
    var speechModel by mutableStateOf("")
    var speechVoice by mutableStateOf("")
    var elevenVoice by mutableStateOf("")
    var elevenModel by mutableStateOf("")
    var speechMcpServer by mutableStateOf("")
        private set
    var speechMcpTool by mutableStateOf("")
        private set
    var speechMcpTextParam by mutableStateOf("text")
    var speechMcpArgs by mutableStateOf("")
    var speechKeyInput by mutableStateOf("")
    var speechTools by mutableStateOf<List<McpTool>?>(null)
        private set
    var speechBusy by mutableStateOf(false)
        private set
    var speechResult by mutableStateOf<String?>(null)
        private set
    private var speechPlayer: MediaPlayer? = null
    var keyInput by mutableStateOf("")
    var loaded by mutableStateOf(false)
        private set

    var models by mutableStateOf<List<String>?>(null)
    var checking by mutableStateOf(false)
        private set
    var checkResult by mutableStateOf<String?>(null)
        private set
    var wallpaperBusy by mutableStateOf(false)
        private set
    var wallpaperError by mutableStateOf<String?>(null)
        private set

    /** Whether the address being edited has a key yet: keys are filed by address. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val hasKey: StateFlow<Boolean> = snapshotFlow { baseUrl }
        .flatMapLatest { c.secrets.hasKey(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** The address the voice engine's key is filed under: ElevenLabs' own, or the voice service's. */
    private fun speechKeyAddress() = if (speechEngine == SpeechEngine.ElevenLabs) Speech.ELEVENLABS_BASE else speechBaseUrl

    @OptIn(ExperimentalCoroutinesApi::class)
    val hasSpeechKey: StateFlow<Boolean> = snapshotFlow { speechKeyAddress() }
        .flatMapLatest { c.secrets.hasKey(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether the transcription service's address has a key yet: filed by address, like the chat keys. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val hasVoiceKey: StateFlow<Boolean> = snapshotFlow { voiceBaseUrl }
        .flatMapLatest { c.secrets.hasKey(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val mcpServers: StateFlow<List<McpServer>> = c.mcp.servers.all.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch { c.companions.all.collect { companionCount = it.size } }
        viewModelScope.launch {
            val s = c.settings.current()
            load(c.companions.current())
            userName = s.userName
            historySize = s.historySize
            weatherCity = s.weatherCity
            voiceBaseUrl = s.voiceBaseUrl
            voiceModel = s.voiceModel
            speechEngine = SpeechEngine.of(s.speechEngine)
            speechBaseUrl = s.speechBaseUrl
            speechModel = s.speechModel
            speechVoice = s.speechVoice
            elevenVoice = s.elevenVoice
            elevenModel = s.elevenModel
            speechMcpServer = s.speechMcpServer
            speechMcpTool = s.speechMcpTool
            speechMcpTextParam = s.speechMcpTextParam
            speechMcpArgs = s.speechMcpArgs
            loaded = true
            watch()
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watch() {
        snapshotFlow {
            listOf(
                baseUrl, model, aiName, userName, persona, historySize, weatherCity, voiceBaseUrl, voiceModel,
                speechEngine, speechBaseUrl, speechModel, speechVoice, elevenVoice, elevenModel,
                speechMcpServer, speechMcpTool, speechMcpTextParam, speechMcpArgs,
            )
        }
            .drop(1)
            .debounce(500)
            .collect { persist() }
    }

    /** Which TA the fields edit. Everything is set in one go, with nothing in between. */
    private fun load(ta: CompanionEntity) {
        companionId = ta.id
        baseUrl = ta.apiBaseUrl
        model = ta.apiModel
        aiName = ta.name
        persona = ta.persona
        deepThinking = ta.deepThinking
        keyInput = ""
        models = null
        checkResult = null
    }

    private suspend fun persist() {
        if (!loaded || deleted) return
        // Read before suspending: the fields can be loaded with another TA meanwhile (an
        // import), and the old TA must not get the new one's values.
        val id = companionId
        val url = baseUrl.trim()
        val m = model.trim()
        val name = aiName.trim()
        val p = persona.take(PERSONA_LIMIT)
        val user = userName.trim()
        val history = historySize
        val city = weatherCity.trim()
        val voiceUrl = voiceBaseUrl.trim()
        val voiceM = voiceModel.trim()
        c.companions.update(id) { it.copy(apiBaseUrl = url, apiModel = m, name = name, persona = p) }
        val speech = speechSettings()
        c.settings.update {
            speech(it.copy(userName = user, historySize = history, weatherCity = city, voiceBaseUrl = voiceUrl, voiceModel = voiceM))
        }
    }

    /** Removes this TA with their conversations and diary; [then] leaves the screen. */
    fun deleteCompanion(then: () -> Unit) {
        deleted = true
        val id = companionId
        viewModelScope.launch {
            c.chat.stopRepliesOf(id)
            c.companions.delete(id)
            then()
        }
    }

    /** The voice fields as they are now, to lay over settings (read before anything suspends). */
    private fun speechSettings(): (AppSettings) -> AppSettings {
        val engine = speechEngine.key
        val url = speechBaseUrl.trim()
        val m = speechModel.trim()
        val voice = speechVoice.trim()
        val eVoice = elevenVoice.trim()
        val eModel = elevenModel.trim()
        val server = speechMcpServer
        val tool = speechMcpTool
        val param = speechMcpTextParam.trim().ifEmpty { "text" }
        val args = speechMcpArgs.trim()
        return {
            it.copy(
                speechEngine = engine,
                speechBaseUrl = url,
                speechModel = m,
                speechVoice = voice,
                elevenVoice = eVoice,
                elevenModel = eModel,
                speechMcpServer = server,
                speechMcpTool = tool,
                speechMcpTextParam = param,
                speechMcpArgs = args,
            )
        }
    }

    fun applySpeechPreset(p: SpeechPreset) {
        speechBaseUrl = p.baseUrl
        speechModel = p.model
        speechVoice = p.voice
        speechResult = null
    }

    fun saveSpeechKey() {
        val url = speechKeyAddress().trim()
        val key = speechKeyInput.trim()
        if (url.isEmpty() || key.isEmpty()) return
        viewModelScope.launch {
            c.secrets.setKey(url, key)
            speechKeyInput = ""
        }
    }

    /** The tools of the MCP services switched on, to pick the voice from. */
    fun loadSpeechTools() {
        viewModelScope.launch { speechTools = c.mcp.tools() }
    }

    fun pickSpeechTool(t: McpTool) {
        speechMcpServer = t.serverId
        speechMcpTool = t.name
        speechMcpTextParam = Speech.textParam(t.inputSchema)
        speechResult = null
    }

    /** Says a sentence with the voice as set up on screen, saved or not, and plays it. */
    fun previewSpeech() {
        if (speechBusy) return
        speechBusy = true
        speechResult = null
        val s = speechSettings()(settings.value)
        viewModelScope.launch {
            speechResult = try {
                val clip = c.speaker.speak(s, Speech.SAMPLE)
                play(c.images.file(clip.file))
                "能用，正在放（${Voice.duration(clip.ms)}）。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "没成：${e.message ?: e.javaClass.simpleName}"
            }
            speechBusy = false
        }
    }

    /** Plays a try-out, then throws it away. */
    private fun play(file: File) {
        speechPlayer?.release()
        val p = MediaPlayer()
        runCatching {
            p.setDataSource(file.path)
            p.setOnCompletionListener {
                it.release()
                if (speechPlayer === it) speechPlayer = null
                file.delete()
            }
            p.prepare()
            p.start()
            speechPlayer = p
        }.onFailure {
            p.release()
            file.delete()
            throw it
        }
    }

    fun applyVoicePreset(p: VoicePreset) {
        voiceBaseUrl = p.baseUrl
        voiceModel = p.model
        voiceResult = null
    }

    fun saveVoiceKey() {
        val url = voiceBaseUrl.trim()
        val key = voiceKeyInput.trim()
        if (url.isEmpty() || key.isEmpty()) return
        viewModelScope.launch {
            c.secrets.setKey(url, key)
            voiceKeyInput = ""
        }
    }

    /** Sends a second of silence: a service that answers at all is set up right. */
    fun testVoice() {
        if (voiceTesting || voiceBaseUrl.isBlank()) return
        voiceTesting = true
        voiceResult = null
        viewModelScope.launch {
            val probe = c.images.file("voice_probe.wav")
            voiceResult = try {
                withContext(Dispatchers.IO) { probe.writeBytes(Voice.silence()) }
                c.transcriber.transcribe(voiceBaseUrl, voiceModel, probe)
                "能用。（试的是一秒静音，所以没转出字来。）"
            } catch (e: ChatException) {
                "没成：${e.message}"
            } finally {
                withContext(Dispatchers.IO) { probe.delete() }
            }
            voiceTesting = false
        }
    }

    fun setMcpEnabled(id: String, on: Boolean) {
        viewModelScope.launch { c.mcp.servers.setEnabled(id, on) }
    }

    /** Takes effect at once, like the tool switches. */
    fun setThinking(on: Boolean) {
        deepThinking = on
        val id = companionId
        viewModelScope.launch { c.companions.update(id) { it.copy(deepThinking = on) } }
    }

    fun setTool(group: ToolGroup, on: Boolean) {
        viewModelScope.launch {
            c.settings.update { it.copy(tools = if (on) it.tools + group else it.tools - group) }
        }
    }

    fun setLetterEveryDays(days: Int) {
        viewModelScope.launch { c.settings.update { it.copy(letterEveryDays = days) } }
    }

    fun applyPreset(p: ApiPreset) {
        baseUrl = p.baseUrl
        model = p.defaultModel
        models = null
        checkResult = null
    }

    fun saveKey() {
        val k = keyInput.trim()
        if (k.isEmpty()) return
        viewModelScope.launch {
            c.secrets.setKey(baseUrl, k)
            keyInput = ""
        }
    }

    fun clearKey() {
        viewModelScope.launch { c.secrets.setKey(baseUrl, null) }
    }

    /** Lists the endpoint's models; that the list comes back at all is the connection test. */
    fun check() {
        viewModelScope.launch {
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
                e.message
            } catch (e: Exception) {
                "出错了：${e.message ?: e.javaClass.simpleName}"
            }
            checking = false
        }
    }

    fun setWallpaper(uri: Uri) {
        viewModelScope.launch {
            wallpaperBusy = true
            wallpaperError = null
            try {
                val old = settings.value.wallpaper
                val stored = c.images.import(uri, maxEdge = 2560, prefix = "wallpaper-")
                val tone = withContext(Dispatchers.Default) {
                    c.images.thumbnail(stored.file)?.let { WallpaperAnalyzer.analyze(it) }
                }
                c.settings.update {
                    it.copy(
                        wallpaper = stored.file,
                        wallpaperDark = tone?.dark,
                        wallpaperHue = tone?.hue,
                        wallpaperChroma = tone?.chroma,
                        wallpaperTrough = tone?.trough,
                        wallpaperPeak = tone?.peak,
                    )
                }
                if (old != null && old != stored.file) c.images.delete(listOf(old))
            } catch (e: Exception) {
                wallpaperError = "这张图读不出来：${e.message ?: e.javaClass.simpleName}"
            } finally {
                wallpaperBusy = false
            }
        }
    }

    fun resetWallpaper() {
        viewModelScope.launch {
            val old = settings.value.wallpaper
            c.settings.update {
                it.copy(
                    wallpaper = null,
                    wallpaperDark = null,
                    wallpaperHue = null,
                    wallpaperChroma = null,
                    wallpaperTrough = null,
                    wallpaperPeak = null,
                )
            }
            if (old != null) c.images.delete(listOf(old))
        }
    }

    fun setGlassMode(mode: GlassMode) {
        viewModelScope.launch { c.settings.update { it.copy(glassMode = mode) } }
    }

    /** The colour of the person's own bubbles (ARGB); null follows the wallpaper. */
    fun setMyBubble(argb: Int?) {
        viewModelScope.launch { c.settings.update { it.copy(myBubble = argb) } }
    }

    var backupBusy by mutableStateOf(false)
        private set
    var backupMessage by mutableStateOf<String?>(null)
        private set
    var canUndoRestore by mutableStateOf(c.backup.hasSnapshot)
        private set

    fun exportBackup(uri: Uri) = runBackup("导出了") { c.backup.export(uri) }

    // A reply still running would write into conversations that are about to be replaced.
    fun restoreBackup(uri: Uri) = runBackup("恢复了") {
        c.chat.stopAll()
        c.backup.restore(uri)
    }

    fun undoRestore() = runBackup("撤销了，回到恢复前：") {
        c.chat.stopAll()
        c.backup.undoRestore()
    }

    /** A file from another app, read and waiting for the person's yes. */
    var pendingImport by mutableStateOf<ImportPlan?>(null)
        private set

    fun readImport(uri: Uri) {
        if (backupBusy) return
        backupBusy = true
        backupMessage = null
        viewModelScope.launch {
            try {
                pendingImport = c.imports.read(uri)
            } catch (e: ImportException) {
                backupMessage = e.message
            } catch (e: Exception) {
                backupMessage = "出错了：${e.message ?: e.javaClass.simpleName}"
            } finally {
                backupBusy = false
            }
        }
    }

    fun cancelImport() {
        pendingImport = null
    }

    /** Brings the file in as a new TA called [name]; this screen then edits that TA. */
    fun confirmImport(name: String) {
        val plan = pendingImport ?: return
        pendingImport = null
        backupBusy = true
        backupMessage = null
        c.appScope.launch {
            val message = try {
                // What was typed here belongs to the TA the screen was editing until now.
                persist()
                val ta = c.imports.import(plan, name)
                val keyed = c.secrets.hasKey(ta.apiBaseUrl).first()
                withContext(Dispatchers.Main) { load(ta) }
                val shown = ta.name.ifEmpty { "TA" }
                buildString {
                    append("导入好了：新的 TA「$shown」，${plan.conversations.size} 段对话（${plan.messageCount} 条消息）、")
                    append("${plan.diary.size} 篇日记")
                    if (plan.memories.isNotEmpty()) append("，它记得关于你的 ${plan.memories.size} 件事")
                    append("。这一页上面现在设置的就是$shown")
                    if (!keyed) append("；它用的接口还没有 Key，填在最上面")
                    append("。")
                }
            } catch (e: Exception) {
                "导入没成：${e.message ?: e.javaClass.simpleName}。什么都没有写进去。"
            }
            withContext(Dispatchers.Main) {
                backupMessage = message
                backupBusy = false
            }
        }
    }

    // App scope: a restore half done because the screen was closed is the worst outcome.
    private fun runBackup(done: String, action: suspend () -> Any) {
        if (backupBusy) return
        backupBusy = true
        backupMessage = null
        c.appScope.launch {
            val message = try {
                "$done ${action()}"
            } catch (e: Exception) {
                (e as? com.cleo.cleos.data.BackupException)?.message ?: "出错了：${e.message ?: e.javaClass.simpleName}"
            }
            withContext(Dispatchers.Main) {
                backupMessage = message
                backupBusy = false
                canUndoRestore = c.backup.hasSnapshot
            }
        }
    }

    override fun onCleared() {
        speechPlayer?.release()
        speechPlayer = null
        val pendingKey = keyInput.trim()
        val address = baseUrl
        c.appScope.launch {
            persist()
            if (pendingKey.isNotEmpty() && !deleted) c.secrets.setKey(address, pendingKey)
        }
    }
}
