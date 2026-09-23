package com.cleo.cleos.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A picture sent in the chat. [file] is a name inside ImageStore. */
@Serializable
data class MessageImage(val file: String, val width: Int, val height: Int)

object MessageImages {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(images: List<MessageImage>): String? = if (images.isEmpty()) null else json.encodeToString(images)

    fun decode(raw: String?): List<MessageImage> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString<List<MessageImage>>(raw) }.getOrDefault(emptyList())
}
