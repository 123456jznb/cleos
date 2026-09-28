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

    /** Reasoning models stream their thinking separately. Shown only as "在想". */
    data class Reasoning(val text: String) : ChatEvent

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

    /** [thinking]: ask the model to think first (see [requestBody]). */
    fun stream(
        endpoint: ApiEndpoint,
        messages: List<ApiMessage>,
        tools: List<ToolSpec> = emptyList(),
        thinking: Boolean = false,
    ): Flow<ChatEvent> = callbackFlow {
        val request = Request.Builder()
            .url(endpoint.chatUrl)
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .header("Accept", "text/event-stream")
            .post(requestBody(endpoint.model, messages, tools, thinking).toString().toRequestBody(JSON_TYPE))
            .build()
        val call = http.newCall(request)

        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
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

    /** Lists model ids the endpoint serves. Doubles as "is the address and key right". */
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
                    ?: throw ChatException("地址能连上，但返回的不是模型列表。检查地址是不是填到了 /v1 这一层。")
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
    }
}

/**
 * The request as the OpenAI-compatible endpoints take it. An assistant turn that called
 * tools goes back with its calls, and with null content when it said nothing (the
 * canonical shape, what the OpenAI SDK itself sends); each result follows as a "tool"
 * message naming its call.
 *
 * With [thinking], the switch DeepSeek and GLM take for thinking before answering. DeepSeek
 * then wants every earlier reply to carry its reasoning too: the turn under way sends its
 * own back, earlier turns an empty one.
 */
internal fun requestBody(model: String, messages: List<ApiMessage>, tools: List<ToolSpec>, thinking: Boolean = false): JsonObject = buildJsonObject {
    put("model", model)
    put("stream", true)
    if (thinking) putJsonObject("thinking") { put("type", "enabled") }
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
                    if (!thinking) m.reasoning?.let { put("reasoning_content", it) }
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
                if (thinking && m.role == "assistant") put("reasoning_content", m.reasoning ?: "")
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
