package com.cleo.cleos.ui.chat

import com.cleo.cleos.ai.ToolCallCodec
import com.cleo.cleos.data.db.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** One tool call as the chat shows it when tapped: what was asked of the tool, and what it answered. */
internal data class ToolDetail(val name: String, val arguments: String, val result: String)

internal object ToolDetails {
    private val pretty = Json { prettyPrint = true }

    /**
     * The call a "tool" row answers: its name and arguments are on the assistant turn that made it,
     * the nearest one before the row (some providers number their calls per turn, so an id alone
     * can match an older turn). The result is the row's own content, as the model saw it. A call
     * that can't be found (its turn was deleted) still shows the result.
     */
    fun find(messages: List<MessageEntity>, row: MessageEntity): ToolDetail {
        val at = messages.indexOfFirst { it.id == row.id }
        val id = row.toolCallId
        val call = if (id == null || at < 0) null else (at - 1 downTo 0).firstNotNullOfOrNull { i ->
            val m = messages[i]
            if (m.role != "assistant" || m.toolCalls.isNullOrBlank()) null else ToolCallCodec.decode(m.toolCalls).firstOrNull { it.id == id }
        }
        return ToolDetail(call?.name ?: "工具", call?.let { prettyJson(it.arguments) }.orEmpty(), row.content)
    }

    /** Arguments are raw JSON text and may not parse: then as they came. */
    fun prettyJson(raw: String): String =
        runCatching { pretty.encodeToString(JsonElement.serializer(), pretty.parseToJsonElement(raw)) }.getOrDefault(raw)
}
