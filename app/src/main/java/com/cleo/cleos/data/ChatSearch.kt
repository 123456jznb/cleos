package com.cleo.cleos.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Finding what was said: the query as SQL takes it, and a result cut down to show. */
object ChatSearch {
    /** Results shown at most: the newest, which are the ones usually looked for. */
    const val LIMIT = 200

    /** [query] as a LIKE pattern that matches it anywhere: its own % and _ taken literally. */
    fun pattern(query: String): String =
        "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /** A piece of a result and where the query is in it. */
    data class Snippet(val text: String, val hits: List<IntRange>)

    /**
     * [text] on one line, cut to about [width] characters so the first place [query] is found
     * shows with a little of what comes before it; … where it was cut. Every place the query
     * is in the piece is given, to be lit up.
     */
    fun snippet(text: String, query: String, width: Int = 60, before: Int = 14): Snippet {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val q = query.trim()
        val at = if (q.isEmpty()) -1 else flat.indexOf(q, ignoreCase = true)
        var start = if (at < 0) 0 else (at - before).coerceAtLeast(0)
        val end = (start + width).coerceAtMost(flat.length)
        // Near the end of a long message: take more of what came before instead.
        if (end - start < width) start = (end - width).coerceAtLeast(0)
        val piece = (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
        val hits = ArrayList<IntRange>()
        if (q.isNotEmpty()) {
            var i = piece.indexOf(q, ignoreCase = true)
            while (i >= 0) {
                hits += i until i + q.length
                i = piece.indexOf(q, i + q.length, ignoreCase = true)
            }
        }
        return Snippet(piece, hits)
    }

    /** The days in [times], as the phone's clock has them. */
    fun days(times: List<Long>, zone: ZoneId): Set<LocalDate> =
        times.mapTo(HashSet()) { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
}
