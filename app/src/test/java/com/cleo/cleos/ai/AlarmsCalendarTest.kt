package com.cleo.cleos.ai

import com.cleo.cleos.data.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class AlarmsCalendarTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    // 2026-09-29 is a Tuesday.
    private val now = ZonedDateTime.of(2026, 9, 29, 22, 10, 0, 0, zone)
    private val today = now.toLocalDate()

    private fun at(month: Int, day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun utcMidnight(month: Int, day: Int) = LocalDate.of(2026, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun alarmTimesAreReadTheWaysPeopleWriteThem() {
        assertEquals(LocalTime.of(7, 30), AlarmText.time("07:30"))
        assertEquals(LocalTime.of(7, 5), AlarmText.time("7:05"))
        assertEquals(LocalTime.of(19, 30), AlarmText.time("19：30"))
        assertEquals(LocalTime.of(7, 0), AlarmText.time("7点"))
        assertEquals(LocalTime.of(7, 30), AlarmText.time("7点半"))
        val bad = runCatching { AlarmText.time("25:00") }.exceptionOrNull() as ToolFailure
        assertTrue(bad.result.contains("HH:MM"))
    }

    @Test
    fun repeatDaysGoToTheClockAppSundayFirst() {
        // The clock app counts days as Calendar does: Sunday 1, Monday 2 … Saturday 7.
        assertEquals(emptyList<Int>(), AlarmText.days(null))
        assertEquals(emptyList<Int>(), AlarmText.days("none"))
        assertEquals((1..7).toList(), AlarmText.days("每天"))
        assertEquals(listOf(2, 3, 4, 5, 6), AlarmText.days("工作日"))
        assertEquals(listOf(1, 7), AlarmText.days("周末"))
        assertEquals(listOf(2, 4, 6), AlarmText.days("一三五"))
        assertEquals(listOf(2, 4, 6), AlarmText.days("1,3,5"))
        assertEquals(listOf(2, 3, 4, 5, 6), AlarmText.days("周一到周五"))
        assertEquals(listOf(1, 2, 3), AlarmText.days("周日到周二"))
        assertEquals("工作日", AlarmText.repeatName(AlarmText.days("工作日")))
        assertEquals("周一、三、五", AlarmText.repeatName(listOf(2, 4, 6)))
    }

    @Test
    fun aOneOffAlarmRingsTheNextTimeItsHourComesRound() {
        assertEquals(now.withHour(23).withMinute(0), AlarmText.rings(LocalTime.of(23, 0), now))
        assertEquals(now.plusDays(1).withHour(7).withMinute(0), AlarmText.rings(LocalTime.of(7, 0), now))
        assertEquals("45 秒", AlarmText.span(45))
        assertEquals("1 分 30 秒", AlarmText.span(90))
        assertEquals("10 分钟", AlarmText.span(600))
        assertEquals("1 小时 30 分钟", AlarmText.span(5400))
    }

    @Test
    fun alarmsAndTimersSayWhenTheyRing() = runBlocking {
        val set = mutableListOf<String>()
        val phone = object : AlarmSource {
            override fun setAlarm(hour: Int, minute: Int, label: String, days: List<Int>) {
                set += "$hour:$minute $label $days"
            }

            override fun setTimer(seconds: Int, label: String) {
                set += "timer $seconds $label"
            }
        }
        val box = ToolBox(unused(), unused(), unused(), alarms = phone, clock = { now.toInstant().toEpochMilli() }, zone = { zone })
        // On from the start: nothing to ask for.
        val on = AppSettings()
        assertTrue(ToolGroup.Alarm in on.tools)
        assertEquals("定了闹钟：明天 07:00「起床」", box.run(ToolCall("a", "set_alarm", """{"time":"7:00","label":"起床"}"""), on).note)
        assertEquals("定了闹钟：工作日 07:30", box.run(ToolCall("b", "set_alarm", """{"time":"07:30","repeat":"工作日"}"""), on).note)
        assertEquals("开了计时器：30 秒「面」", box.run(ToolCall("c", "set_timer", """{"minutes":0.5,"label":"面"}"""), on).note)
        assertEquals(listOf("7:0 起床 []", "7:30  [2, 3, 4, 5, 6]", "timer 30 面"), set)
        // The phone refusing (Cleos in the background, no clock app) is said, not claimed.
        val away = ToolBox(
            unused(), unused(), unused(),
            alarms = object : AlarmSource {
                override fun setAlarm(hour: Int, minute: Int, label: String, days: List<Int>) = throw ToolFailure("Cleos 现在不在屏幕上。", "App 不在前台")

                override fun setTimer(seconds: Int, label: String) = throw ToolFailure("Cleos 现在不在屏幕上。", "App 不在前台")
            },
        )
        assertEquals("定闹钟没成：App 不在前台", away.run(ToolCall("d", "set_alarm", """{"time":"7:00"}"""), on).note)
    }

    @Test
    fun calendarTimesAreStoredTheWayCalendarsKeepThem() {
        val w = CalendarText.parse("2026-10-03 15:00", today)
        assertEquals(LocalDate.of(2026, 10, 3), w.day)
        assertEquals(LocalTime.of(15, 0), w.time)
        assertTrue(CalendarText.parse("2026-10-03", today).allDay)
        assertEquals(LocalTime.of(9, 5), CalendarText.parse("10-3T9:05", today).time)
        // Without an end, an hour.
        val (b, e) = CalendarText.span(w, null, zone)
        assertEquals(at(10, 3, 15, 0), b)
        assertEquals(3_600_000L, e - b)
        // A whole day is midnight to midnight in UTC, whatever the phone's zone.
        assertEquals(utcMidnight(10, 3) to utcMidnight(10, 4), CalendarText.span(CalendarText.parse("2026-10-03", today), null, zone))
        assertEquals(utcMidnight(10, 1) to utcMidnight(10, 4), CalendarText.span(CalendarText.parse("2026-10-01", today), CalendarText.parse("2026-10-03", today), zone))
        // An end before the start goes back, not swapped.
        assertTrue(runCatching { CalendarText.span(w, CalendarText.parse("2026-10-03 14:00", today), zone) }.exceptionOrNull() is ToolFailure)
    }

    @Test
    fun eventsReadWithWhenWhatWhereAndWhose() {
        val mine = CalEvent(12, "取快递", at(9, 30, 15, 0), at(9, 30, 16, 0), false, "菜鸟驿站", calendar = "Cleos", ours = true)
        val theirs = CalEvent(3, "组会", at(10, 5, 14, 0), at(10, 5, 15, 30), false, calendar = "工作")
        assertEquals("#12 明天 15:00–16:00 取快递（地点：菜鸟驿站） [你加的]", CalendarText.line(mine, zone, today))
        assertEquals("#3 10月5日 周一 14:00–15:30 组会 [对方的：工作]", CalendarText.line(theirs, zone, today))
        val trip = CalEvent(7, "出差", utcMidnight(10, 3), utcMidnight(10, 6), true, calendar = "个人")
        assertEquals("10月3日 周六–10月5日 周一 全天", CalendarText.whenText(trip, zone, today))
        // Two days off is 后天, the way people say it.
        assertEquals("后天 全天", CalendarText.whenText(trip.copy(begin = utcMidnight(10, 1), end = utcMidnight(10, 2)), zone, today))
        assertEquals("今天 日历上没有安排。", CalendarText.list(emptyList(), today, today, zone, today))
    }

    @Test
    fun onlyWhatTheTaAddedCanBeChanged() = runBlocking {
        val added = mutableListOf<EventDraft>()
        val deleted = mutableListOf<Long>()
        val theirs = CalEvent(2, "组会", at(10, 5, 14, 0), at(10, 5, 15, 30), false, calendar = "工作")
        val calendar = object : CalendarSource {
            override suspend fun events(from: Long, to: Long) = listOf(theirs).filter { it.begin in from until to }

            override suspend fun event(id: Long) = if (id == 2L) theirs else null

            override suspend fun add(draft: EventDraft): Long {
                added += draft
                return 40
            }

            override suspend fun update(id: Long, draft: EventDraft) = error("not reached")

            override suspend fun delete(id: Long) {
                deleted += id
            }
        }
        val box = ToolBox(unused(), unused(), unused(), calendar = calendar, clock = { now.toInstant().toEpochMilli() }, zone = { zone })
        // Off until turned on (it asks for the permission then).
        assertFalse(ToolGroup.Calendar in AppSettings().tools)
        val on = AppSettings().let { it.copy(tools = it.tools + ToolGroup.Calendar) }

        val add = box.run(ToolCall("a", "add_event", """{"title":"取快递","start":"2026-09-30 15:00","remind":0}"""), on)
        assertEquals("加了日程「取快递」· 明天 15:00–16:00，到点提醒", add.note)
        assertEquals(EventDraft("取快递", at(9, 30, 15, 0), at(9, 30, 16, 0), false, null, null, 0), added.single())

        val read = box.run(ToolCall("b", "read_calendar", """{"days":10}"""), on)
        assertEquals("看了日历：今天到10月8日 周四", read.note)
        assertTrue(read.result.contains("#2 10月5日 周一 14:00–15:30 组会 [对方的：工作]"))

        // The person's own: read, never changed.
        val refused = box.run(ToolCall("c", "delete_event", """{"id":2}"""), on)
        assertEquals("删日程没成：那条是对方自己的", refused.note)
        assertTrue(refused.result.contains("只能看"))
        assertEquals("改日程没成：那条是对方自己的", box.run(ToolCall("d", "update_event", """{"id":2,"title":"x"}"""), on).note)
        assertTrue(deleted.isEmpty())
    }

    /** A DAO or source these tools never touch. */
    private inline fun <reified T> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> error("not used") } as T
}
