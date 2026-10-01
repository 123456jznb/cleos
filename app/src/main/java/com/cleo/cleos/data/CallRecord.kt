package com.cleo.cleos.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A phone call with a TA, as its "call" row keeps it: when the TA picked up and when the call
 * ended. Both are null while it rings, and [endedAt] while it is on. One that ended without
 * [answeredAt] was hung up before the TA said anything.
 */
@Serializable
data class CallRecord(val answeredAt: Long? = null, val endedAt: Long? = null) {
    /** How long the two talked; null while it is on, and for one never answered. */
    val talkedMs: Long?
        get() = if (answeredAt != null && endedAt != null) (endedAt - answeredAt).coerceAtLeast(0) else null
}

object CallRecords {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(record: CallRecord): String = json.encodeToString(record)

    fun decode(raw: String?): CallRecord? =
        if (raw.isNullOrBlank()) null else runCatching { json.decodeFromString<CallRecord>(raw) }.getOrNull()

    /** The way a phone shows a call's length: 03:12, 1:02:45. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    /** How long, the way the TA is told it: 不到一分钟, 3分钟, 1小时5分钟. */
    fun spoken(ms: Long): String {
        val minutes = ((ms + 30_000) / 60_000).toInt()
        return when {
            ms < 60_000 -> "不到一分钟"
            minutes < 60 -> "${minutes}分钟"
            minutes % 60 == 0 -> "${minutes / 60}小时"
            else -> "${minutes / 60}小时${minutes % 60}分钟"
        }
    }
}
