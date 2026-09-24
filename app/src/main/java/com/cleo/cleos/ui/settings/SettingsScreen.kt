package com.cleo.cleos.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ai.LetterTiming
import com.cleo.cleos.ai.ToolGroup
import com.cleo.cleos.data.ApiPresets
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.GlassMode
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appViewModel
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLab: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val palette = LocalGlassPalette.current
    var pickingModel by remember { mutableStateOf(false) }
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setWallpaper(uri)
    }
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    var confirmUndo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingRestore = uri
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readImport(uri)
    }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = "设置",
                backdrop = page,
                leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page) },
            )
        },
    ) {
        if (!vm.loaded) return@GlassPage
        Column(
            Modifier
                .fillMaxSize()
                .fadeUnderTopBar(statusTop + TopBarHeight)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = statusTop + TopBarHeight + 8.dp, bottom = navBottom + 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val ta = vm.aiName.trim().ifEmpty { "TA" }
            Section("${ta}的模型") {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ApiPresets.all.forEach { p ->
                        Chip(p.name, selected = vm.baseUrl.trimEnd('/') == p.baseUrl) { vm.applyPreset(p) }
                    }
                }
                Field("接口地址", vm.baseUrl, { vm.baseUrl = it }, keyboardType = KeyboardType.Uri)
                OutlinedTextField(
                    value = vm.keyInput,
                    onValueChange = { vm.keyInput = it },
                    label = { Text("API Key") },
                    placeholder = { if (hasKey) Text("已保存（不再显示）；要换就重新填") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (vm.keyInput.isNotBlank()) Chip("保存 Key", selected = true) { vm.saveKey() }
                    if (hasKey && vm.keyInput.isBlank()) {
                        Text("Key 已加密保存在这台手机上", color = palette.contentSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Chip("清除", selected = false) { vm.clearKey() }
                    }
                }
                Text(
                    "每个 TA 用自己的模型。Key 跟着接口地址存：同一个地址的几个 TA 共用一个 Key，填一次就够。",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Field("模型", vm.model, { vm.model = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(if (vm.checking) "正在连接…" else "测试并列出模型", selected = false) { if (!vm.checking) vm.check() }
                    if (!vm.models.isNullOrEmpty()) Chip("从列表里选", selected = false) { pickingModel = true }
                }
                vm.checkResult?.let {
                    Text(it, color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }

            Section("称呼") {
                Field("TA 的名字", vm.aiName, { vm.aiName = it })
                Field("你的名字", vm.userName, { vm.userName = it })
                OutlinedTextField(
                    value = vm.persona,
                    onValueChange = { if (it.length <= PERSONA_LIMIT) vm.persona = it },
                    label = { Text("TA 的性格") },
                    placeholder = { Text("想让 TA 怎样说话、记得什么，都写在这里。可以不写。") },
                    minLines = 4,
                    maxLines = 10,
                    supportingText = { Text("${vm.persona.length} / $PERSONA_LIMIT") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "每次带上最近 ${vm.historySize} 条消息",
                    color = palette.content,
                    fontSize = 14.sp,
                )
                Slider(
                    value = vm.historySize.toFloat(),
                    onValueChange = { vm.historySize = (it / 10f).roundToInt() * 10 },
                    valueRange = 10f..100f,
                    steps = 8,
                )
                Text(
                    "带得越多，TA 记得越久，每次花的 token 也越多。",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                )
                if (vm.companionCount > 1) {
                    Chip("删除$ta", selected = false) { confirmDelete = true }
                }
            }

            Section("TA 能做的事") {
                Text(
                    "在聊天里说一声，TA 就能去做。模型要支持工具调用（function calling）；不支持的会自动不带工具，聊天照常。",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                ToolSwitch(
                    "分几条发",
                    "想分开说的时候，TA 会一条一条地发，每条一个气泡。模型没这么做时，照常是一个气泡。",
                    ToolGroup.Messages in settings.tools,
                ) {
                    vm.setTool(ToolGroup.Messages, it)
                }
                ToolSwitch("待办", "帮你记下、查看、改日期、打勾", ToolGroup.Todos in settings.tools) {
                    vm.setTool(ToolGroup.Todos, it)
                }
                ToolSwitch("读你的日记", "你提到日记里写过的事时，TA 可以去翻（小秘密除外）。日记最私密，所以默认关着。", ToolGroup.Diary in settings.tools) {
                    vm.setTool(ToolGroup.Diary, it)
                }
                ToolSwitch("写日记", "TA 有自己的日记，写在同一个本子里，标着是 TA 写的；你能看，改不了。", ToolGroup.AiDiary in settings.tools) {
                    vm.setTool(ToolGroup.AiDiary, it)
                }
                ToolSwitch("小秘密", "TA 知道你有小秘密，但看不到；想看会在聊天里问你，你点头才给看。", ToolGroup.Secrets in settings.tools) {
                    vm.setTool(ToolGroup.Secrets, it)
                }
                ToolSwitch(
                    "记忆",
                    "TA 会自己记下关于你的事和它自己的事，下次还知道。在主页「记忆」里能看、能改、能钉住。",
                    ToolGroup.Memory in settings.tools,
                ) {
                    vm.setTool(ToolGroup.Memory, it)
                }
                ToolSwitch(
                    "写信",
                    "TA 隔几天、有话可写时会主动给你写信；聊天里提到信，TA 也接得上。关掉后只回你寄去的信。",
                    ToolGroup.Letters in settings.tools,
                ) {
                    vm.setTool(ToolGroup.Letters, it)
                }
                LetterPace(settings, vm)
                ToolSwitch("换自己的头像", "TA 可以把你发来的图、或者一个表情，换成自己的头像。", ToolGroup.Avatar in settings.tools) {
                    vm.setTool(ToolGroup.Avatar, it)
                }
                ToolSwitch("查天气", "用 open-meteo 查，不需要 Key", ToolGroup.Weather in settings.tools) {
                    vm.setTool(ToolGroup.Weather, it)
                }
                if (ToolGroup.Weather in settings.tools) {
                    OutlinedTextField(
                        value = vm.weatherCity,
                        onValueChange = { vm.weatherCity = it },
                        label = { Text("你在的城市") },
                        placeholder = { Text("比如 杭州；不填的话 TA 会问你") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Section("外观") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(if (vm.wallpaperBusy) "正在换…" else "换壁纸", selected = false) {
                        if (!vm.wallpaperBusy) {
                            wallpaperPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    }
                    if (settings.wallpaper != null) Chip("用回默认", selected = false) { vm.resetWallpaper() }
                }
                vm.wallpaperError?.let { Text(it, color = palette.error, fontSize = 13.sp) }
                Text("玻璃", color = palette.content, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("跟随壁纸", selected = settings.glassMode == GlassMode.Auto) { vm.setGlassMode(GlassMode.Auto) }
                    Chip("浅色", selected = settings.glassMode == GlassMode.Light) { vm.setGlassMode(GlassMode.Light) }
                    Chip("深色", selected = settings.glassMode == GlassMode.Dark) { vm.setGlassMode(GlassMode.Dark) }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable(interactionSource = null, indication = null, onClick = onOpenLab),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("玻璃实验室", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("拖一块玻璃，看每个参数在做什么", color = palette.contentSecondary, fontSize = 12.sp)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.contentSecondary)
                }
            }

            Section("数据") {
                Text(
                    "把每个 TA、聊天、日记、信、记忆、待办和图片打包成一个文件。换手机、重装之前先导出一份。API Key 不会导出。",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(if (vm.backupBusy) "正在处理…" else "导出备份", selected = false) {
                        if (!vm.backupBusy) exportPicker.launch("cleos-备份-${java.time.LocalDate.now()}.zip")
                    }
                    Chip("从备份恢复", selected = false) {
                        if (!vm.backupBusy) restorePicker.launch(arrayOf("application/zip", "application/octet-stream"))
                    }
                }
                if (vm.canUndoRestore) {
                    Chip("撤销上次恢复", selected = false) { confirmUndo = true }
                }
                Text(
                    "从别的 App 搬过来：现在认得 phone_ai_assistant 导出的备份（日记备份-….json）。会新建一个 TA，" +
                        "带上那边的对话和它写的日记，它记得的关于你的事成为这个 TA 的记忆。这里已有的都不动。",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                // Any type: a file passed along through a chat app often comes back without a JSON type.
                Chip("从别的 App 导入", selected = false) {
                    if (!vm.backupBusy) importPicker.launch(arrayOf("*/*"))
                }
                vm.backupMessage?.let { Text(it, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
            }

            Section("关于") {
                val context = LocalContext.current
                val version = remember {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                }
                Text("Cleos ${version.orEmpty()}", color = palette.content, fontSize = 14.sp)
                Text(
                    "聊天、日记和待办都只存在这台手机上。API Key 用系统密钥库加密。",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }

    vm.pendingImport?.let { plan ->
        var name by remember(plan) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            title = { Text("导入成一个新的 TA") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${plan.conversations.size} 段对话（${plan.messageCount} 条消息）、${plan.diary.size} 篇它写的日记。")
                    if (plan.memories.isNotEmpty()) {
                        Text("它记得的关于你的 ${plan.memories.size} 件事（${plan.memories.sumOf { it.details.size }} 条细节）成为它在这里的记忆。")
                    }
                    Text(
                        when {
                            plan.personaCut -> "那边对话里设的性格太长，只带了前面 $PERSONA_LIMIT 字。"
                            plan.hadPersona -> "那边对话里设的性格也一起带上。"
                            else -> "那边没单独设过性格（那个 App 默认的性格写在它的代码里，备份里没有），想要可以之后在设置里写。"
                        },
                    )
                    if (plan.picturesLeftBehind > 0) {
                        Text("${plan.picturesLeftBehind} 张图片带不过来：那边的备份里只有图片的名字，没有图。")
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text("TA 的名字") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmImport(name) }) { Text("导入") } },
            dismissButton = { TextButton(onClick = vm::cancelImport) { Text("取消") } },
        )
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("用这份备份替换现在的内容？") },
            text = {
                Text("现在的 TA、聊天、日记和待办会被备份里的全部替换掉。恢复之前会自动把现在的留一份，恢复完可以撤销。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    vm.restoreBackup(uri)
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("取消") } },
        )
    }

    if (confirmDelete) {
        val ta = vm.aiName.trim().ifEmpty { "这个 TA" }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除$ta？") },
            text = { Text("和${ta}的所有对话、${ta}写的日记会一起删掉，删了找不回来。你自己的日记和待办不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteCompanion(onBack)
                }) { Text("删除", color = palette.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }

    if (confirmUndo) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text("撤销上次恢复？") },
            text = { Text("回到恢复之前的 TA、聊天、日记和待办。恢复之后新写的会没有。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUndo = false
                    vm.undoRestore()
                }) { Text("撤销") }
            },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("取消") } },
        )
    }

    if (pickingModel) {
        AlertDialog(
            onDismissRequest = { pickingModel = false },
            title = { Text("选一个模型") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(vm.models.orEmpty()) { id ->
                        Text(
                            id,
                            fontSize = 15.sp,
                            fontWeight = if (id == vm.model) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.model = id
                                    pickingModel = false
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingModel = false }) { Text("关闭") } },
        )
    }
}

/** The title lives inside the card: above it, it would be bare text on the wallpaper. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalGlassPalette.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = GlassShape.Rounded(24.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = palette.accentContent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/**
 * How letters are paced, in a panel under the letters switch. The reply's wait is a range;
 * each reply lands somewhere in it at random. The gap between a TA's own letters shows only
 * while they write them. A slider is saved when it is let go, not at every step of a drag.
 */
@Composable
private fun LetterPace(settings: AppSettings, vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    val steps = LetterTiming.REPLY_STEPS
    var reply by remember(settings.letterReplyMin, settings.letterReplyMax) {
        mutableStateOf(LetterTiming.stepOf(settings.letterReplyMin).toFloat()..LetterTiming.stepOf(settings.letterReplyMax).toFloat())
    }
    var every by remember(settings.letterEveryDays) { mutableFloatStateOf(settings.letterEveryDays.toFloat()) }
    fun minutes(at: Float) = steps[at.roundToInt().coerceIn(0, steps.lastIndex)]
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.content.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            LetterTiming.describeReply(minutes(reply.start), minutes(reply.endInclusive)),
            color = palette.content,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        RangeSlider(
            value = reply,
            onValueChange = { reply = it },
            onValueChangeFinished = { vm.setLetterReply(minutes(reply.start), minutes(reply.endInclusive)) },
            valueRange = 0f..steps.lastIndex.toFloat(),
            steps = steps.size - 2,
        )
        Text(
            "两头都能拖。每封回信在这段时间里随便哪一刻到；改了只管以后寄的信，已经在路上的照原来的时间到。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(10.dp))
        ToolSwitch(
            "夜里不来信",
            "夜里 11 点到早上 8 点之间要到的信，推到第二天早上 8～9 点。",
            settings.letterQuietNight,
        ) { vm.setLetterQuietNight(it) }
        if (ToolGroup.Letters in settings.tools) {
            Spacer(Modifier.height(10.dp))
            Text(
                "TA 自己写信，两封至少隔 ${every.roundToInt()} 天",
                color = palette.content,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Slider(
                value = every,
                onValueChange = { every = it },
                onValueChangeFinished = { vm.setLetterEveryDays(every.roundToInt()) },
                valueRange = 1f..14f,
                steps = 12,
            )
            Text(
                "隔够了也不一定写：这段时间聊过一阵，或者有新日记，TA 才会动笔。",
                color = palette.contentSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun ToolSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalGlassPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = null,
                indication = null,
                role = Role.Switch,
                onValueChange = onChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        // The row is the control; the switch only shows its state (one target for a screen reader).
        Switch(
            checked = checked,
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

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * A plain pill, not glass: these sit on a glass card, and glass inside the page can only
 * see the wallpaper, so a glass chip here would look like a hole cut through the card.
 */
@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .background(if (selected) palette.accent else palette.content.copy(alpha = 0.07f), CircleShape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (selected) Color.White else palette.content, fontSize = 14.sp)
    }
}
