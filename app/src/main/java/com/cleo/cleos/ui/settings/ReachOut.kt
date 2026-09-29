package com.cleo.cleos.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ai.LaterRules
import com.cleo.cleos.ai.RoutineRules
import com.cleo.cleos.data.db.WakeEntity
import com.cleo.cleos.glass.LocalGlassPalette
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Under the "reach out" switch: whether notifications can get through at all, what the TA has
 * waiting (how many and when, never what: that is for later), what the day's greetings go by
 * (when the person's days usually start and end), and what came of the last wake.
 * That last line is the one to read when "it never says anything on its own" comes up: it tells
 * whether the phone woke it, whether it decided not to, or whether the request failed.
 */
@Composable
internal fun ReachOutStatus(vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    val context = LocalContext.current
    val wake by vm.lastWake.collectAsStateWithLifecycle()
    val waiting by vm.waiting.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(vm.notificationsAllowed()) }
    // Back from the system settings, or from the permission dialog.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = vm.notificationsAllowed() }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = vm.notificationsAllowed() }
    val now = ZonedDateTime.now()
    fun at(ms: Long) = LaterRules.at(ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()), now)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!allowed) {
            Text("通知没开：TA 找你的话会在聊天里，但手机不会提醒你。", color = palette.error, fontSize = 12.sp, lineHeight = 17.sp)
            Chip("去开通知", selected = false) {
                // Asked in the app once; after that only the system settings can change it.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !vm.settings.value.notificationsAsked) {
                    vm.markNotificationsAsked()
                    ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
        waiting.firstOrNull()?.let { first ->
            Text(
                "现在记着 ${waiting.size} 件事，最早的一件大约${at(first.dueAt)} 到时候。",
                color = palette.contentSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        vm.habits?.let { h ->
            Text(RoutineRules.describe(h), color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        wake?.let { w ->
            Text("上次：${at(w.at)}，${describe(w)}", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Text(
            "有的手机会把后台任务往后拖，到时候晚一些才想起来，过了时候就不说了。想准一点，在系统设置里给 Cleos 开自启动、允许后台运行。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

private fun describe(w: WakeEntity): String {
    val why = w.detail.trim()
    return when (w.outcome) {
        WakeEntity.SENT -> "说了：$why"
        WakeEntity.SKIPPED -> "想了想没说" + (if (why.isNotEmpty()) "（$why）" else "")
        WakeEntity.EXPIRED -> "没说：$why"
        WakeEntity.HELD -> "先没说：$why"
        else -> "没成：$why"
    }
}
