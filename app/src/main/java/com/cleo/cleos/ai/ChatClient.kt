package com.cleo.cleos.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

data class ApiMessage(
    val role: String,
    val content: String,
    /** An assistant turn that called tools. */
    val toolCalls: List<ToolCall> = emptyList(),
    /** A tool result: the call it answers. */
    val toolCallId: String? = null,
    /** Reasoning handed back within the turn that produced it (see MessageEntity.reasoning). */
    val reasoning: String? = null,
    /**
     * Pictures the person sent: ImageStore names as Prompt builds the message, turned into
     * data: URLs just before sending.
     */
    val images: List<String> = emptyList(),
    /** An assistant message that went as a voice message: Prompt gives it back as the send_voice it was. Not sent. */
    val spoken: Boolean = false,
    /** An assistant message that quoted one of the person's: the words, for the send_message it was. Not sent. */
    val quoted: String? = null,
)

/** Where to send a conversation. [baseUrl] may or may not already end in /chat/completions. */
data class ApiEndpoint(val baseUrl: String, val apiKey: String, val model: String) {
    val chatUrl: String
        get() = baseUrl.trim().trimEnd('/').let { if (it.endsWith("/chat/completions")) it else "$it/chat/completions" }
    val modelsUrl: String
        get() = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions") + "/models"
}

sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent

    /**
     * The model thinking before it answers, shown to the person folded above the reply.
     * [sendBack]: it came as reasoning_content, which the provider wants back while the same
     * turn is still calling tools; thinking found anywhere else is only shown.
     */
    data class Reasoning(val text: String, val sendBack: Boolean = true) : ChatEvent

    /** The tool calls the reply ended with. Sent once, after everything else. */
    data class ToolCalls(val calls: List<ToolCall>) : ChatEvent
}

/** A failure with a message already worded for the person using the app. */
class ChatException(message: String, val status: Int? = null) : Exception(message)

/**
 * OpenAI-compatible chat completions with streaming (DeepSeek, OpenAI, SiliconFlow,
 * Moonshot, OpenRouter… all speak it).
 *
 * The stream is read line by line from OkHttp's buffered source. A server-sent event can
 * arrive split across two network chunks; splitting each chunk on newlines by hand drops
 * both halves of such a line, which shows up as characters missing from the middle of
 * long replies. readUtf8Line() waits for the whole line.
 */
