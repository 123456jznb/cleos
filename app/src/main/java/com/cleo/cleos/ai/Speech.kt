package com.cleo.cleos.ai

import android.media.MediaMetadataRetriever
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.ImageStore
import com.cleo.cleos.data.MessageAudio
import com.cleo.cleos.data.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** What makes the TA's voice messages. */
enum class SpeechEngine(val key: String, val label: String) {
    /** An OpenAI-shaped /audio/speech: SiliconFlow's CosyVoice, OpenAI's own, Mossland's. */
    Api("api", "语音接口"),
    ElevenLabs("elevenlabs", "ElevenLabs"),

    /** A tool of one of the person's MCP services: whatever voice service they found. */
    Mcp("mcp", "MCP 工具"),
    ;

    companion object {
        fun of(key: String?): SpeechEngine = entries.firstOrNull { it.key == key } ?: Api
    }
}

/** A voice service for the OpenAI-shaped /audio/speech, as the settings screen offers it. */
data class SpeechPreset(val name: String, val baseUrl: String, val model: String, val voice: String)

class SpeechException(message: String) : Exception(message)

/** The TA's voice: requests, and finding the audio in what comes back. Nothing here touches the network. */
object Speech {
    val presets = listOf(
        SpeechPreset("硅基流动", "https://api.siliconflow.cn/v1", "FunAudioLLM/CosyVoice2-0.5B", "FunAudioLLM/CosyVoice2-0.5B:anna"),
        SpeechPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini-tts", "alloy"),
        // Mossland's voices are ids from its voice library; this one is its 轻快灵动女声.
        SpeechPreset("Mossland", "https://api.mosi.cn/v1", "moss-tts-1.5-flash", "806c9695-6160-404e-8722-4f788d935af3"),
    )

    /** ElevenLabs' key is filed under this address, like any other. */
    const val ELEVENLABS_BASE = "https://api.elevenlabs.io/v1"
    const val ELEVENLABS_MODEL = "eleven_multilingual_v2"

    /** What the settings screen plays to try a voice. */
    const val SAMPLE = "你好呀，这是我的声音。"

    /** A voice message says a sentence or two; longer is cut here rather than billed. */
    const val MAX_CHARS = 300

