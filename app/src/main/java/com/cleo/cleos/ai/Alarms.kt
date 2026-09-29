package com.cleo.cleos.ai

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Calendar

/**
 * Alarms and timers go to the phone's own clock app, not to anything Cleos keeps: it rings through
 * silent mode and do-not-disturb the way the person set it up, it can be snoozed and switched off
 * where they switch off every other alarm, and no background job of ours has to be alive for it.
 * What that costs: the clock app takes an hour and a minute (the next time it comes round) or
 * weekdays to repeat on, not a date; anything further off goes in the calendar with a reminder.
 */
interface AlarmSource {
    /** [days]: [Calendar.SUNDAY]..[Calendar.SATURDAY] to repeat on; empty rings once. Throws [ToolFailure]. */
    fun setAlarm(hour: Int, minute: Int, label: String, days: List<Int>)

    /** Throws [ToolFailure]. */
    fun setTimer(seconds: Int, label: String)
}

object AlarmText {
    /** "7:05", "07:05", "7点05", "7点半", "19：30". */
    fun time(s: String): LocalTime {
        val m = Regex("^\\s*(\\d{1,2})\\s*[:：点]\\s*(\\d{1,2}|半)?\\s*(分)?\\s*$").find(s)
        val hour = m?.groupValues?.get(1)?.toIntOrNull()
        val minute = m?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.let { if (it == "半") 30 else it.toIntOrNull() } ?: 0
        if (hour == null || hour !in 0..23 || minute !in 0..59) {
            throw ToolFailure("时间「$s」看不懂，写成 24 小时的 HH:MM，比如 07:30、19:00。", "时间没写对")
        }
        return LocalTime.of(hour, minute)
    }

    private val weekdays = mapOf('一' to DayOfWeek.MONDAY, '二' to DayOfWeek.TUESDAY, '三' to DayOfWeek.WEDNESDAY, '四' to DayOfWeek.THURSDAY,
        '五' to DayOfWeek.FRIDAY, '六' to DayOfWeek.SATURDAY, '日' to DayOfWeek.SUNDAY, '天' to DayOfWeek.SUNDAY)

    /**
     * Which days an alarm repeats on, as the clock app wants them ([Calendar] constants, Sunday
     * first). "每天", "工作日", "周末", "一三五", "周一到周五", "1,3,5" (1 is Monday). Blank or
     * "none": once.
     */
    fun days(s: String?): List<Int> {
        val t = s?.trim().orEmpty()
        if (t.isEmpty() || ToolArgs.isNone(t) || t == "不重复" || t == "一次") return emptyList()
        val picked: Set<DayOfWeek> = when {
            "每天" in t || t == "天天" -> DayOfWeek.entries.toSet()
            "工作日" in t -> setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
            "周末" in t -> setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            Regex("[到至~-]").containsMatchIn(t) && t.count { it in weekdays } == 2 -> {
                val (a, b) = t.filter { it in weekdays }.map { weekdays.getValue(it) }
                if (a.value <= b.value) (a.value..b.value).map { DayOfWeek.of(it) }.toSet()
                else ((a.value..7) + (1..b.value)).map { DayOfWeek.of(it) }.toSet()
            }
            t.any { it in weekdays } -> t.filter { it in weekdays }.map { weekdays.getValue(it) }.toSet()
            else -> t.split(',', '，', '、', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.map { DayOfWeek.of(it) }.toSet()
        }
        if (picked.isEmpty()) throw ToolFailure("重复的日子「$t」看不懂：写「每天」「工作日」「周末」，或者「一三五」。", "重复日子没写对")
        // DayOfWeek goes Monday = 1 .. Sunday = 7; Calendar goes Sunday = 1 .. Saturday = 7.
        return picked.map { it.value % 7 + 1 }.sorted()
    }

    /** "每天", "工作日", "周一、三、五". */
    fun repeatName(days: List<Int>): String {
        val set = days.toSet()
        return when (set) {
            (1..7).toSet() -> "每天"
            setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY) -> "工作日"
            setOf(Calendar.SATURDAY, Calendar.SUNDAY) -> "周末"
            else -> "周" + listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)
                .filter { it in set }.joinToString("、") { "一二三四五六日"[(it + 5) % 7].toString() }
        }
    }

    /** When a one-off alarm at [time] rings: today if that is still ahead, else tomorrow. */
    fun rings(time: LocalTime, now: ZonedDateTime): ZonedDateTime {
        val today = now.with(time).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** "10 分钟", "1 小时 30 分钟", "45 秒". */
    fun span(seconds: Int): String = when {
        seconds < 60 -> "$seconds 秒"
        seconds % 60 != 0 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> LaterRules.span(seconds / 60L)
    }
}

/**
 * The clock app, asked through the intents Android defines for it ([AlarmClock]), with its own
 * screen skipped where it allows. [onScreen]: whether Cleos is in front. Android won't let an app
 * in the background open another app's screen, and the clock app is one, so a reply finishing
 * after the person has left, or a TA waking on its own, can't set anything: it says so instead of
 * claiming it did.
 */
class PhoneClock(private val context: Context, private val onScreen: () -> Boolean) : AlarmSource {
    override fun setAlarm(hour: Int, minute: Int, label: String, days: List<Int>) {
        open(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply {
                    if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
                },
        )
    }

    override fun setTimer(seconds: Int, label: String) {
        open(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply { if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) },
        )
    }

    /** The next alarm on the phone, anyone's; null when there is none, or the phone won't say. */
    fun nextAlarm(): Long? = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime

    private fun open(intent: Intent) {
        if (!onScreen()) {
            throw ToolFailure(
                "Cleos 现在不在屏幕上，手机不让在后台打开时钟，这次没设上。照实告诉对方，等对方回到聊天再设，或者请对方自己设一下。",
                "App 不在前台",
            )
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            throw ToolFailure("这台手机上没有能接这个的时钟 App，设不了。照实告诉对方，请对方自己设。", "手机上没有时钟 App")
        } catch (_: SecurityException) {
            throw ToolFailure("时钟 App 没让设，照实告诉对方，请对方自己设。", "时钟 App 不让设")
        }
    }
}
