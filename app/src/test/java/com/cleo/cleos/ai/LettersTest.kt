package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.DiaryBlock
import com.cleo.cleos.data.DiaryBlocks
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.data.db.LetterEntity
import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.ui.letters.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class LettersTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val hour = Duration.ofHours(1).toMillis()
    private val day = Duration.ofDays(1).toMillis()

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun local(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone)

    private fun ta(created: Long, lastTry: Long? = null) =
        CompanionEntity(id = 1, name = "沐", apiBaseUrl = "", apiModel = "", createdAt = created, lastLetterTry = lastTry)

    private fun letter(id: Long, author: String, text: String, at: Long, deliverAt: Long?, readAt: Long? = null, replyTo: Long? = null) =
        LetterEntity(id = id, companionId = 1, author = author, content = text, createdAt = at, deliverAt = deliverAt, readAt = readAt, replyTo = replyTo)

    @Test
    fun aReplyComesHoursLaterButNeverInTheNight() {
        val afternoon = at(2026, 9, 24, 14)
        for (seed in 1L..200L) {
            val t = LetterTiming.replyAt(afternoon, seed, zone)
            assertTrue("1 to 6 hours ($seed)", t - afternoon in hour..6 * hour)
        }
        // Sent at 22:30: whatever falls after 23:00 comes the next morning, between 8 and 9.
        val late = at(2026, 9, 24, 22, 30)
        for (seed in 1L..200L) {
            val t = local(LetterTiming.replyAt(late, seed, zone))
            assertTrue("$seed: $t", t.toLocalDate() == LocalDate.of(2026, 9, 25) && t.toLocalTime() >= LocalTime.of(8, 0) && t.toLocalTime() < LocalTime.of(9, 0))
        }
        // Written after midnight: the same morning.
        val t = local(LetterTiming.ownAt(at(2026, 9, 25, 1), 7, zone))
        assertEquals(LocalDate.of(2026, 9, 25), t.toLocalDate())
        assertTrue(t.toLocalTime() >= LocalTime.of(8, 0))
    }

    @Test
    fun theTimeIsTheSameEveryTimeItIsWorkedOut() {
        // A reply written late (the phone was offline) still arrives when it was due.
        val sent = at(2026, 9, 24, 10)
        assertEquals(LetterTiming.replyAt(sent, 42, zone), LetterTiming.replyAt(sent, 42, zone))
        val own = LetterTiming.ownAt(sent, 9, zone)
        assertTrue(own - sent in Duration.ofMinutes(25).toMillis()..Duration.ofMinutes(100).toMillis())
    }

    @Test
    fun thePersonSetsHowLongAReplyTakes() {
        val afternoon = at(2026, 9, 24, 14)
        for (seed in 1L..200L) {
            val t = LetterTiming.replyAt(afternoon, seed, zone, minMinutes = 5, maxMinutes = 15)
            assertTrue("5 to 15 minutes ($seed)", t - afternoon in Duration.ofMinutes(5).toMillis()..Duration.ofMinutes(15).toMillis())
        }
        assertEquals("as soon as it is written", afternoon, LetterTiming.replyAt(afternoon, 3, zone, minMinutes = 0, maxMinutes = 0))
        // Out of order, or below zero (a hand-edited backup): still a time, not an exception.
        assertEquals(afternoon + hour, LetterTiming.replyAt(afternoon, 3, zone, minMinutes = 60, maxMinutes = 10))
        assertEquals(afternoon, LetterTiming.replyAt(afternoon, 3, zone, minMinutes = -5, maxMinutes = -5))
        // The quiet night is on unless turned off; off, a letter may come in the night.
        val late = at(2026, 9, 24, 23, 30)
        val pushed = local(LetterTiming.replyAt(late, 3, zone, minMinutes = 0, maxMinutes = 0))
        assertTrue("$pushed", pushed.toLocalDate() == LocalDate.of(2026, 9, 25) && pushed.toLocalTime() >= LocalTime.of(8, 0))
        assertEquals(late, LetterTiming.replyAt(late, 3, zone, minMinutes = 0, maxMinutes = 0, quietNight = false))
        val own = LetterTiming.ownAt(late, 7, zone, quietNight = false)
        assertTrue(own - late in Duration.ofMinutes(25).toMillis()..Duration.ofMinutes(100).toMillis())
    }

    @Test
    fun theWaitReadsTheWayItIsSet() {
        assertEquals("回信过 1～6 小时到", LetterTiming.describeReply(60, 360))
        assertEquals("回信一写好就到", LetterTiming.describeReply(0, 0))
        assertEquals("回信 15 分钟以内到", LetterTiming.describeReply(0, 15))
        assertEquals("回信一天以内到", LetterTiming.describeReply(0, 1440))
        assertEquals("回信过 5～15 分钟到", LetterTiming.describeReply(5, 15))
        assertEquals("回信过 30 分钟～2 小时到", LetterTiming.describeReply(30, 120))
        assertEquals("回信过 12 小时～一天到", LetterTiming.describeReply(720, 1440))
        assertEquals("回信过 3 小时到", LetterTiming.describeReply(180, 180))
        assertEquals("回信过一天到", LetterTiming.describeReply(1440, 1440))
        // The slider puts what it is given on the nearest step.
        assertEquals(LetterTiming.REPLY_STEPS.indexOf(60), LetterTiming.stepOf(60))
        assertEquals(LetterTiming.REPLY_STEPS.indexOf(360), LetterTiming.stepOf(400))
        assertEquals(LetterTiming.REPLY_STEPS.lastIndex, LetterTiming.stepOf(5000))
    }

    @Test
    fun theGapBetweenOwnLettersIsThePersonsToSet() {
        val now = at(2026, 9, 24, 20)
        val old = ta(created = now - 30 * day)
        val twoDaysAgo = letter(1, LetterEntity.AUTHOR_AI, "前天的信", now - 2 * day, now - 2 * day, readAt = now - day)
        assertFalse("five days unless changed", LetterRules.ready(now, old, listOf(twoDaysAgo), 20, 0))
        assertTrue(LetterRules.ready(now, old, listOf(twoDaysAgo), 20, 0, cooldown = Duration.ofDays(1)))
        assertFalse("still only with something to say", LetterRules.ready(now, old, listOf(twoDaysAgo), 0, 0, cooldown = Duration.ofDays(1)))
    }

    @Test
    fun aTaWritesOnlyAfterAWhileAndWithSomethingToSay() {
        val now = at(2026, 9, 24, 20)
        val old = ta(created = now - 30 * day)
        assertTrue(LetterRules.ready(now, old, emptyList(), saidSince = 20, diarySince = 0))
        assertTrue("a diary entry is enough", LetterRules.ready(now, old, emptyList(), saidSince = 0, diarySince = 1))
        assertFalse("nothing to write about", LetterRules.ready(now, old, emptyList(), saidSince = 19, diarySince = 0))
        assertFalse("a TA made two days ago waits", LetterRules.ready(now, ta(created = now - 2 * day), emptyList(), 50, 3))
        assertFalse("tried four days ago, wrote nothing", LetterRules.ready(now, ta(now - 30 * day, lastTry = now - 4 * day), emptyList(), 50, 3))
        val lastWeek = letter(1, LetterEntity.AUTHOR_AI, "上周的信", now - 7 * day, now - 7 * day, readAt = now - 6 * day)
        assertTrue(LetterRules.ready(now, old, listOf(lastWeek), 20, 0))
        assertFalse("one still unread", LetterRules.ready(now, old, listOf(lastWeek.copy(readAt = null)), 50, 3))
        assertFalse("one still on its way", LetterRules.ready(now, old, listOf(lastWeek.copy(deliverAt = now + hour, readAt = null)), 50, 3))
        assertFalse("written three days ago", LetterRules.ready(now, old, listOf(lastWeek.copy(createdAt = now - 3 * day)), 50, 3))
        // Material counts from the last letter written, not from the last try.
        assertEquals(now - 7 * day, LetterRules.materialSince(old.copy(lastLetterTry = now - day), listOf(lastWeek)))
        assertEquals(old.createdAt, LetterRules.materialSince(old, emptyList()))
    }

    @Test
    fun onlyALetterOfItsOwnMayBeSkipped() {
        val settings = AppSettings(userName = "小雨")
        val t = ta(0).copy(persona = "说话简短。")
        val own = LetterPrompt.system(settings, t, own = true)
        val reply = LetterPrompt.system(settings, t, own = false)
        assertTrue(own.startsWith("你叫沐。\n\n对方叫小雨。\n\n说话简短。"))
        assertTrue(own.contains("不要套「亲爱的某某」"))
        assertTrue(own.contains("写信时不用照着"))
        assertTrue(own.contains(LetterPrompt.SKIP))
        assertFalse("someone who wrote gets an answer", reply.contains(LetterPrompt.SKIP))
        assertTrue(LetterPrompt.isSkip("SKIP"))
        assertTrue(LetterPrompt.isSkip("skip（这几天没什么可写的）"))
        assertFalse(LetterPrompt.isSkip("今天我想写点什么。" + "字".repeat(30) + "skip"))
    }

    @Test
    fun theMaterialIsTheLettersTheTalkAndTheDiary() {
        val now = local(at(2026, 9, 24, 20))
        val sent = letter(3, LetterEntity.AUTHOR_ME, "最近睡得不好", at(2026, 9, 24, 9), at(2026, 9, 24, 9))
        val letters = listOf(
            letter(1, LetterEntity.AUTHOR_ME, "第一封", at(2026, 9, 10, 9), at(2026, 9, 10, 9)),
            letter(2, LetterEntity.AUTHOR_AI, "回第一封", at(2026, 9, 10, 12), at(2026, 9, 10, 14), readAt = at(2026, 9, 10, 15), replyTo = 1),
            sent,
            letter(4, LetterEntity.AUTHOR_ME, "草稿不算", at(2026, 9, 24, 19), null),
        )
        var n = 0L
        fun said(role: String, text: String) = MessageEntity(id = ++n, conversationId = 1, role = role, content = text, createdAt = at(2026, 9, 23, 20) + n)
        fun entry(author: String, text: String, secret: Boolean = false) = DiaryEntryEntity(
            day = LocalDate.of(2026, 9, 22).toEpochDay(),
            title = "",
            blocks = DiaryBlocks.encode(listOf(DiaryBlock.Text(text))),
            createdAt = 1,
            updatedAt = 1,
            author = author,
            secret = secret,
            companionId = if (author == DiaryEntryEntity.AUTHOR_AI) 1 else null,
        )
        val m = LetterPrompt.material(
            now = now,
            letters = letters,
            replyTo = sent,
            said = listOf(said("assistant", "那早点睡"), said("user", "睡不着")).reversed(),
            diary = listOf(entry(DiaryEntryEntity.AUTHOR_AI, "今天下雨"), entry(DiaryEntryEntity.AUTHOR_ME, "去了河边"), entry(DiaryEntryEntity.AUTHOR_ME, "不能说的", secret = true)),
            zone = zone,
        )
        assertTrue(m.startsWith("现在是2026年9月24日"))
        assertTrue(m.indexOf("【对方写的 · 9月10日】\n第一封") < m.indexOf("【你写的 · 9月10日】\n回第一封"))
        assertFalse("the letter being answered is shown once, as the one to answer", m.substringBefore("对方刚寄来").contains("最近睡得不好"))
        assertTrue(m.contains("对方刚寄来这封信（9月24日）：\n最近睡得不好"))
        assertFalse(m.contains("草稿不算"))
        assertTrue("oldest first", m.indexOf("你：那早点睡") < m.indexOf("对方：睡不着"))
        assertTrue(m.contains("你上一封信之后写的日记：\n- 9月22日 今天下雨"))
        assertTrue(m.contains("对方上一封信之后写的日记：\n- 9月22日 去了河边"))
        assertFalse(m.contains("不能说的"))
        assertTrue(m.endsWith("给对方写一封回信。"))
    }

    @Test
    fun theMailboxShowsWhatHasArrived() {
        val now = at(2026, 9, 24, 20)
        val sent = letter(1, LetterEntity.AUTHOR_ME, "寄出的", now - 2 * hour, now - 2 * hour)
        val draft = letter(2, LetterEntity.AUTHOR_ME, "草稿", now - hour, null)
        val coming = letter(3, LetterEntity.AUTHOR_AI, "路上的回信", now - hour, now + 2 * hour, replyTo = 1)
        assertEquals(listOf(2L, 1L), Mailbox.shown(listOf(sent, draft, coming), now).map { it.id })
        assertTrue(Mailbox.replyOnTheWay(listOf(sent, draft, coming), now))
        assertEquals(0, Mailbox.unread(listOf(sent, draft, coming), now))
        // Three hours on, the reply is in: unread, and nothing is on its way any more.
        val later = now + 3 * hour
        assertEquals(listOf(3L, 2L, 1L), Mailbox.shown(listOf(sent, draft, coming), later).map { it.id })
        assertEquals(1, Mailbox.unread(listOf(sent, draft, coming), later))
        assertFalse(Mailbox.replyOnTheWay(listOf(sent, draft, coming), later))
        assertFalse("a draft waits for no reply", Mailbox.replyOnTheWay(listOf(draft), later))
    }
}
