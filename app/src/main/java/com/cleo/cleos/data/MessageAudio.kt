package com.cleo.cleos.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A voice message's recording: a WAV file kept with the pictures in ImageStore's folder, so
 * it goes into backups the same way, and its length.
 */
@Serializable
data class MessageAudio(val file: String, val ms: Long)

object MessageAudios {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(audio: MessageAudio): String = json.encodeToString(audio)

    fun decode(raw: String?): MessageAudio? =
        if (raw.isNullOrBlank()) null else runCatching { json.decodeFromString<MessageAudio>(raw) }.getOrNull()
}
