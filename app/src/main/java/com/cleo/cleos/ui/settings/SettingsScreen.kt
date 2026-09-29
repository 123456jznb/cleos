package com.cleo.cleos.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.ai.Mcp
import com.cleo.cleos.ai.PhoneCalendar
import com.cleo.cleos.ai.PhoneLocation
import com.cleo.cleos.ai.Speech
import com.cleo.cleos.ai.SpeechEngine
import com.cleo.cleos.ai.Voice
import com.cleo.cleos.ai.ToolGroup
import com.cleo.cleos.data.ApiPresets
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.McpServer
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
fun SettingsScreen(onBack: () -> Unit, onOpenLab: () -> Unit, onOpenMcp: (String) -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val mcpServers by vm.mcpServers.collectAsStateWithLifecycle()
    val hasVoiceKey by vm.hasVoiceKey.collectAsStateWithLifecycle()
    val hasSpeechKey by vm.hasSpeechKey.collectAsStateWithLifecycle()
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
    val appContext = LocalContext.current
    var locationHint by remember { mutableStateOf<String?>(null) }
    // The switch goes on once the person has let the app use the location, not before.
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            locationHint = null
            vm.setTool(ToolGroup.Location, true)
        } else {
            locationHint = "没给定位权限，TA 查不了位置。"
        }
    }
    var calendarHint by remember { mutableStateOf<String?>(null) }
    // Likewise the calendar, which needs both: reading what is on it, and adding to Cleos's own.
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) {
            calendarHint = null
            vm.setTool(ToolGroup.Calendar, true)
        } else {
            calendarHint = "没给日历权限，TA 看不了也加不了日程。"
        }
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
                ToolSwitch(
                    "深度思考",
                    "回答前先想一想：前后更连贯，推理也更好；每次要多等几秒，也多花一点 token。DeepSeek、智谱这类模型认这个开关，不认的会自动照常回复。",
                    vm.deepThinking,
                ) { vm.setThinking(it) }
                ToolSwitch(
                    "主动找你",
                    "聊天时，TA 会给自己记下想过一阵再说的事（比如你去做饭了，过一会儿问问做得怎么样），到时候看看这之间聊了什么，自己决定要不要找你；早上你醒了、晚上睡前，也可能来打个招呼。不会因为你没回、好久没聊来催你。",
                    vm.proactive,
                ) { vm.setReachOut(it) }
                if (vm.proactive) ReachOutStatus(vm)
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
                    "每次原样带上最近 ${vm.historySize} 条消息",
                    color = palette.content,
                    fontSize = 14.sp,
                )
                Slider(
                    value = vm.historySize.toFloat(),
                    onValueChange = { vm.historySize = (it / 10f).roundToInt() * 10 },
                    valueRange = 10f..200f,
                    steps = 18,
                )
                Text(
                    "更早的，TA 会自己整理成前情提要带着，不会一下子忘掉；在聊天里往上翻，能看到分界的那一行，点开能看、能改。原样带得越多，细节记得越清楚，每次花的 token 也越多。",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
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
                ToolSwitch(
                    "发语音条",
                    "TA 想用声音说的时候（道晚安、撒娇），发一条语音条，你能听，也看得到字。用什么声音，在下面「TA 的声音」里选。",
                    ToolGroup.Speak in settings.tools,
                ) {
                    vm.setTool(ToolGroup.Speak, it)
                }
                if (ToolGroup.Speak in settings.tools && !Speech.ready(settings)) {
                    Text("还没配好声音，TA 暂时发不了：在下面「TA 的声音」里选一个。", color = palette.error, fontSize = 12.sp)
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
                if (ToolGroup.Letters in settings.tools) LetterPace(settings, vm)
                ToolSwitch("换自己的头像", "TA 可以把你发来的图、或者一个表情，换成自己的头像。", ToolGroup.Avatar in settings.tools) {
                    vm.setTool(ToolGroup.Avatar, it)
                }
                ToolSwitch(
                    "查位置",
                    "TA 需要知道你在哪时（问附近、问路、问天气），用手机定位查一下，地名要联网查。只在聊天中查，不在后台跟踪；每查一次，聊天里都会写一行。",
                    ToolGroup.Location in settings.tools,
                ) { on ->
                    when {
                        !on -> vm.setTool(ToolGroup.Location, false)
                        PhoneLocation.allowed(appContext) -> vm.setTool(ToolGroup.Location, true)
                        else -> askLocation.launch(PhoneLocation.PERMISSIONS)
                    }
                }
                if (ToolGroup.Location in settings.tools && !PhoneLocation.allowed(appContext)) {
                    Text("还没给定位权限，TA 查不了：把开关关掉再打开，会重新问你。", color = palette.error, fontSize = 12.sp)
                }
                locationHint?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp) }
                ToolSwitch(
                    "定闹钟",
                    "你让 TA 定闹钟、计时，它在手机自带的时钟里定，到点手机响，和你自己定的一样。只在 Cleos 开着的时候能定：手机不让 App 在后台打开时钟。",
                    ToolGroup.Alarm in settings.tools,
                ) {
                    vm.setTool(ToolGroup.Alarm, it)
                }
                ToolSwitch(
                    "日历",
                    "TA 能看你手机日历上的安排；你让它记的日程，加在一个叫「Cleos」的日历里，可以带提醒。它只能改、删自己加的，你的日程只能看。要日历权限，打开时会问。",
                    ToolGroup.Calendar in settings.tools,
                ) { on ->
                    when {
                        !on -> vm.setTool(ToolGroup.Calendar, false)
                        PhoneCalendar.allowed(appContext) -> vm.setTool(ToolGroup.Calendar, true)
                        else -> askCalendar.launch(PhoneCalendar.PERMISSIONS)
                    }
                }
                if (ToolGroup.Calendar in settings.tools && !PhoneCalendar.allowed(appContext)) {
                    Text("还没给日历权限，TA 看不了：把开关关掉再打开，会重新问你。", color = palette.error, fontSize = 12.sp)
                }
                calendarHint?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp) }
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

            Section("发语音") {
                Text(
                    "在聊天里按住输入框右边的话筒说话，松开就发，往上滑再松开是取消。语音先转成文字再给 TA 看，" +
                        "所以要接一个转文字的服务，和聊天的模型分开选。",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Voice.presets.forEach { p ->
                        Chip(p.name, selected = vm.voiceBaseUrl.trimEnd('/') == p.baseUrl) { vm.applyVoicePreset(p) }
                    }
                }
                Field("接口地址", vm.voiceBaseUrl, { vm.voiceBaseUrl = it }, keyboardType = KeyboardType.Uri)
                Field("模型", vm.voiceModel, { vm.voiceModel = it })
                OutlinedTextField(
                    value = vm.voiceKeyInput,
                    onValueChange = { vm.voiceKeyInput = it },
                    label = { Text("API Key") },
                    placeholder = { if (hasVoiceKey) Text("已保存（不再显示）；要换就重新填") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (vm.voiceKeyInput.isNotBlank()) Chip("保存 Key", selected = true) { vm.saveVoiceKey() }
                    if (vm.voiceBaseUrl.isNotBlank()) Chip(if (vm.voiceTesting) "正在试…" else "试一下", selected = false) { vm.testVoice() }
                }
                Text(
                    if (hasVoiceKey) "这个地址的 Key 已经有了。" else "Key 跟着地址存：和哪个 TA 聊天用的是同一个地址，就不用再填。模型名以服务商的说明为准。",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                vm.voiceResult?.let { Text(it, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
            }

            Section("TA 的声音") {
                Text(
                    "TA 发语音条时用的声音。合成交给你选的服务：MCP 工具（比如 MiniMax 的）、OpenAI 格式的语音接口" +
                        "（硅基流动 CosyVoice、OpenAI、Mossland），或者 ElevenLabs。",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeechEngine.entries.forEach { e -> Chip(e.label, selected = vm.speechEngine == e) { vm.speechEngine = e } }
                }
                when (vm.speechEngine) {
                    SpeechEngine.Api -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Speech.presets.forEach { p ->
                                Chip(p.name, selected = vm.speechBaseUrl.trimEnd('/') == p.baseUrl) { vm.applySpeechPreset(p) }
                            }
                        }
                        Field("接口地址", vm.speechBaseUrl, { vm.speechBaseUrl = it }, keyboardType = KeyboardType.Uri)
                        Field("模型", vm.speechModel, { vm.speechModel = it })
                        val moss = Speech.isMoss(vm.speechBaseUrl)
                        Field(if (moss) "音色 ID" else "声音", vm.speechVoice, { vm.speechVoice = it })
                        if (moss) {
                            Text(
                                "换声音：在 Mossland 音色库里挑一个，点卡片上的复制图标，把音色 ID 粘到这里。" +
                                    "Key 在 Moss 开放平台（platform.mosi.cn）的 API Keys 里建。",
                                color = palette.contentSecondary,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            )
                        }
                    }
                    SpeechEngine.ElevenLabs -> {
                        Field("Voice ID", vm.elevenVoice, { vm.elevenVoice = it })
                        Field("模型（不填就是 ${Speech.ELEVENLABS_MODEL}）", vm.elevenModel, { vm.elevenModel = it })
                    }
                    SpeechEngine.Mcp -> {
                        val picked = vm.speechTools?.firstOrNull { it.serverId == vm.speechMcpServer && it.name == vm.speechMcpTool }
                        Text(
                            when {
                                vm.speechMcpTool.isBlank() -> "还没选工具。"
                                picked != null -> "用的是：${picked.serverName} · ${picked.title}"
                                else -> "用的是：${vm.speechMcpTool}"
                            },
                            color = palette.content,
                            fontSize = 14.sp,
                        )
                        Chip(if (vm.speechTools == null) "列出 MCP 工具" else "重新列出", selected = false) { vm.loadSpeechTools() }
                        vm.speechTools?.let { list ->
                            if (list.isEmpty()) {
                                Text("还没有开着的 MCP 服务，或者它们没有工具。先在下面「外部服务（MCP）」里加一个。", color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                            } else {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    list.forEach { t ->
                                        Chip("${t.serverName} · ${t.title}", selected = t.serverId == vm.speechMcpServer && t.name == vm.speechMcpTool) { vm.pickSpeechTool(t) }
                                    }
                                }
                            }
                        }
                        Field("文字放在哪个参数", vm.speechMcpTextParam, { vm.speechMcpTextParam = it })
                        OutlinedTextField(
                            value = vm.speechMcpArgs,
                            onValueChange = { vm.speechMcpArgs = it },
                            label = { Text("其他参数（JSON，可不填）") },
                            placeholder = { Text("比如 {\"voice_id\": \"female-shaonv\"}") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "工具回音频，或者回一个音频链接，都能变成语音条。用的是你自己配的工具，所以这里不再每次问你。",
                            color = palette.contentSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
                if (vm.speechEngine != SpeechEngine.Mcp) {
                    OutlinedTextField(
                        value = vm.speechKeyInput,
                        onValueChange = { vm.speechKeyInput = it },
                        label = { Text("API Key") },
                        placeholder = { if (hasSpeechKey) Text("已保存（不再显示）；要换就重新填") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (vm.speechKeyInput.isNotBlank()) Chip("保存 Key", selected = true) { vm.saveSpeechKey() }
                    Text(
                        if (hasSpeechKey) "这个地址的 Key 已经有了。" else "Key 跟着地址存：和聊天、转文字用同一个地址的话，不用再填。",
                        color = palette.contentSecondary,
                        fontSize = 12.sp,
                    )
                }
                Chip(if (vm.speechBusy) "正在合成…" else "试听", selected = false) { vm.previewSpeech() }
                vm.speechResult?.let { Text(it, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
                // Setting up a voice doesn't let the TA use it: that is the switch up in 「TA 能做的事」.
                if (ToolGroup.Speak !in settings.tools && Speech.ready(settings)) {
                    Text(
                        "声音配好了，不过上面「TA 能做的事」里的「发语音条」还关着，TA 现在还不会发。",
                        color = palette.content,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Chip("打开发语音条", selected = true) { vm.setTool(ToolGroup.Speak, true) }
                }
            }

            Section("外部服务（MCP）") {
                Text(
                    "接上 MCP 服务，TA 就能用它们的工具，比如点咖啡、查路线。只支持 Streamable HTTP 的地址，电脑上 stdio 那种接不了。" +
                        "地址和 Token 加密存在这台手机上，不进备份。",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                for (server in mcpServers) {
                    McpRow(server, onOpen = { onOpenMcp(server.id) }) { vm.setMcpEnabled(server.id, it) }
                }
                Chip("添加一个服务", selected = false) { onOpenMcp("") }
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
                MyBubbleColor(settings.myBubble, vm::setMyBubble)
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
                var releasesHint by remember { mutableStateOf<String?>(null) }
                Text("Cleos ${version.orEmpty()}", color = palette.content, fontSize = 14.sp)
                Text(
                    "新版本都放在蓝奏云上。更新时直接装新的 apk、覆盖安装，聊天记录都还在；别先卸载，卸载会把这台手机上的聊天、" +
                        "日记一起清掉。真要重装，先在上面「数据」里导出一份备份。",
                    color = palette.contentSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Chip("去蓝奏云看新版", selected = false) {
                    // The page asks for the code once; it is on the clipboard by then.
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("提取码", RELEASES_CODE))
                    val opened = runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.isSuccess
                    releasesHint = if (opened) {
                        "提取码 $RELEASES_CODE 已经复制好了，页面让输密码时粘贴就行。"
                    } else {
                        "没找到能打开网页的浏览器。地址是 $RELEASES_URL ，提取码 $RELEASES_CODE（已复制）。"
                    }
                }
                releasesHint?.let { Text(it, color = palette.content, fontSize = 13.sp, lineHeight = 19.sp) }
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
 * How often a TA writes of their own accord, in a panel under the letters switch while it
 * is on. When a reply comes is picked on each letter as it is sent, not here. The slider is
 * saved when it is let go, not at every step of a drag.
 */
@Composable
private fun LetterPace(settings: AppSettings, vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current
    var every by remember(settings.letterEveryDays) { mutableFloatStateOf(settings.letterEveryDays.toFloat()) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.content.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
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
            "隔够了也不一定写：这段时间聊过一阵，或者有新日记，TA 才会动笔。你寄的信，回信什么时候到在寄的时候选。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
    }
}

/** One MCP service: tap it to edit, switch it on and off beside. */
@Composable
private fun McpRow(server: McpServer, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val palette = LocalGlassPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .clickable(interactionSource = null, indication = null, onClick = onOpen),
        ) {
            Text(server.name.ifBlank { "没起名字" } + " ›", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            // Never the address as stored: a key in it would show.
            Text(Mcp.displayUrl(server.url), color = palette.contentSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = server.enabled, onCheckedChange = onToggle, colors = glassSwitchColors())
    }
}

@Composable
internal fun glassSwitchColors() = LocalGlassPalette.current.let { palette ->
    SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = palette.accent,
        checkedBorderColor = palette.accent,
        uncheckedThumbColor = palette.contentSecondary,
        uncheckedTrackColor = palette.content.copy(alpha = 0.07f),
        uncheckedBorderColor = palette.contentSecondary.copy(alpha = 0.6f),
    )
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
        Switch(checked = checked, onCheckedChange = null, colors = glassSwitchColors())
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
internal fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
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

/** Where every release goes: a 蓝奏云 folder, so the link stays the same from one version to the next. */
private const val RELEASES_URL = "https://wwbnf.lanzouc.com/b01gicbubg"
private const val RELEASES_CODE = "5y4u"
