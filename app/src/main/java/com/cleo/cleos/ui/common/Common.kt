package com.cleo.cleos.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cleo.cleos.AppContainer
import com.cleo.cleos.CleosApp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as CleosApp).container

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

object Dates {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val monthDay = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private val yearMonthDay = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
    private val hourMinute = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val weekday = DateTimeFormatter.ofPattern("EEEE", Locale.CHINA)
    private val yearMonth = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA)

    fun today(): LocalDate = LocalDate.now(zone)
    fun dateOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    fun time(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(zone).format(hourMinute)

    /** "今天 21:04", "昨天 08:10", "9月3日 14:00", "2025年12月1日 14:00". */
    fun chatStamp(epochMillis: Long): String {
        val date = dateOf(epochMillis)
        val today = today()
        val day = when {
            date == today -> "今天"
            date == today.minusDays(1) -> "昨天"
            date.year == today.year -> date.format(monthDay)
            else -> date.format(yearMonthDay)
        }
        return "$day ${time(epochMillis)}"
    }

    fun weekday(date: LocalDate): String = date.format(weekday)
    fun monthDay(date: LocalDate): String = date.format(monthDay)
    fun yearMonth(date: LocalDate): String = date.format(yearMonth)
    fun full(date: LocalDate): String =
        (if (date.year == today().year) date.format(monthDay) else date.format(yearMonthDay)) + " " + weekday(date)

    /** "今天" / "明天" / "昨天" / "周五" (within a week) / "9月30日". */
    fun due(date: LocalDate): String {
        val diff = ChronoUnit.DAYS.between(today(), date)
        return when (diff) {
            0L -> "今天"
            1L -> "明天"
            2L -> "后天"
            -1L -> "昨天"
            in 3L..6L -> date.format(weekday).replace("星期", "周")
            else -> if (date.year == today().year) date.format(monthDay) else date.format(yearMonthDay)
        }
    }
}
