package com.cleo.cleos.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns the `data:` payloads of one streamed chat completion into events, and collects
 * the tool calls it makes along the way. Kept apart from the network for testing.
 *
 * Tool calls arrive in fragments: the first piece of each call carries its id and name,
 * the rest carry slices of the arguments' JSON, and `index` says which call a fragment
 * belongs to. Providers bend this. Some send a whole call in one piece, some send the
 * arguments as an object instead of a string, and some leave `index` out, in which case
 * a new id means a new call and a piece without an id continues the last one.
 */
internal class StreamParser {
    private val json = Json { ignoreUnknownKeys = true }

    private class Pending(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())

    private val calls = sortedMapOf<Int, Pending>()

    fun feed(data: String): List<ChatEvent> {
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return emptyList()
        obj["error"]?.let { throw ChatException("服务端报错：" + errorText(it)) }
        val delta = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta") as? JsonObject
            ?: return emptyList()
        val events = ArrayList<ChatEvent>(2)
        (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?.let { events += ChatEvent.Reasoning(it) }
        (delta["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?.let { events += ChatEvent.Delta(it) }
        (delta["tool_calls"] as? JsonArray)?.forEach { piece -> (piece as? JsonObject)?.let(::addPiece) }
        return events
    }

    /** The calls, in order, once the stream is over. Pieces that never got a name are dropped. */
    fun toolCalls(): List<ToolCall> = calls.values
        .filter { it.name.isNotEmpty() }
        .mapIndexed { i, p -> ToolCall(p.id.ifEmpty { "call_$i" }, p.name, p.arguments.toString()) }

    private fun addPiece(piece: JsonObject) {
        val id = (piece["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val index = (piece["index"] as? JsonPrimitive)?.intOrNull
        val pending = when {
            index != null -> calls.getOrPut(index) { Pending() }
            id.isNotEmpty() -> calls.values.firstOrNull { it.id == id }
                ?: Pending().also { calls[(calls.keys.maxOrNull() ?: -1) + 1] = it }
            else -> calls.values.lastOrNull() ?: Pending().also { calls[0] = it }
        }
        if (pending.id.isEmpty() && id.isNotEmpty()) pending.id = id
        val function = piece["function"] as? JsonObject ?: return
        val name = (function["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (pending.name.isEmpty() && name.isNotEmpty()) pending.name = name
        when (val args = function["arguments"]) {
            is JsonObject -> pending.arguments.append(args.toString())
            is JsonPrimitive -> args.contentOrNull?.let { pending.arguments.append(it) }
            else -> Unit
        }
    }
}

internal fun errorText(e: JsonElement): String =
    (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e.toString()
