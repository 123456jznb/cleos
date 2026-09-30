package com.cleo.cleos.ai

import android.icu.text.Transliterator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
     * The one that is the song playing: within [SAME_WITHIN_MS] of its length, since a live take
     * or another cut has its words at other times (with no length to go by, any with times). The
     * same recording is often there several times over, in simplified and in traditional
     * characters (七里香 came back traditional first): simplified goes first, as Cleos is read
     * in it, then the closest in length.
     */
    fun best(hits: List<LyricsHit>, durationMs: Long): LyricsHit? {
        fun off(h: LyricsHit) = if (durationMs > 0) kotlin.math.abs(h.durationS * 1000 - durationMs) else 0.0
        val same = hits.filter { h ->
            if (durationMs > 0) (h.synced != null || h.instrumental) && off(h) <= SAME_WITHIN_MS else h.synced != null
        }
        return same.minWithOrNull(compareBy<LyricsHit> { traditional(it.synced) }.thenBy { off(it) })
    }

    /** How many characters of [text] are written only in traditional characters (among the common ones). */
    fun traditional(text: String?): Int = text?.count { it in TRADITIONAL } ?: 0

    private const val TRADITIONAL = "們這說個為來時會對將與過還裡後發見讓從愛聽風夢無麼開關東車門長義難雙當學樣認話讀寫謝請問間題點實經頭邊誰樂聲憶淚戀轉陽鐘錯隨雲電線紙鉛滋瞭尋豔麗飛節頁熱獨歡離遠燈歲給記妳臉現覺氣"

    /** "周杰伦/费玉清" → "周杰伦": LRCLIB lists artists every which way; the first finds it. */
    fun firstArtist(artist: String): String =
        artist.split('/', '、', ',', '，', '&', ';').first().split(" feat", " ft.", " Feat", " FT").first().trim()

    /** "晴天 (Live)" → "晴天": what a second try searches for. */
    fun bareTitle(title: String): String = title.replace(Regex("""\s*[(（\[【].*?[)）\]】]"""), "").trim().ifEmpty { title }
}

/**
 * The words of what is playing, from LRCLIB (lrclib.net): free and open, no key, and it answers
 * from mainland China without a proxy (tried: 晴天, 平凡之路, 孤勇者 all came back timed). Asked
 * once a song; a song it doesn't have is remembered as having none. [agent] names the app, as
 * LRCLIB asks every client to.
 */
class Lyrics(private val http: OkHttpClient, private val agent: String) {
    private val lock = Mutex()
    private val found = object : LinkedHashMap<String, List<LyricLine>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LyricLine>>?) = size > KEPT
    }
    private val json = Json { ignoreUnknownKeys = true }

    /** Empty when there are no words to be had (or none sung); null when LRCLIB couldn't be reached. */
    suspend fun of(title: String, artist: String, durationMs: Long): List<LyricLine>? {
        if (title.isBlank()) return emptyList()
        val key = "$title|$artist|${durationMs / 1000}"
        // One at a time: the chat's bar and a reply asking for the same song find it here the second time.
        return lock.withLock {
            found[key]?.let { return@withLock it }
            val lines = fetch(title, artist, durationMs) ?: return@withLock null
            found[key] = lines
            lines
        }
    }

    private suspend fun fetch(title: String, artist: String, durationMs: Long): List<LyricLine>? = withContext(Dispatchers.IO) {
        try {
            val first = LyricsPick.firstArtist(artist)
            val exact = search { addQueryParameter("track_name", title).apply { if (first.isNotEmpty()) addQueryParameter("artist_name", first) } }
            val hit = LyricsPick.best(exact, durationMs)
                ?: LyricsPick.best(search { addQueryParameter("q", "${LyricsPick.bareTitle(title)} $first".trim()) }, durationMs)
            val lines = hit?.synced?.let(Lrc::parse) ?: emptyList()
            // In the characters the player writes the song in: some songs LRCLIB has only in
            // traditional ones (every copy of 七里香 the right length is), shown beside a title in simplified.
            val convert = LyricsPick.traditional(title + artist) == 0 && lines.any { LyricsPick.traditional(it.text) > 0 }
            if (convert) lines.map { it.copy(text = simplified(it.text)) } else lines
        } catch (_: IOException) {
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
        const val HOST = "lrclib.net"
        const val KEPT = 60
    }
}