    private val json = Json { ignoreUnknownKeys = true }
    private val URL = Regex("""https?://[^\s"'<>）」]+""")
    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "opus", "m4a", "aac", "flac")

    /** Whether the engine picked has what it needs, so send_voice can be offered. */
    fun ready(s: AppSettings): Boolean = when (SpeechEngine.of(s.speechEngine)) {
        SpeechEngine.Api -> s.speechBaseUrl.isNotBlank() && s.speechModel.isNotBlank()
        SpeechEngine.ElevenLabs -> s.elevenVoice.isNotBlank()
        SpeechEngine.Mcp -> s.speechMcpServer.isNotBlank() && s.speechMcpTool.isNotBlank()
    }

    fun url(baseUrl: String): String =
        baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions").let { if (it.endsWith("/audio/speech")) it else "$it/audio/speech" }

    /** Mossland's API (Moss, api.mosi.cn): the same /audio/speech, but the voice goes in voice_id. */
    fun isMoss(baseUrl: String): Boolean {
        val host = baseUrl.trim().substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        return host == "mosi.cn" || host.endsWith(".mosi.cn")
    }

    fun apiBody(baseUrl: String, model: String, voice: String, text: String): String = buildJsonObject {
        put("model", model.trim())
        put("input", text)
        if (voice.isNotBlank()) put(if (isMoss(baseUrl)) "voice_id" else "voice", voice.trim())
        put("response_format", "mp3")
    }.toString()

    fun elevenLabsBody(model: String, text: String): String = buildJsonObject {
        put("text", text)
        put("model_id", model.trim().ifEmpty { ELEVENLABS_MODEL })
        putJsonObject("voice_settings") {
            put("stability", 0.5)
            put("similarity_boost", 0.75)
        }
    }.toString()

    /** The arguments for an MCP tool: the person's fixed ones, with the words under [textParam]. */
    fun mcpArguments(fixed: String, textParam: String, text: String): JsonObject {
        val base = fixed.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.parseToJsonElement(it) as JsonObject }.getOrNull() ?: throw SpeechException("「其他参数」不是一个 JSON 对象") }
            ?: JsonObject(emptyMap())
        return JsonObject(base + (textParam.trim().ifEmpty { "text" } to JsonPrimitive(text)))
    }

    /** The parameter an MCP tool most likely takes the words in: text, input or content, else its first string one. */
    fun textParam(schema: JsonObject): String {
        val props = (schema["properties"] as? JsonObject).orEmpty()
        return listOf("text", "input", "content", "prompt").firstOrNull { it in props }
            ?: props.entries.firstOrNull { (_, p) -> ((p as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "string" }?.key
            ?: "text"
    }

    /** A file extension for an audio type. */
    fun extension(mime: String?): String = when (mime?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/aac" -> "aac"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/flac", "audio/x-flac" -> "flac"
        else -> "mp3"
    }

    fun isAudioUrl(url: String): Boolean = url.substringBefore('?').substringAfterLast('.').lowercase() in AUDIO_EXTENSIONS

    /** Where an MCP tool's result keeps the audio it made. */
    sealed interface Found {
        class Bytes(val data: ByteArray, val mime: String?) : Found

        data class Link(val url: String) : Found
    }

    /**
     * The audio in an MCP tool's result: an audio part, a resource holding it or linking to
     * it, or a link in the text (the one that looks like an audio file, else the only one).
     */
    fun audioIn(result: JsonObject): Found? {
        fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
        val parts = (result["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        for (p in parts) {
            when (p.str("type")) {
                "audio" -> p.str("data")?.let { return Found.Bytes(Base64.getMimeDecoder().decode(it), p.str("mimeType")) }
                "resource" -> {
                    val r = p["resource"] as? JsonObject ?: continue
                    val mime = r.str("mimeType")
                    val blob = r.str("blob")
                    if (blob != null && (mime == null || mime.startsWith("audio"))) return Found.Bytes(Base64.getMimeDecoder().decode(blob), mime)
                    r.str("uri")?.takeIf { it.startsWith("http") && (isAudioUrl(it) || mime?.startsWith("audio") == true) }?.let { return Found.Link(it) }
                }
                "resource_link" -> p.str("uri")?.takeIf { it.startsWith("http") }?.let { return Found.Link(it) }
            }
        }
        val links = parts.filter { it.str("type") == "text" }
            .flatMap { part -> URL.findAll(part.str("text").orEmpty()).map { it.value.trimEnd('.', ',', ')', '。', '，') }.toList() }
            .distinct()
        return (links.firstOrNull(::isAudioUrl) ?: links.singleOrNull())?.let { Found.Link(it) }
    }
}

/**
 * Makes the TA's voice messages with the engine the person picked: into a file among the
 * pictures (voice_…, so backups and deletes take it along, like the person's own), with how
 * long it plays.
 */
class Speaker(
    private val images: ImageStore,
    http: OkHttpClient,
    private val secrets: SecretStore,
    private val mcp: McpHub,
) {
    private val http = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    /** [text] spoken; throws [SpeechException] with what went wrong, worded for the person. */
    suspend fun speak(s: AppSettings, text: String): MessageAudio {
        val words = text.trim().take(Speech.MAX_CHARS)
        if (words.isEmpty()) throw SpeechException("没有要说的话")
        val (bytes, mime) = when (SpeechEngine.of(s.speechEngine)) {
            SpeechEngine.Api -> api(s, words)
            SpeechEngine.ElevenLabs -> elevenLabs(s, words)
            SpeechEngine.Mcp -> fromMcp(s, words)
        }
        if (bytes.size < 64) throw SpeechException("回来的声音是空的")
        return withContext(Dispatchers.IO) {
            val file = images.file("voice_${System.currentTimeMillis()}_${(1000..9999).random()}.${Speech.extension(mime)}")
            file.writeBytes(bytes)
            val ms = runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(file.path)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                }
            }.getOrNull()
            // Some files don't say how long they are: about four characters a second, then.
            MessageAudio(file.name, ms ?: (words.length * 250L).coerceAtLeast(1000))
        }
    }

    private suspend fun api(s: AppSettings, text: String): Pair<ByteArray, String?> {
        val key = secrets.key(s.speechBaseUrl)?.takeIf { it.isNotBlank() } ?: throw SpeechException("语音接口还没填 Key")
        val request = Request.Builder()
            .url(Speech.url(s.speechBaseUrl))
            .header("Authorization", "Bearer $key")
            .post(Speech.apiBody(s.speechBaseUrl, s.speechModel, s.speechVoice, text).toRequestBody(JSON))
            .build()
        return fetch(request)
    }

    private suspend fun elevenLabs(s: AppSettings, text: String): Pair<ByteArray, String?> {
        val key = secrets.key(Speech.ELEVENLABS_BASE)?.takeIf { it.isNotBlank() } ?: throw SpeechException("ElevenLabs 还没填 Key")
        val request = Request.Builder()
            .url("${Speech.ELEVENLABS_BASE}/text-to-speech/${s.elevenVoice.trim()}")
            .header("xi-api-key", key)
            .header("Accept", "audio/mpeg")
            .post(Speech.elevenLabsBody(s.elevenModel, text).toRequestBody(JSON))
            .build()
        return fetch(request)
    }

    private suspend fun fromMcp(s: AppSettings, text: String): Pair<ByteArray, String?> {
        val server = mcp.servers.get(s.speechMcpServer)?.takeIf { it.enabled } ?: throw SpeechException("选的 MCP 服务不在了，或者关着")
        val result = try {
            mcp.callRaw(server, s.speechMcpTool, Speech.mcpArguments(s.speechMcpArgs, s.speechMcpTextParam, text))
        } catch (e: McpException) {
            throw SpeechException(e.message ?: "MCP 服务出错了")
        }
        if ((result["isError"] as? JsonPrimitive)?.contentOrNull == "true") throw SpeechException("工具说出错了：${Mcp.text(result).take(120)}")
        return when (val found = Speech.audioIn(result) ?: throw SpeechException("这个工具没回音频：${Mcp.text(result).take(120)}")) {
            is Speech.Found.Bytes -> found.data to found.mime
            is Speech.Found.Link -> fetch(Request.Builder().url(found.url).build())
        }
    }

    /** One request whose answer is the audio; an answer that isn't says why, where it can. */
    private suspend fun fetch(request: Request): Pair<ByteArray, String?> = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { r ->
                val type = r.header("Content-Type")
                if (!r.isSuccessful) throw SpeechException(describe(r.code, r.body.string()))
                if (type != null && (type.startsWith("application/json") || type.startsWith("text/"))) {
                    throw SpeechException("回来的不是声音：${r.body.string().take(120)}")
                }
                r.body.bytes() to type
            }
        } catch (e: IOException) {
            throw SpeechException("网络出错：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun describe(code: Int, body: String): String {
        val hint = when (code) {
            401, 403 -> "Key 不对，或者已经失效了"
            402 -> "账户余额不足"
            404 -> "地址、模型或声音不对（404）"
            429 -> "请求太频繁，或者额度用完了"
            in 500..599 -> "服务那边出错了（$code）"
            else -> "请求失败（$code）"
        }
        val detail = body.trim().take(120)
        return if (detail.isEmpty()) hint else "$hint：$detail"
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
