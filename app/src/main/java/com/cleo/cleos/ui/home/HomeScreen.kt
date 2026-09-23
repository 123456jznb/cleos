package com.cleo.cleos.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.AppContainer
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.avatarLetter
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private enum class Who { Me, Ai }

/**
 * The two of them: their pictures and names, and how long they have known each other.
 * The wording stays out of saying what they are to each other; the app doesn't decide that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTab(bottomInset: Dp, onOpenSettings: () -> Unit) {
    val c = appContainer()
    val palette = LocalGlassPalette.current
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)
    val firstSaid by remember { c.db.messages().observeFirstSaid() }.collectAsStateWithLifecycle(null)
    val said by remember { c.db.messages().observeSaidCount() }.collectAsStateWithLifecycle(0)
    val diaries by remember { c.db.diary().observeCount() }.collectAsStateWithLifecycle(0)
    val done by remember { c.db.todos().observeDoneCount() }.collectAsStateWithLifecycle(0)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    var pickingFor by remember { mutableStateOf<Who?>(null) }
    var cropping by remember { mutableStateOf<Pair<Who, Uri>?>(null) }
    var menuFor by remember { mutableStateOf<Who?>(null) }
    var naming by remember { mutableStateOf<Who?>(null) }
    var dating by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val who = pickingFor
        pickingFor = null
        if (uri != null && who != null) cropping = who to uri
    }
    fun pick(who: Who) {
        pickingFor = who
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = "主页",
                backdrop = page,
                trailing = { GlassIconButton(Icons.Rounded.Settings, "设置", onOpenSettings, page) },
            )
        },
    ) {
        val s = settings ?: return@GlassPage
        val me = s.userName.trim().ifEmpty { "我" }
        val ai = s.aiName.trim().ifEmpty { "TA" }
        val since = s.knownSince?.let(LocalDate::ofEpochDay) ?: firstSaid?.let(Dates::dateOf)
        Column(
            Modifier
                .fillMaxSize()
                .fadeUnderTopBar(statusTop + TopBarHeight, bottom = bottomInset - 10.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = statusTop + TopBarHeight + 8.dp, bottom = bottomInset + 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = GlassShape.Rounded(28.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 28.dp),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Top) {
                        Person(
                            file = s.userAvatar,
                            name = me,
                            letter = avatarLetter(s.userName, "我"),
                            label = if (s.userName.isBlank()) null else "我",
                            menuOpen = menuFor == Who.Me,
                            onAvatar = { if (s.userAvatar == null) pick(Who.Me) else menuFor = Who.Me },
                            onName = { naming = Who.Me },
                            onChange = { menuFor = null; pick(Who.Me) },
                            onReset = { menuFor = null; c.appScope.launch { setAvatar(c, Who.Me, null) } },
                            onMenuDismiss = { menuFor = null },
                        )
                        Text(
                            "✧",
                            color = palette.accentContent,
                            fontSize = 22.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 34.dp),
                        )
                        Person(
                            file = s.aiAvatar,
                            name = ai,
                            letter = s.aiAvatarEmoji ?: avatarLetter(s.aiName, "TA"),
                            label = if (s.aiName.isBlank()) null else "TA",
                            menuOpen = menuFor == Who.Ai,
                            onAvatar = { if (s.aiAvatar == null && s.aiAvatarEmoji == null) pick(Who.Ai) else menuFor = Who.Ai },
                            onName = { naming = Who.Ai },
                            onChange = { menuFor = null; pick(Who.Ai) },
                            onReset = { menuFor = null; c.appScope.launch { setAvatar(c, Who.Ai, null) } },
                            onMenuDismiss = { menuFor = null },
                        )
                    }
                    Spacer(Modifier.height(22.dp))
                    Column(
                        Modifier.clickable(interactionSource = null, indication = null) { dating = true },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            since?.let { "认识的第 ${Home.dayNumber(it, Dates.today())} 天" } ?: "还没开始聊",
                            color = palette.accentContent,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            (since?.let { "从 ${Dates.full(it)} 算起" } ?: "从第一句话算起") + " · 点这里改",
                            color = palette.contentSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Stat(said, "句话")
                        Stat(diaries, "篇日记")
                        Stat(done, "件做完的事")
                    }
                }
            }

            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = GlassShape.Rounded(24.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = s.chatAvatars,
                            interactionSource = null,
                            indication = null,
                            role = Role.Switch,
                            onValueChange = { on -> c.appScope.launch { c.settings.update { it.copy(chatAvatars = on) } } },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("聊天里显示头像", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("每条消息旁边放上各自的头像", color = palette.contentSecondary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = s.chatAvatars,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = palette.accent,
                            checkedBorderColor = palette.accent,
                            uncheckedThumbColor = palette.contentSecondary,
                            uncheckedTrackColor = palette.content.copy(alpha = 0.07f),
                            uncheckedBorderColor = palette.contentSecondary.copy(alpha = 0.6f),
                        ),
                    )
                }
            }
        }

        cropping?.let { (who, uri) ->
            AvatarCropDialog(
                uri = uri,
                onDismiss = { cropping = null },
                onCropped = { bitmap ->
                    cropping = null
                    c.appScope.launch { setAvatar(c, who, c.images.save(bitmap, prefix = "avatar-")) }
                },
            )
        }

        naming?.let { who ->
            NameDialog(
                title = if (who == Who.Me) "你的名字" else "TA 的名字",
                initial = if (who == Who.Me) s.userName else s.aiName,
                onDismiss = { naming = null },
                onSave = { name ->
                    naming = null
                    c.appScope.launch {
                        c.settings.update { if (who == Who.Me) it.copy(userName = name) else it.copy(aiName = name) }
                    }
                },
            )
        }

        if (dating) {
            val today = Dates.today()
            val state = rememberDatePickerState(
                initialSelectedDateMillis = (since ?: today).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) =
                        !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
                },
            )
            DatePickerDialog(
                onDismissRequest = { dating = false },
                confirmButton = {
                    TextButton(onClick = {
                        dating = false
                        state.selectedDateMillis?.let { millis ->
                            val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                            c.appScope.launch { c.settings.update { it.copy(knownSince = day) } }
                        }
                    }) { Text("确定") }
                },
                dismissButton = {
                    Row {
                        if (s.knownSince != null) {
                            TextButton(onClick = {
                                dating = false
                                c.appScope.launch { c.settings.update { it.copy(knownSince = null) } }
                            }) { Text("按第一句话算") }
                        }
                        TextButton(onClick = { dating = false }) { Text("取消") }
                    }
                },
            ) { DatePicker(state) }
        }
    }
}

@Composable
private fun Person(
    file: String?,
    name: String,
    letter: String,
    label: String?,
    menuOpen: Boolean,
    onAvatar: () -> Unit,
    onName: () -> Unit,
    onChange: () -> Unit,
    onReset: () -> Unit,
    onMenuDismiss: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Avatar(
                file,
                letter,
                96.dp,
                Modifier
                    .clickable(onClickLabel = "换头像", onClick = onAvatar)
                    .semantics { contentDescription = "${name}的头像" },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
                DropdownMenuItem(text = { Text("换一张") }, onClick = onChange)
                DropdownMenuItem(text = { Text("用回默认") }, onClick = onReset)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            name,
            color = palette.content,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClickLabel = "改名字", onClick = onName),
        )
        if (label != null) Text(label, color = palette.contentSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun Stat(count: Int, unit: String) {
    val palette = LocalGlassPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("%,d".format(count), color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(unit, color = palette.contentSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 24) text = it },
                singleLine = true,
                placeholder = { Text("可以不写") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text("好") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Swaps the picture and removes the one it replaces: nothing else points at an old avatar. */
private suspend fun setAvatar(c: AppContainer, who: Who, file: String?) {
    val old = c.settings.current().let { if (who == Who.Me) it.userAvatar else it.aiAvatar }
    // A picture chosen here replaces an emoji TA picked for itself, and so does going back to the default.
    c.settings.update { if (who == Who.Me) it.copy(userAvatar = file) else it.copy(aiAvatar = file, aiAvatarEmoji = null) }
    if (old != null && old != file) c.images.delete(listOf(old))
}

/** Plain arithmetic for the home page, kept apart for testing. */
internal object Home {
    /** The day they met is day 1. */
    fun dayNumber(since: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(since, today).coerceAtLeast(0) + 1
}
