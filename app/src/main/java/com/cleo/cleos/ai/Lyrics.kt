package com.cleo.cleos.ai

import android.icu.text.Transliterator
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One line of a song's words and when it starts. An empty [text] is a stretch with no singing. */
data class LyricLine(val atMs: Long, val text: String)

/**
 * Timed lyrics in LRC ("[01:23.45]words"), the way LRCLIB and most players keep them. What
 * isn't sung is left out: the credits at the start ("作词 : …", "Composer: …") and the title line
 * some files open with. Shown as "唱到哪句" during the intro, they would read as the words.
 */
object Lrc {
    private val tag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val credit = Regex(
        """^(作词|作曲|编曲|词|曲|制作人?|音乐制作|监制|混音|混缩|母带|和声|配唱|录音|吉他|贝斯|鼓|键盘|弦乐|出品|发行|企划|统筹|OP|SP|""" +
            """Lyrics?|Lyricist|Written|Composer|Composed|Producer|Produced|Arranger|Arranged|Mix(?:ed|ing)?|Master(?:ed|ing)?)""" +
            """(?:\s*by)?\s*[:：]""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            var rest = raw.trim()
            val times = ArrayList<Long>(1)
            // A line can carry several times: a chorus written once, sung at each of them.
            while (true) {
                val m = tag.find(rest)?.takeIf { it.range.first == 0 } ?: break
                val (min, sec, frac) = m.destructured
                val ms = when (frac.length) {
                    0 -> 0
                    1 -> frac.toInt() * 100
                    2 -> frac.toInt() * 10
                    else -> frac.toInt()
                }
                times += min.toLong() * 60_000 + sec.toLong() * 1000 + ms
                rest = rest.substring(m.range.last + 1).trim()
            }
            if (times.isEmpty()) continue
            if (credit.containsMatchIn(rest)) continue
            times.forEach { out += LyricLine(it, rest) }
        }
        out.sortBy { it.atMs }
        // "光年之外 - G.E.M. 邓紫棋" at the very start is the title, not the first line sung.
        if (out.firstOrNull()?.let { it.atMs < TITLE_WITHIN_MS && " - " in it.text } == true) out.removeAt(0)
        return out
    }

    /** The line being sung at [positionMs]: the last one begun by then; -1 before the first. */
    fun at(lines: List<LyricLine>, positionMs: Long): Int {
        var found = -1
        for (i in lines.indices) {
            if (lines[i].atMs <= positionMs) found = i else break
        }
        return found
    }

    private const val TITLE_WITHIN_MS = 1_500L
}

/** A song as LRCLIB has it: [synced] is its LRC, null when only plain words (or none) are known. */
data class LyricsHit(val track: String, val artist: String, val durationS: Double, val synced: String?, val instrumental: Boolean)

object LyricsPick {
    /** How far the recording on LRCLIB may be from the one playing and still be the same one. */
    const val SAME_WITHIN_MS = 3_000L

    /**
     * With none that close, how far one may be and still be taken: most likely the same song cut a
     * little differently, its lines then a second or so off. Nearer than that, a radio edit and the
     * album version.
     */
    const val NEAR_WITHIN_MS = 8_000L

    /**
     * The one that is the song playing: within [SAME_WITHIN_MS] of its length, else [NEAR_WITHIN_MS],
     * since a live take or another cut has its words at other times (with no length to go by, any
     * with times). The same recording is often there several times over, in simplified and in
     * traditional characters (七里香 came back traditional first): simplified goes first, as Cleos
     * is read in it, then the closest in length.
     */
    fun best(hits: List<LyricsHit>, durationMs: Long): LyricsHit? {
        fun off(h: LyricsHit) = if (durationMs > 0) kotlin.math.abs(h.durationS * 1000 - durationMs) else 0.0
        val order = compareBy<LyricsHit> { traditional(it.synced) }.thenBy { off(it) }
        if (durationMs <= 0) return hits.filter { it.synced != null }.minWithOrNull(order)
        val usable = hits.filter { it.synced != null || it.instrumental }
        for (within in listOf(SAME_WITHIN_MS, NEAR_WITHIN_MS)) {
            usable.filter { off(it) <= within }.minWithOrNull(order)?.let { return it }
        }
        return null
    }

    /** How many characters of [text] are written only in traditional characters (among the common ones). */
    fun traditional(text: String?): Int = text?.count { it in TRADITIONAL } ?: 0

    private const val TRADITIONAL = "們這說個為來時會對將與過還裡後發見讓從愛聽風夢無麼開關東車門長義難雙當學樣認話讀寫謝請問間題點實經頭邊誰樂聲憶淚戀轉陽鐘錯隨雲電線紙鉛滋瞭尋豔麗飛節頁熱獨歡離遠燈歲給記妳臉現覺氣"

    /** "周杰伦/费玉清" → "周杰伦": LRCLIB lists artists every which way; the first finds it. */
    fun firstArtist(artist: String): String =
        artist.split('/', '、', ',', '，', '&', ';').first().split(" feat", " ft.", " Feat", " FT").first().trim()

    /** "HOTSHOT (Explicit)" → "HOTSHOT": what is searched for; a version in brackets is told apart by its length. */
    fun bareTitle(title: String): String = title.replace(Regex("""\s*[(（\[【].*?[)）\]】]"""), "").trim().ifEmpty { title }
}

/**
 * The words of what is playing, from LRCLIB (lrclib.net): free and open, no key, and it answers
 * from mainland China without a proxy (tried: 晴天, 平凡之路, 孤勇者 all came back timed). Asked
 * once a song; a song it doesn't have is remembered as having none. [agent] names the app, as
 * LRCLIB asks every client to.
 *
 * A lookup, once begun, runs to its end in [scope] whoever stops waiting for it. A reply waits
 * only a few seconds; on the phone the first lookup took longer than that, was called off with the
 * reply, and was never kept, so every message after it came without the words too.
 */
class Lyrics(http: OkHttpClient, private val agent: String, private val scope: CoroutineScope) {
    private val http = http.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val found = object : LinkedHashMap<String, List<LyricLine>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LyricLine>>?) = size > KEPT
    }
    private val asking = HashMap<String, Deferred<List<LyricLine>?>>()
    private val json = Json { ignoreUnknownKeys = true }

    /** Empty when there are no words to be had (or none sung); null when LRCLIB couldn't be reached. */
    suspend fun of(title: String, artist: String, durationMs: Long): List<LyricLine>? {
        if (title.isBlank()) return emptyList()
        val key = "$title|$artist|${durationMs / 1000}"
        // The chat's bar and a reply asking for the same song wait on the same lookup.
        val lookup = synchronized(this) {
            found[key]?.let { return it }
            asking.getOrPut(key) {
                scope.async(Dispatchers.IO) {
                    val lines = fetch(title, artist, durationMs)
                    synchronized(this@Lyrics) {
                        if (lines != null) found[key] = lines
                        asking.remove(key)
                    }
                    lines
                }
            }
        }
        return lookup.await()
    }

    /** Never throws: a lookup that fails is null, and asked again next time. */
    private fun fetch(title: String, artist: String, durationMs: Long): List<LyricLine>? {
        val started = System.currentTimeMillis()
        return try {
            val first = LyricsPick.firstArtist(artist)
            val bare = LyricsPick.bareTitle(title)
            // By title and artist first: one answer, and the right one, most of the time.
            val byName = search { addQueryParameter("track_name", bare).apply { if (first.isNotEmpty()) addQueryParameter("artist_name", first) } }
            var hit = LyricsPick.best(byName, durationMs)
            var asked = 1
            if (hit == null) {
                hit = LyricsPick.best(search { addQueryParameter("q", "$bare $first".trim()) }, durationMs)
                asked++
            }
            val lines = hit?.synced?.let(Lrc::parse) ?: emptyList()
            Log.i(TAG, (if (lines.isEmpty()) "none" else "${lines.size} lines") + " after $asked asked, ${System.currentTimeMillis() - started} ms")
            // In the characters the player writes the song in: some songs LRCLIB has only in
            // traditional ones (every copy of 七里香 the right length is), shown beside a title in simplified.
            val convert = LyricsPick.traditional(title + artist) == 0 && lines.any { LyricsPick.traditional(it.text) > 0 }
            if (convert) lines.map { it.copy(text = simplified(it.text)) } else lines
        } catch (e: Exception) {
            Log.w(TAG, "unreachable after ${System.currentTimeMillis() - started} ms: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /** The phone's own converter (ICU, there since Android 10); where it isn't, the words as they came. */
    private val toSimplified by lazy { runCatching { Transliterator.getInstance("Traditional-Simplified") }.getOrNull() }

    /** 妳 is left as it is by ICU, being a character of its own; written in simplified, it is 你. */
    private fun simplified(text: String): String =
        (toSimplified?.let { t -> runCatching { t.transliterate(text) }.getOrNull() } ?: text).replace('妳', '你')

    private fun search(params: HttpUrl.Builder.() -> HttpUrl.Builder): List<LyricsHit> {
        val url = HttpUrl.Builder().scheme("https").host(HOST).addPathSegments("api/search").params().build()
        val request = Request.Builder().url(url).header("User-Agent", agent).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("LRCLIB ${response.code}")
            val body = response.body.string()
            val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
            return array.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun text(name: String) = (o[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                LyricsHit(
                    track = text("trackName").orEmpty(),
                    artist = text("artistName").orEmpty(),
                    durationS = (o["duration"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                    synced = text("syncedLyrics")?.takeIf { it.isNotBlank() },
                    instrumental = (o["instrumental"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
        }
    }

    private companion object {
        const val TAG = "Lyrics"
        const val HOST = "lrclib.net"
        const val KEPT = 60
    }
}