class ChatClient(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Endpoints (address and model) that turned down being told not to think, in this run of the
     * app. From then on they aren't told: thinking or not is up to them.
     */
    private val refusesOff = ConcurrentHashMap.newKeySet<String>()

    /**
     * [thinking]: ask the model to think first (see [requestBody]). Without it, a model that thinks
     * unless told otherwise ([Thinking.canSwitchOff]) is told not to: the wait and the tokens are
     * for nothing then. One that can't stop thinking ([Thinking.thinksAlways]) is told to think
     * little. One that turns either down is asked again plainly, at once.
     */
    fun stream(
        endpoint: ApiEndpoint,
        messages: List<ApiMessage>,
        tools: List<ToolSpec> = emptyList(),
        thinking: Boolean = false,
    ): Flow<ChatEvent> = callbackFlow {
        val key = endpoint.chatUrl + "|" + endpoint.model
        val always = Thinking.thinksAlways(endpoint.model)
        fun request(off: Boolean) = http.newCall(
            Request.Builder()
                .url(endpoint.chatUrl)
                .header("Authorization", "Bearer ${endpoint.apiKey}")
                .header("Accept", "text/event-stream")
                .post(
                    requestBody(
                        endpoint.model,
                        messages,
                        tools,
                        thinking = if (thinking) true else if (off && !always) false else null,
                        effort = if (off && always) Thinking.LITTLE else null,
                    ).toString().toRequestBody(JSON_TYPE),
                )
                .build(),
        )
        val off = !thinking && (always || Thinking.canSwitchOff(endpoint.model)) && key !in refusesOff
        var call = request(off)

        launch(Dispatchers.IO) {
            try {
                var answer = call.execute()
                if (off && answer.code in REFUSED) {
                    answer.close()
                    refusesOff += key
                    call = request(off = false)
                    answer = call.execute()
                }
                answer.use { response ->
                    if (!response.isSuccessful) {
                        throw ChatException(describeHttpError(response.code, response.body.string()), response.code)
                    }
                    val source = response.body.source()
                    val parser = StreamParser()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.substring(5).trim()
                        if (data.isEmpty()) continue
                        if (data == "[DONE]") break
                        for (event in parser.feed(data)) send(event)
                    }
                    for (event in parser.finish()) send(event)
                    parser.toolCalls().takeIf { it.isNotEmpty() }?.let { send(ChatEvent.ToolCalls(it)) }
                }
                close()
            } catch (e: ChatException) {
                close(e)
            } catch (e: IOException) {
                close(if (call.isCanceled()) e else ChatException(describeNetworkError(e)))
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose { call.cancel() }
    }

    /**
     * Asks for a single token, the same way the chat does. **This is the connection test.**
     *
     * Listing models used to be the test, and that was wrong twice over: a service can chat
     * perfectly well and keep no list at all, and — worse — a wrong address can answer the
     * list politely while being unable to chat. Only the road actually travelled proves
     * anything, so this sends one real message down it.
     *
     * ## ⚠️ 2xx is not success here
     *
     * 智谱's older `/api/paas/v1` and `/v3` answer **HTTP 200** to anything, including a
     * request with no key at all, with `{"code":1001,"msg":"…"}` in the body. An endpoint
     * like that passes every check that only looks at the status line. A reply with no
     * `choices` is a failure however cheerful the status code, and whatever the service
     * said in the body is worth repeating — it is usually the real answer.
     */
    suspend fun probe(endpoint: ApiEndpoint) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint.chatUrl)
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .post(probeBody(endpoint.model).toString().toRequestBody(JSON_TYPE))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw ChatException(describeHttpError(response.code, text), response.code)
                val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: throw ChatException("地址能连上，但回的不是 JSON，多半不是聊天接口。")
                if (root["choices"] == null) throw ChatException(notAChatReply(root))
            }
        } catch (e: IOException) {
            throw ChatException(describeNetworkError(e))
        }
    }

    /**
     * Lists model ids the endpoint serves, to fill the picker.
     *
     * **Not the connection test** — that is [probe]. Plenty of services chat fine and serve
     * no list; failing here means only that there is nothing to show.
     */
    suspend fun models(endpoint: ApiEndpoint): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint.modelsUrl)
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw ChatException(describeHttpError(response.code, text), response.code)
                val root = json.parseToJsonElement(text).jsonObject
                root["data"]?.jsonArray
                    ?.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
                    ?.sorted()
                    // No "check whether it is /v1": 智谱 is /v4 and DeepSeek is /v1, so that
                    // advice was wrong half the time — and following it landed people on an
                    // address that answers 200 to everything, where the real fault (a bad
                    // key) could no longer be reported. Say what is missing, nothing more.
                    ?: throw ChatException("这个地址没有给出模型列表。有的服务就是不提供，模型名直接填也能用。")
            }
        } catch (e: IOException) {
            throw ChatException(describeNetworkError(e))
        }
    }

    private fun describeHttpError(code: Int, body: String): String {
        val detail = runCatching { errorText(json.parseToJsonElement(body).jsonObject["error"]!!) }
            .getOrNull()
            ?: body.take(160)
        val hint = when (code) {
            401, 403 -> "API Key 不对，或者已经失效了"
            402 -> "账户余额不足"
            404 -> "地址或模型名不对（404）"
            429 -> "请求太频繁，或者额度用完了"
            in 500..599 -> "服务那边出错了（$code），过一会儿再试"
            else -> "请求失败（$code）"
        }
        return if (detail.isBlank()) hint else "$hint\n$detail"
    }

    private fun describeNetworkError(e: IOException): String = when {
        e is UnknownHostException -> "找不到服务器：检查地址，或者网络是不是断了"
        e is SocketTimeoutException -> "等太久没有回应，网络可能不太好"
        // Android refuses plain http by default, so the key would never go out unencrypted.
        e.message?.contains("CLEARTEXT", ignoreCase = true) == true -> "只支持 https:// 开头的地址"
        else -> "网络出错：${e.message ?: e.javaClass.simpleName}"
    }

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /** How endpoints turn down a field they don't take: 400, 404 (OpenRouter), 422. */
        val REFUSED = setOf(400, 404, 422)
    }
}

/**
 * Models that think unless told not to, and take being told: DeepSeek V4 (DeepSeek's own
 * OpenClaw plugin sends the same switch) and GLM from 4.5 on. Asked plainly they would think
 * anyway, so a TA with thinking off would still keep the person waiting and cost the tokens.
 */
object Thinking {
    private val SWITCHABLE = listOf("deepseek-v4", "glm-4.5", "glm-4.6", "glm-4.7", "glm-5")

    /**
     * Models that always think: told not to, they fail the request (GLM from 5.3 on: its docs say
     * thinking.type only takes enabled). Asked plainly they think as hard as they can, which is
     * the longest wait there is; they take being asked to think little ([LITTLE]).
     */
    private val ALWAYS = listOf("glm-5.3")

    /** reasoning_effort for a model that can't stop thinking, when the TA isn't to think. */
    const val LITTLE = "low"

