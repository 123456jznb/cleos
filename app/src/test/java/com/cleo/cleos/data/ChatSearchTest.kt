package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ChatSearchTest {
    @Test
    fun theQueryIsTakenLiterally() {
        assertEquals("%海边%", ChatSearch.pattern(" 海边 "))
        // % and _ mean themselves, not "anything".
        assertEquals("%100\\%%", ChatSearch.pattern("100%"))
        assertEquals("%a\\_b%", ChatSearch.pattern("a_b"))
        assertEquals("%c:\\\\x%", ChatSearch.pattern("c:\\x"))
    }

    @Test
    fun aResultShowsTheWordsAroundWhatWasFound() {
        val long = "今天下午去了图书馆，找了一本关于海边小镇的书，" + "翻着翻着就困了，".repeat(6) + "梦见了海。"
        val s = ChatSearch.snippet(long, "海边", width = 30, before = 6)
        assertTrue(s.text.startsWith("…"))
        assertTrue(s.text.endsWith("…"))
        assertEquals(listOf("海边"), s.hits.map { s.text.substring(it) })
        // Near the end: more of what came before, nothing cut after.
        val end = ChatSearch.snippet(long, "梦见了海", width = 20)
        assertTrue(end.text.endsWith("梦见了海。"))
        assertEquals(20 + 1, end.text.length)
        // Every place it is in the piece, whatever the case; lines become one.
        val all = ChatSearch.snippet("Hi hi\nHI", "hi")
        assertEquals("Hi hi HI", all.text)
        assertEquals(3, all.hits.size)
        // Short text stays whole.
        assertEquals("早", ChatSearch.snippet("早", "早").text)
    }

    @Test
    fun daysAreThePhonesDays() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        // 2026-09-27 23:30 and 2026-09-28 00:30 in Shanghai: two days, not one.
        val late = LocalDate.of(2026, 9, 27).atTime(23, 30).atZone(shanghai).toInstant().toEpochMilli()
        val early = late + 60 * 60_000L
        assertEquals(setOf(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 28)), ChatSearch.days(listOf(late, early, late), shanghai))
    }
}
