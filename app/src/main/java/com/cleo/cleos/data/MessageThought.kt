package com.cleo.cleos.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the TA thought before a reply, as the model streamed it, and for how long. It is there
 * for the person to read: it never goes back to the model, nor into a recap.
 */
@Serializable
data class MessageThought(val text: String, val ms: Long)

object MessageThoughts {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(thought: MessageThought): String = json.encodeToString(thought)

    fun decode(raw: String?): MessageThought? =
        if (raw.isNullOrBlank()) null else runCatching { json.decodeFromString<MessageThought>(raw) }.getOrNull()
}
