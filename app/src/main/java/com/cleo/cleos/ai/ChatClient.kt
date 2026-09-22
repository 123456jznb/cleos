package com.cleo.cleos.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class ApiMessage(val role: String, val content: String)

/** Where to send a conversation. [baseUrl] may or may not already end in /chat/completions. */
data class ApiEndpoint(val baseUrl: String, val apiKey: String, val model: String) {
    val chatUrl: String
        get() = baseUrl.trim().trimEnd('/').let { if (it.endsWith("/chat/completions")) it else "$it/chat/completions" }
    val modelsUrl: String
        get() = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions") + "/models"
}

sealed interface ChatEvent {
    data class Delta(val text: String) : ChatEvent
    /** Reasoning models stream their thinking separately; shown only as "thinking…". */
    data object Thinking : ChatEvent
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

    fun stream(endpoint: ApiEndpoint, messages: List<ApiMessage>): Flow<ChatEvent> = callbackFlow {
        val body = buildJsonObject {
            put("model", endpoint.model)
            put("stream", true)
            putJsonArray("messages") {
                messages.forEach { m ->
                    addJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    }
                }
            }
        }
        val request = Request.Builder()
            .url(endpoint.chatUrl)
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val call = http.newCall(request)

        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw ChatException(describeHttpError(response.code, response.body.string()), response.code)
                    }
                    val source = response.body.source()
                    var thinkingSent = false
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.substring(5).trim()
                        if (data.isEmpty()) continue
                        if (data == "[DONE]") break
                        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                        obj["error"]?.let { throw ChatException("服务端报错：" + errorText(it)) }
                        val delta = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta") as? JsonObject
                            ?: continue
                        val reasoning = (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
                        if (!reasoning.isNullOrEmpty() && !thinkingSent) {
                            send(ChatEvent.Thinking)
                            thinkingSent = true
                        }
                        val content = (delta["content"] as? JsonPrimitive)?.contentOrNull
                        if (!content.isNullOrEmpty()) send(ChatEvent.Delta(content))
                    }
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

    private fun errorText(e: JsonElement): String =
        (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e.toString()

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
