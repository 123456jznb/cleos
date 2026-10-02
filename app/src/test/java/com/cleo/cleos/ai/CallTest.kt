package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.CallRecord
import com.cleo.cleos.data.CallRecords
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class CallTest {
    // What the TA writes, cut into what is said piece by piece.

    /** [text] streamed a character at a time, the way deltas come, then ended. */
    private fun pieces(text: String): List<String> {
        val cut = Sentences()
        val out = ArrayList<String>()
        for (ch in text) out += cut.add(ch.toString())
        cut.end()?.let { out += it }
        return out
    }

    @Test
    fun piecesEndWhereSentencesDo() {
        assertEquals(listOf("嗯，今天确实挺累的吧。", "早点睡，明天再说。"), pieces("嗯，今天确实挺累的吧。早点睡，明天再说。"))
    }

    @Test
    fun aPieceWithHardlyAnyWordsWaitsForTheNext() {
        assertEquals(listOf("嗯。好。我知道了。"), pieces("嗯。好。我知道了。"))
        // A first piece may end at a comma once it has a few words: it starts the talking sooner.
        assertEquals(listOf("嗯。好的呀，", "我知道了。"), pieces("嗯。好的呀，我知道了。"))
    }

    @Test
    fun theFirstPieceEndsAtItsFirstCommaToStartTalkingSooner() {
        assertEquals(
            listOf("今天下班的时候，", "我路过那家店，买了你爱吃的。"),
            pieces("今天下班的时候，我路过那家店，买了你爱吃的。"),
        )
    }

    @Test
    fun closingQuotesStayWithTheirSentenceAndDecimalsAreNotEnds() {
        assertEquals(listOf("我就说「真的吗？」", "你猜怎么着"), pieces("我就说「真的吗？」你猜怎么着"))
        assertEquals(listOf("大概3.5公里吧。"), pieces("大概3.5公里吧。"))
    }

    @Test
    fun aLongSentenceIsCutAtACommaToo() {
        assertEquals(
            listOf("我知道了。", "我跟你说今天早上出门的时候发现外面下了好大的雨，然后我又回去拿了伞，", "结果还是淋湿了。"),
            pieces("我知道了。我跟你说今天早上出门的时候发现外面下了好大的雨，然后我又回去拿了伞，结果还是淋湿了。"),
        )
    }

    @Test
    fun whatIsSaidHasNoStickersEmojiOrActions() {
        assertEquals("好呀", CallSpeech.clean("（笑）好呀😊 [[sticker:开心]]"))
        assertEquals("早点睡", CallSpeech.clean("*摸摸头* 早点睡"))
        assertEquals("明天20℃，记得带伞", CallSpeech.clean("明天20℃，记得带伞"))
        assertEquals("重点：多喝水", CallSpeech.clean("**重点**：多喝水"))
        assertEquals("", CallSpeech.clean("（抱抱）"))
        // A longer bracket is something said in passing: its words stay.
        assertTrue(CallSpeech.clean("我刚才（其实是在想明天要不要出门）发呆了").contains("其实是在想明天要不要出门"))
    }

    @Test
    fun englishPiecesGetTheirSpaceBack() {
        val said = StringBuilder()
        CallSpeech.join(said, "Hi there.")
        CallSpeech.join(said, "How are you?")
        assertEquals("Hi there. How are you?", said.toString())
        val zh = StringBuilder()
        CallSpeech.join(zh, "好呀。")
        CallSpeech.join(zh, "那就这样。")
        assertEquals("好呀。那就这样。", zh.toString())
    }

    // Where the person's turn ends.

    private val rate = Voice.RATE

    /** [seconds] of a tone at [amp] (0 for none) over noise up to [noise], in 20 ms frames. */
    private fun sound(seconds: Double, amp: Double, noise: Double = 8.0, random: Random = Random(7)): List<ShortArray> {
        val frames = (seconds * 50).toInt()
        var t = 0
        return List(frames) {
            ShortArray(TurnDetector.FRAME) {
                val v = amp * sin(2 * PI * 220 * t++ / rate) + (random.nextDouble() * 2 - 1) * noise
                v.toInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }

    private fun heard(frames: List<ShortArray>): List<TurnDetector.Heard> {
        val d = TurnDetector()
        return frames.mapNotNull { d.feed(it) }
    }

    private fun turns(frames: List<ShortArray>): List<TurnDetector.Ended> = heard(frames).filterIsInstance<TurnDetector.Ended>()

    @Test
    fun aTurnEndsAfterAMomentOfQuietWithItsFirstSyllableKept() {
        val ended = turns(sound(1.0, 0.0) + sound(1.2, 600.0) + sound(2.0, 0.0))
        assertEquals(1, ended.size)
        val seconds = ended[0].pcm.size / rate.toDouble()
        assertTrue("got $seconds s", seconds in 1.2..1.9)
    }

    @Test
    fun aPauseToThinkDoesNotEndIt() {
        val ended = turns(sound(0.5, 0.0) + sound(0.8, 600.0) + sound(0.6, 0.0) + sound(0.8, 600.0) + sound(2.0, 0.0))
        assertEquals(1, ended.size)
        assertTrue(ended[0].pcm.size / rate.toDouble() > 2.2)
    }

    /** [seconds] of something like speech: four syllables a second, dipping between them. */
    private fun syllables(seconds: Double, random: Random = Random(5)): List<ShortArray> {
        var t = 0
        return List((seconds * 50).toInt()) {
            ShortArray(TurnDetector.FRAME) {
                val s = t++ / rate.toDouble()
                val env = Math.pow(kotlin.math.abs(sin(PI * ((s * 4) % 1.0))), 0.7)
                val v = env * 2500 * (sin(2 * PI * 180 * s) + 0.5 * sin(2 * PI * 360 * s)) + (random.nextDouble() * 2 - 1) * 30
                v.toInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }

    @Test
    fun turnAfterTurnTheRoomStaysWhereItIs() {
        // Each turn starts the moment it is the person's again, as when someone answers right away:
        // the room's level used to creep up with every turn until speech no longer stood out.
        val d = TurnDetector()
        repeat(12) { turn ->
            d.reset()
            val ended = (syllables(1.6) + sound(2.0, 0.0)).firstNotNullOfOrNull { d.feed(it) as? TurnDetector.Ended }
            assertTrue("turn $turn not heard", ended != null)
        }
    }

    @Test
    fun aPauseComesFirstWithTheWordsTheEndWillHave() {
        val events = heard(sound(1.0, 0.0) + sound(1.0, 600.0) + sound(2.0, 0.0))
        assertEquals(2, events.size)
        val paused = events[0] as TurnDetector.Paused
        val ended = events[1] as TurnDetector.Ended
        assertTrue(ended.asPaused)
        assertTrue(paused.pcm.contentEquals(ended.pcm))
    }

    @Test
    fun talkingAgainAfterAPauseMakesItsWordsStale() {
        val events = heard(sound(0.5, 0.0) + sound(0.8, 600.0) + sound(0.8, 0.0) + sound(0.8, 600.0) + sound(2.0, 0.0))
        val pauses = events.filterIsInstance<TurnDetector.Paused>()
        val ended = events.last() as TurnDetector.Ended
        assertEquals(2, pauses.size)
        // The first pause's words are only the beginning; the second pause has all of them.
        assertTrue(pauses[0].pcm.size < ended.pcm.size)
        assertTrue(pauses[1].pcm.contentEquals(ended.pcm))
        assertTrue(ended.asPaused)
    }

    @Test
    fun aKnockIsNotATurn() {
        assertTrue(turns(sound(1.0, 0.0) + sound(0.1, 3000.0) + sound(2.0, 0.0)).isEmpty())
    }

    @Test
    fun inANoisyRoomOnlyTheVoiceIsATurnAndItStillEnds() {
        val room = 500.0
        val ended = turns(sound(3.0, 0.0, room) + sound(1.5, 2000.0, room) + sound(2.5, 0.0, room))
        assertEquals(1, ended.size)
        val seconds = ended[0].pcm.size / rate.toDouble()
        assertTrue("got $seconds s", seconds in 1.5..2.5)
    }

    @Test
    fun aRoomThatGetsLouderMidTurnDoesNotKeepItGoing() {
        // A fan comes on just as the person talks: the turn still ends, well before the minute is up.
        val ended = turns(sound(1.0, 0.0) + sound(1.0, 800.0) + sound(12.0, 0.0, noise = 300.0))
        assertEquals(1, ended.size)
        assertTrue(ended[0].pcm.size / rate.toDouble() < 8)
    }

    // A call in what the TA reads.

    private val now = ZonedDateTime.of(2026, 10, 2, 21, 5, 0, 0, ZoneId.of("Asia/Shanghai"))
    private val ta = CompanionEntity(id = 1, name = "", apiBaseUrl = "", apiModel = "", createdAt = 0)
    private var id = 0L

    private fun msg(role: String, content: String, call: Long? = null) =
        MessageEntity(id = ++id, conversationId = 1, role = role, content = content, createdAt = id * 1000, call = call)

    private fun record(answeredAt: Long?, endedAt: Long?) = msg("call", CallRecords.encode(CallRecord(answeredAt, endedAt)))

    @Test
    fun anEndedCallSitsBetweenWhereItBeganAndWhereItEnded() {
        val hi = msg("user", "在吗")
        val call = record(answeredAt = 0, endedAt = 3 * 60_000)
        val history = listOf(hi, call, msg("user", "今天好累", call.id), msg("assistant", "那早点睡吧", call.id), msg("user", "刚才挂得急"))
        val m = Prompt.messages(AppSettings(), ta, history, now)
        assertEquals(listOf("system", "user", "assistant", "user"), m.map { it.role })
        assertTrue(m[1].content.contains(Prompt.CALL_BEGAN))
        assertTrue(m[1].content.contains("今天好累"))
        assertTrue(m[3].content.contains("（电话挂了，打了3分钟）"))
        assertTrue(m[3].content.contains("刚才挂得急"))
        assertFalse(m[3].content.contains(Prompt.CALL_RULE))
    }

    @Test
    fun onTheCallNowTheTaIsToldItIsOnThePhone() {
        val call = record(answeredAt = 0, endedAt = null)
        val history = listOf(call, msg("assistant", "喂？", call.id), msg("user", "在干嘛呢", call.id))
        val m = Prompt.messages(AppSettings(), ta, history, now, call = call.id)
        assertTrue(m.last().content.endsWith(Prompt.CALL_RULE))
        assertTrue(m.last().content.contains("在干嘛呢"))
        assertFalse(m.any { it.content.contains("电话挂了") })
    }

    @Test
    fun pickingUpTheCallIsAnswered() {
        val call = record(answeredAt = null, endedAt = null)
        val history = listOf(msg("user", "在吗"), msg("assistant", "在呀"), call)
        val m = Prompt.messages(AppSettings(), ta, history, now, call = call.id)
        assertEquals("user", m.last().role)
        assertTrue(m.last().content.contains(Prompt.CALL_BEGAN))
        assertTrue(m.last().content.endsWith(Prompt.CALL_RULE))
    }

    @Test
    fun aCallWhereNothingWasSaidIsLeftOut() {
        val call = record(answeredAt = null, endedAt = 5000)
        val m = Prompt.messages(AppSettings(), ta, listOf(msg("user", "在吗"), msg("assistant", "在"), call, msg("user", "打错了")), now)
        assertFalse(m.any { it.content.contains(Prompt.CALL_BEGAN) || it.content.contains("电话挂了") })
    }

    @Test
    fun aCallWhoseBeginningIsOutOfTheWindowStillEnds() {
        val callId = 99L
        val history = listOf(msg("user", "对了", callId), msg("assistant", "嗯，你说", callId), msg("user", "没事了"))
        val m = Prompt.messages(AppSettings(), ta, history, now)
        assertFalse(m.any { it.content.contains(Prompt.CALL_BEGAN) })
        assertTrue(m.last().content.contains("（电话挂了）"))
        // Its row looked up from outside the window: how long it went on, too.
        val known = Prompt.messages(AppSettings(), ta, history, now, calls = mapOf(callId to CallRecord(0, 2 * 60_000)))
        assertTrue(known.last().content.contains("（电话挂了，打了2分钟）"))
    }

    @Test
    fun aRecapKnowsWhatWasSaidOnThePhone() {
        val zone = ZoneId.of("Asia/Shanghai")
        val start = ZonedDateTime.of(2026, 10, 2, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
        val call = MessageEntity(
            id = 1,
            conversationId = 1,
            role = "call",
            content = CallRecords.encode(CallRecord(start, start + 3 * 60_000)),
            createdAt = start,
        )
        val batch = listOf(
            call,
            MessageEntity(id = 2, conversationId = 1, role = "user", content = "今天好累", createdAt = start + 10_000, call = 1),
            MessageEntity(id = 3, conversationId = 1, role = "assistant", content = "那早点睡吧", createdAt = start + 70_000, call = 1),
        )
        assertEquals(
            "10月2日\n21:00 （对方打来电话，打了3分钟）\n21:00 对方（电话里）：今天好累\n21:01 我（电话里）：那早点睡吧",
            Recap.transcript(batch, zone),
        )
    }

    @Test
    fun aCallsLengthReadsTheWayPhonesShowIt() {
        assertEquals("03:12", CallRecords.clock(192_000))
        assertEquals("1:02:45", CallRecords.clock(3_765_000))
        assertEquals("不到一分钟", CallRecords.spoken(20_000))
        assertEquals("3分钟", CallRecords.spoken(185_000))
        assertEquals("1小时", CallRecords.spoken(3_600_000))
        assertEquals("1小时5分钟", CallRecords.spoken(3_900_000))
        assertNull(CallRecord(answeredAt = null, endedAt = 10).talkedMs)
        assertEquals(60_000L, CallRecord(answeredAt = 0, endedAt = 60_000).talkedMs)
    }
}