    /** By the model's own name, also behind a relay's prefix (deepseek/deepseek-v4-flash). */
    fun canSwitchOff(model: String): Boolean = !thinksAlways(model) && SWITCHABLE.any { name(model).startsWith(it) }

    fun thinksAlways(model: String): Boolean = ALWAYS.any { name(model).startsWith(it) }

    private fun name(model: String) = model.trim().substringAfterLast('/').lowercase()
}

/**
 * The request as the OpenAI-compatible endpoints take it. An assistant turn that called
 * tools goes back with its calls, and with null content when it said nothing (the
 * canonical shape, what the OpenAI SDK itself sends); each result follows as a "tool"
 * message naming its call.
 *
 * [thinking] is the switch DeepSeek and GLM take for thinking before answering: true turns it
 * on, false off, null leaves it out. On, DeepSeek wants every earlier reply to carry its
 * reasoning too: the turn under way sends its own back, earlier turns an empty one. Off, no
 * reasoning goes back at all (as DeepSeek's own plugin does it). [effort]: how hard a model that
 * always thinks should (reasoning_effort); its reasoning goes back as with the switch left out.
 */
/**
 * The smallest thing that still counts as using the service: one word, one token back.
 *
 * Deliberately **not** streamed and deliberately not built by [requestBody] — a test should
 * ask for as little as possible, and should not quietly inherit whatever the real chat path
 * grows later (thinking switches, tools, pictures). A model that cannot answer "hi" in one
 * token is not going to carry a conversation.
 */
internal fun probeBody(model: String): JsonObject = buildJsonObject {
    put("model", model)
    put("stream", false)
    put("max_tokens", 1)
    putJsonArray("messages") {
        addJsonObject {
            put("role", "user")
            put("content", "hi")
        }
    }
}

/**
 * What to say about a 200 that is not a reply.
 *
 * Services disagree about where they put the complaint — 智谱's old endpoints use `msg`,
 * OpenAI-shaped ones nest it under `error`, some just use `message`. Whichever it is, the
 * service's own words beat anything guessed here, so they are passed straight through.
 */
internal fun notAChatReply(root: JsonObject): String {
    val said = (root["msg"] ?: root["message"])?.jsonPrimitive?.contentOrNull
        ?: root["error"]?.let { errorText(it) }
    return if (said.isNullOrBlank()) {
        "地址能连上，但它没有按聊天接口回话。多半是地址填到了别的层级。"
    } else {
        "地址能连上，但它没有按聊天接口回话：$said"
    }
}

internal fun requestBody(
    model: String,
    messages: List<ApiMessage>,
    tools: List<ToolSpec>,
    thinking: Boolean? = null,
    effort: String? = null,
): JsonObject = buildJsonObject {
    put("model", model)
    put("stream", true)
    if (thinking != null) putJsonObject("thinking") { put("type", if (thinking) "enabled" else "disabled") }
    if (effort != null) put("reasoning_effort", effort)
    putJsonArray("messages") {
        for (m in messages) {
            addJsonObject {
                put("role", m.role)
                if (m.images.isNotEmpty()) {
                    // With pictures the content becomes a list of parts, the text first.
                    putJsonArray("content") {
                        if (m.content.isNotEmpty()) {
                            addJsonObject {
                                put("type", "text")
                                put("text", m.content)
                            }
                        }
                        for (url in m.images) {
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", url) }
                            }
                        }
                    }
                } else if (m.toolCalls.isEmpty()) {
                    put("content", m.content)
                } else {
                    if (m.content.isEmpty()) put("content", JsonNull) else put("content", m.content)
                    if (thinking == null) m.reasoning?.let { put("reasoning_content", it) }
                    putJsonArray("tool_calls") {
                        for (c in m.toolCalls) {
                            addJsonObject {
                                put("id", c.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", c.name)
                                    put("arguments", sendableArguments(c.arguments))
                                }
                            }
                        }
                    }
                }
                if (thinking == true && m.role == "assistant") put("reasoning_content", m.reasoning ?: "")
                m.toolCallId?.let { put("tool_call_id", it) }
            }
        }
    }
    if (tools.isNotEmpty()) {
        putJsonArray("tools") {
            for (t in tools) {
                addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parameters)
                    }
                }
            }
        }
    }
}

/**
 * Arguments are echoed back as the model wrote them, unless they are not a JSON object
 * (cut off mid-stream, or "" for no parameters): endpoints that check them would reject
 * the whole request. The tool already told the model its arguments were broken.
 */
private fun sendableArguments(raw: String): String =
    if (raw.isNotBlank() && ToolArgs.parse(raw) != null) raw else "{}"
