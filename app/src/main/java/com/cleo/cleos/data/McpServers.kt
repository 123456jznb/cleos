package com.cleo.cleos.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An MCP service the TAs can use, reached over Streamable HTTP. Kept encrypted with the API
 * keys, never in the database or a backup: the address can carry a key (`?key=`), and the
 * token is one.
 */
@Serializable
data class McpServer(
    val id: String,
    val name: String = "",
    val url: String = "",
    /** Sent as `Authorization: Bearer …`. Pasted with the "Bearer " in front works too. */
    val token: String = "",
    /** One more header a service may want, as "Name: value". */
    val header: String = "",
    val enabled: Boolean = true,
    /**
     * Ask the person before each call. Tools the service marks read-only, and ones the person
     * said to always allow, go ahead without asking.
     */
    val askFirst: Boolean = true,
    /** Tools the person said to always allow, by the service's own names for them. */
    val allowed: Set<String> = emptySet(),
) {
    /** What the connection depends on: a change here means a new session and a new tool list. */
    val connection: String get() = listOf(url.trim(), token.trim(), header.trim()).joinToString("\n")
}

/** The MCP services, as one encrypted list. */
class McpServers(private val secrets: SecretStore) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Mutex()

    val all: Flow<List<McpServer>> = secrets.secret(KEY).map(::decode)

    suspend fun current(): List<McpServer> = all.first()

    suspend fun get(id: String): McpServer? = current().firstOrNull { it.id == id }

    /** Adds [server], or replaces the one with its id. */
    suspend fun save(server: McpServer) = edit { list ->
        if (list.any { it.id == server.id }) list.map { if (it.id == server.id) server else it } else list + server
    }

    suspend fun delete(id: String) = edit { list -> list.filterNot { it.id == id } }

    suspend fun setEnabled(id: String, on: Boolean) = edit { list -> list.map { if (it.id == id) it.copy(enabled = on) else it } }

    /** The person said to always allow [tool] of service [id]: no more asking for it. */
    suspend fun allow(id: String, tool: String) = edit { list -> list.map { if (it.id == id) it.copy(allowed = it.allowed + tool) else it } }

    private suspend fun edit(change: (List<McpServer>) -> List<McpServer>) = lock.withLock {
        secrets.setSecret(KEY, json.encodeToString(change(current())))
    }

    private fun decode(raw: String?): List<McpServer> =
        raw?.let { runCatching { json.decodeFromString<List<McpServer>>(it) }.getOrNull() } ?: emptyList()

    private companion object {
        const val KEY = "mcp_servers"
    }
}
