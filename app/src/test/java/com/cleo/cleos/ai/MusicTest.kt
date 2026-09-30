package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.decodeTools
import com.cleo.cleos.data.encodeTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class MusicTest {
    private val lrc = """
        [ti:晴天]
        [ar:周杰伦]
        [00:00.00] 晴天 - 周杰伦
        [00:00.50] 作词 : 周杰伦
        [00:01.00]作曲：周杰伦
        [00:29.37]故事的小黄花
        [00:32.80]从出生那年就飘着
        [01:10.5][02:40.123]刮风这天 我试过握着你手
        [03:00.00]
    """.trimIndent()

    private val sunny = NowPlaying("com.netease.cloudmusic", "晴天", "周杰伦", "叶惠美", 269_000, 83_000, at = 1_000, playing = true)
    private val seven = NowPlaying("com.netease.cloudmusic", "七里香", "周杰伦", "七里香", 299_000, 0, at = 1_000, playing = true)

    @Test
    fun timedWordsKeepWhatIsSungAndLeaveOutTheCredits() {
        val lines = Lrc.parse(lrc)
        assertEquals(
            listOf(
                LyricLine(29_370, "故事的小黄花"),
                LyricLine(32_800, "从出生那年就飘着"),
                LyricLine(70_500, "刮风这天 我试过握着你手"),
                LyricLine(160_123, "刮风这天 我试过握着你手"),
                LyricLine(180_000, ""),
            ),
            lines,
        )
        assertEquals(-1, Lrc.at(lines, 10_000))
        assertEquals(0, Lrc.at(lines, 29_370))
        assertEquals(1, Lrc.at(lines, 50_000))
        assertEquals(4, Lrc.at(lines, 200_000))
        assertEquals(emptyList<LyricLine>(), Lrc.parse("没有时间的歌词\n只有字"))
    }

    @Test
    fun theWordsAreTheRecordingOfTheSameLength() {
        val hits = listOf(
            LyricsHit("晴天", "周杰伦", 299.0, "[00:01.00]a", false),
            LyricsHit("晴天", "周杰伦", 270.0, "[00:01.00]b", false),
            // Words without times are no use for "唱到哪句".
            LyricsHit("晴天", "周杰伦", 269.5, null, false),
            LyricsHit("晴天", "周杰伦", 249.0, "[00:01.00]c", false),
        )
        assertEquals(270.0, LyricsPick.best(hits, 269_800)!!.durationS, 0.0)
        // A live take three minutes long is another recording.
        assertNull(LyricsPick.best(hits, 180_000))
        // Not knowing how long it is, the first with times.
        assertEquals(299.0, LyricsPick.best(hits, 0)!!.durationS, 0.0)
        // The same recording twice: the words in simplified characters win, even a little further off in length.
        val both = listOf(
            LyricsHit("七里香", "周杰伦", 299.0, "[00:27.38]窗外的麻雀 在電線桿上多嘴", false),
            LyricsHit("七里香", "周杰伦", 298.0, "[00:27.38]窗外的麻雀 在电线杆上多嘴", false),
        )
        assertEquals(298.0, LyricsPick.best(both, 299_000)!!.durationS, 0.0)
        // None within three seconds: one within eight is most likely the same song cut a little differently,
        // but only when nothing is closer.
        val cut = LyricsHit("HOTSHOT", "YSB Tril", 137.0, "[00:10.00]x", false)
        assertEquals(137.0, LyricsPick.best(listOf(cut), 131_500)!!.durationS, 0.0)
        assertEquals(133.0, LyricsPick.best(listOf(cut, cut.copy(durationS = 133.0)), 131_500)!!.durationS, 0.0)
        assertNull(LyricsPick.best(listOf(cut), 125_000))
        // A player's "(Explicit)" is not part of the name LRCLIB knows the song by.
        assertEquals("HOTSHOT", LyricsPick.bareTitle("HOTSHOT (Explicit)"))
        // Nothing to sing is an answer too.
        assertTrue(LyricsPick.best(listOf(LyricsHit("Intro", "x", 60.0, null, true)), 60_000)!!.instrumental)
        assertEquals("周杰伦", LyricsPick.firstArtist("周杰伦/费玉清"))
        assertEquals("Taylor Swift", LyricsPick.firstArtist("Taylor Swift feat. Ed Sheeran"))
        assertEquals("陈奕迅", LyricsPick.firstArtist("陈奕迅、王菲"))
        assertEquals("晴天", LyricsPick.bareTitle("晴天 (Live)"))
        assertEquals("光年之外", LyricsPick.bareTitle("光年之外（电影《太空旅客》中文主题曲）"))
    }

    @Test
    fun theTaIsToldWhatPlaysHowFarInAndTheLineBeingSung() {
        val words = Lrc.parse(lrc)
        assertEquals(
            "（你们正在一起听《晴天》—周杰伦，放到 1:23 / 4:29，这会儿唱到：「刮风这天 我试过握着你手」）",
            MusicText.listening(sunny, words, 83_000),
        )
        assertEquals("（你们正在一起听《晴天》—周杰伦，放到 0:10 / 4:29，还在前奏）", MusicText.listening(sunny, words, 10_000))
        assertTrue(MusicText.listening(sunny, words, 200_000).endsWith("这会儿没在唱）"))
        // Words not found, and a player that doesn't say how long the song is.
        assertEquals("（你们正在一起听《晴天》—周杰伦，放到 1:23）", MusicText.listening(sunny.copy(durationMs = 0), null, 83_000))
        assertEquals("（你们正在一起听《晴天》，放到 1:23 / 4:29）", MusicText.listening(sunny.copy(artist = ""), emptyList(), 83_000))
    }

    @Test
    fun thePositionRunsOnFromWhereThePlayerLastSaidItWas() {
        assertEquals(86_000, sunny.position(4_000))
        assertEquals(83_000, sunny.copy(playing = false).position(4_000))
        assertEquals(89_000, sunny.copy(speed = 2f).position(4_000))
        assertEquals(269_000, sunny.position(1_000_000))
    }

    @Test
    fun musicControlSwitchesSongsAndSaysWhatPlaysNow() = runBlocking {
        val phone = object : MusicSource {
            var current: NowPlaying? = sunny
            var access = true
            override fun allowed() = access
            override fun now() = current

            override fun control(action: MusicAction) {
                current = when (action) {
                    MusicAction.Next -> seven
                    MusicAction.Pause -> current?.copy(playing = false)
                    else -> current
                }
            }
        }
        val box = ToolBox(unused(), unused(), unused(), music = phone)
        val on = AppSettings(tools = setOf(ToolGroup.Music))
        val next = box.run(ToolCall("a", "music_control", """{"action":"next"}"""), on)
        assertEquals("切到下一首：《七里香》—周杰伦", next.note)
        assertTrue(next.result.contains("现在放的是《七里香》—周杰伦"))
        assertEquals("暂停了音乐", box.run(ToolCall("b", "music_control", """{"action":"暂停"}"""), on).note)
        assertEquals("切歌没成：没说要怎么切", box.run(ToolCall("c", "music_control", """{"action":"louder"}"""), on).note)
        // Off by default: it needs a permission, asked for when it is turned on.
        assertFalse(ToolGroup.Music in AppSettings().tools)
        assertEquals("切歌没成：设置里关着", box.run(ToolCall("d", "music_control", """{"action":"next"}"""), AppSettings()).note)
        phone.current = null
        assertEquals("切歌没成：没在放歌", box.run(ToolCall("e", "music_control", """{"action":"play"}"""), on).note)
        phone.access = false
        val closed = box.run(ToolCall("f", "music_control", """{"action":"play"}"""), on)
        assertEquals("切歌没成：没开通知使用权", closed.note)
        assertTrue(closed.result.contains("一起听歌"))
        assertEquals(MusicAction.Previous, MusicText.action("上一首"))
        assertEquals(MusicAction.Play, MusicText.action(" PLAY "))
        // The new switch keeps its place among the others when settings are saved and read back.
        assertEquals(setOf(ToolGroup.Music, ToolGroup.Todos), decodeTools(encodeTools(setOf(ToolGroup.Music, ToolGroup.Todos))))
    }

    private inline fun <reified T> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> error("not used") } as T
}
