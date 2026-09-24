package com.cleo.cleos.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A memory's details, stored as a JSON array of strings. Unreadable reads as none. */
object MemoryDetails {
    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(String.serializer())

    fun decode(raw: String?): List<String> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(list, raw) }.getOrDefault(emptyList())

    fun encode(details: List<String>): String = json.encodeToString(list, details)
}
