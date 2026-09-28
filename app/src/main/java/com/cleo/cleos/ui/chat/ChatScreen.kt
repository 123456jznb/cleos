package com.cleo.cleos.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cleo.cleos.ai.ChatRepository
import com.cleo.cleos.ai.McpAsk
import com.cleo.cleos.ai.Prompt
import com.cleo.cleos.ai.Recap
import com.cleo.cleos.ai.SecretRequest
import com.cleo.cleos.ai.SecretRequests
import com.cleo.cleos.ai.StreamingReply
import com.cleo.cleos.ai.Voice
import com.cleo.cleos.data.MessageAudio
import com.cleo.cleos.data.MessageAudios
import com.cleo.cleos.data.MessageImage
import com.cleo.cleos.data.MessageImages
import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassButton
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.liquidGlass
import com.cleo.cleos.ui.common.Avatar
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.common.appViewModel
import com.cleo.cleos.ui.common.avatarLetter
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/** A gap longer than this between two messages gets a time line between them. */
private const val TIME_GAP_MS = 10 * 60 * 1000L

private data class Face(val file: String?, val letter: String)

/** Whose picture goes beside which bubbles; null when the chat shows no avatars. */
private data class Faces(val me: Face, val ai: Face)

private val LocalFaces = compositionLocalOf<Faces?> { null }
private val AvatarSize = 34.dp
private val AvatarGap = 8.dp

/** What a row gives up on the avatar's side, so lines without one still line up. */
private val AvatarSlot = AvatarSize + AvatarGap

private val MaxPictureHeight = 260.dp

/** The input bar's height on one line; its round ends have half this as radius. */
private val BarHeight = 50.dp

/** Pictures in one message: as many as are sent along with a request. */
private const val MAX_ATTACHMENTS = Prompt.MAX_IMAGES

private sealed interface ChatRow {
    val key: Any

    data class Stamp(val at: Long) : ChatRow {
        override val key: Any get() = "t$at"
    }

    /** Where the messages sent verbatim begin: before it, the TA has only the recap. */
    data object RecapMark : ChatRow {
        override val key: Any get() = "recap"
    }

    /** [showFace]: the newest of a run of messages from one side, which alone gets the avatar. */
    data class Message(val message: MessageEntity, val isLast: Boolean, val showFace: Boolean = true) : ChatRow {
        override val key: Any get() = message.id
    }
}

/**
 * Rows with nothing to draw: an assistant turn that only called tools (the results have
 * their own lines), and a tool result without a line (a request shows its card instead).
 */
private fun MessageEntity.silent() =
    (role == "assistant" && content.isEmpty() && error == null) || (role == "tool" && note.isNullOrBlank())

/** Which side a bubble is on: true for the person's, false for the TA's, null for lines that aren't bubbles. */
private fun MessageEntity.side(): Boolean? = when {
    role == "user" && note == null -> true
    role == "assistant" -> false
    else -> null
}

/** Whether the message is folded into a recap that ends at [until] (a time, then an id). */
private fun MessageEntity.foldedBy(until: Pair<Long, Long>) = createdAt < until.first || (createdAt == until.first && id <= until.second)

/**
 * Newest first, because the list is laid out bottom-up. Several messages in a row from one
 * side show the avatar once, beside the newest, the way chat apps stack them; a line or a
 * time between them starts a new run. With a recap, its mark goes between the last message
 * folded into it and the first one after.
 */
private fun buildRows(messages: List<MessageEntity>, recapUntil: Pair<Long, Long>? = null): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + 8)
    var newerSide: Boolean? = null
    var marked = false
    for (i in messages.indices.reversed()) {
        val m = messages[i]
        if (recapUntil != null && !marked && m.foldedBy(recapUntil)) {
            rows += ChatRow.RecapMark
            marked = true
            newerSide = null
        }
        if (m.silent()) continue
        val side = m.side()
        rows += ChatRow.Message(m, isLast = i == messages.lastIndex, showFace = side == null || side != newerSide)
        newerSide = side
        val prev = messages.getOrNull(i - 1)
        if (prev == null || m.createdAt - prev.createdAt > TIME_GAP_MS) {
            rows += ChatRow.Stamp(m.createdAt)
            newerSide = null
        }
    }
    return rows
}

@Composable
fun ChatTab(
    bottomInset: Dp,
    onOpenSettings: () -> Unit,
    onOpenConversations: () -> Unit,
    onOpenImage: (String) -> Unit,
) {
    val vm = appViewModel { ChatViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val c = appContainer()
    val companions by remember { c.companions.all }.collectAsStateWithLifecycle(emptyList())
    var switching by remember { mutableStateOf(false) }
    var readingRecap by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Voice messages: the recording under way, what the indicator shows, and the one playing.
    val recorder = remember { VoiceRecorder(c.images.dir) }
    var recording by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var recordedMs by remember { mutableLongStateOf(0L) }
    var loudness by remember { mutableFloatStateOf(0f) }
    var voiceHint by remember { mutableStateOf<String?>(null) }
    var askVoiceSetup by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<String?>(null) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        voiceHint = if (granted) "可以了，再按住说话" else "要给 Cleos 用话筒的权限，才能发语音"
    }

    fun stopPlaying() {
        player[0]?.let {
            runCatching { it.stop() }
            it.release()
        }
        player[0] = null
        playing = null
    }

    fun play(file: String) {
        val again = playing == file
        stopPlaying()
        if (again) return
        val p = MediaPlayer()
        runCatching {
            p.setDataSource(c.images.file(file).path)
            p.setOnCompletionListener { stopPlaying() }
            p.prepare()
            p.start()
        }.onSuccess {
            player[0] = p
            playing = file
        }.onFailure {
            p.release()
            voiceHint = "这段语音放不了"
        }
    }

    fun startVoice(): Boolean {
        when {
            !state.voiceReady -> askVoiceSetup = true
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                askMic.launch(Manifest.permission.RECORD_AUDIO)
            !recorder.start() -> voiceHint = "话筒打不开，可能正被别的应用用着"
            else -> {
                stopPlaying()
                recordedMs = 0
                cancelling = false
                recording = true
                return true
            }
        }
        return false
    }

    fun endVoice(cancel: Boolean) {
        if (!recording) return
        recording = false
        cancelling = false
        if (cancel) {
            recorder.cancel()
            return
        }
        val clip = recorder.stop()
        when {
            clip == null -> voiceHint = "说话时间太短了"
            !vm.sendVoice(clip) -> voiceHint = "${state.aiName.ifBlank { "TA" }}还在回，等一下再说"
        }
    }

    LaunchedEffect(recording) {
        val t0 = System.currentTimeMillis()
        while (recording) {
            recordedMs = System.currentTimeMillis() - t0
            loudness = recorder.level
            // A minute is as long as one goes; it is sent as it is.
            if (recordedMs >= Voice.MAX_MS) endVoice(cancel = false)
            delay(100)
        }
    }
    LaunchedEffect(voiceHint) {
        if (voiceHint != null) {
            delay(2200)
            voiceHint = null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            stopPlaying()
            recorder.cancel()
        }
    }
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    var inputHeight by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) {
        vm.attach(it)
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val inputBottom = if (imeBottom > bottomInset) imeBottom + 8.dp else bottomInset
    val rows = remember(state.messages, state.recapUntil) { buildRows(state.messages, state.recapUntil) }
    val faces = if (!state.chatAvatars) {
        null
    } else {
        Faces(
            me = Face(state.userAvatar, avatarLetter(state.userName, "我")),
            ai = Face(state.aiAvatar, state.aiAvatarEmoji ?: avatarLetter(state.aiName, "TA")),
        )
    }

    // Follow new messages, unless the user has scrolled up to read something older.
    LaunchedEffect(state.messages.lastOrNull()?.id, state.streaming?.text?.isEmpty(), state.streaming?.activity) {
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }

    GlassPage(
        overlay = { page ->
            // The title is the TA; tapping it switches to another one or adds one.
            GlassTopBar(
                title = state.aiName.ifBlank { "聊天" },
                subtitle = state.model.takeIf { it.isNotBlank() }?.let { "$it ▾" },
                backdrop = page,
                leading = { GlassIconButton(Icons.Rounded.Forum, "对话记录", onOpenConversations, page) },
                trailing = {
                    GlassIconButton(Icons.Rounded.AddComment, "新对话", vm::newConversation, page)
                    GlassIconButton(Icons.Rounded.Settings, "设置", onOpenSettings, page)
                },
                onTitleClick = { switching = true },
                titleMenu = {
                    DropdownMenu(expanded = switching, onDismissRequest = { switching = false }) {
                        companions.forEach { ta ->
                            val here = ta.id == state.companionId
                            DropdownMenuItem(
                                text = { Text(ta.name.ifBlank { "TA" }, fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal) },
                                leadingIcon = { Avatar(ta.avatar, ta.avatarEmoji ?: avatarLetter(ta.name, "TA"), 28.dp) },
                                trailingIcon = if (here) ({ Icon(Icons.Rounded.Check, contentDescription = "正在聊") }) else null,
                                onClick = {
                                    switching = false
                                    if (!here) vm.switchTo(ta.id)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("添加一个 TA") },
                            leadingIcon = { Icon(Icons.Rounded.PersonAdd, contentDescription = null) },
                            onClick = {
                                switching = false
                                vm.addCompanion(onOpenSettings)
                            },
                        )
                    }
                },
            )
            ChatInputBar(
                backdrop = page,
                text = input,
                onTextChange = { input = it },
                attachments = vm.attachments,
                attaching = vm.attaching,
                onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemove = vm::detach,
                busy = state.replying,
                onSend = { if (vm.send(input)) input = "" },
                onStop = vm::stop,
                recording = recording,
                onVoiceStart = { startVoice() },
                onVoiceMove = { cancelling = it },
                onVoiceEnd = { endVoice(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = inputBottom)
                    .onSizeChanged { inputHeight = it.height },
            )
            if (recording || voiceHint != null) {
                RecordingPill(
                    backdrop = page,
                    recording = recording,
                    ms = recordedMs,
                    level = loudness,
                    cancelling = cancelling,
                    hint = voiceHint,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = inputBottom + with(density) { inputHeight.toDp() } + 10.dp),
                )
            }
        },
    ) {
        val inputTop = inputBottom + with(density) { inputHeight.toDp() }
        CompositionLocalProvider(LocalFaces provides faces) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .fillMaxSize()
                    .fadeUnderTopBar(statusTop + TopBarHeight, bottom = inputTop),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = statusTop + TopBarHeight + 8.dp,
                    bottom = inputTop + 12.dp,
                ),
                // Bottom: a short conversation should sit just above the input, where the
                // newest message is, not float up under the title. spacedBy without an
                // alignment would put it at the top even in a reversed list.
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
            ) {
                state.streaming?.let { live ->
                    item(key = "live") { LiveBubble(live, state.aiName, vm::answerAsk) }
                }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ChatRow.Stamp -> TimeStamp(row.at)
                        ChatRow.RecapMark -> RecapMark(state.aiName) { readingRecap = true }
                        is ChatRow.Message -> {
                            val m = row.message
                            val note = m.note
                            when {
                                m.role == "tool" || m.role == "note" ->
                                    ToolNote(note.orEmpty(), if (m.role == "note") Icons.Rounded.Info else Icons.Rounded.AutoAwesome)
                                m.role == "request" -> RequestCard(m, state.aiName, enabled = !state.replying) { grant ->
                                    vm.answerSecret(m.id, grant)
                                }
                                // The person's answer to a request: their turn, drawn as a line on their side.
                                m.role == "user" && note != null -> ToolNote(note, Icons.Rounded.Key, mine = true)
                                else -> MessageBubble(
                                    message = m,
                                    showFace = row.showFace,
                                    canRetry = row.isLast && !state.replying,
                                    onRetry = { vm.retry(m.id) },
                                    onDelete = { vm.delete(m.id) },
                                    onOpenImage = onOpenImage,
                                    transcribing = m.id in state.transcribing,
                                    playingFile = playing,
                                    onPlay = { play(it) },
                                    onRetryVoice = { vm.retryVoice(m.id) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.loaded && !state.hasApiKey && state.messages.isEmpty()) {
            NoKeyCard(onOpenSettings, Modifier.align(Alignment.Center).padding(horizontal = 28.dp))
        } else if (state.loaded && state.messages.isEmpty() && state.streaming == null) {
            GlassSurface(
                modifier = Modifier.align(Alignment.Center),
                style = palette.notice,
                shape = GlassShape.Capsule,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text(
                    if (state.aiName.isBlank()) "说点什么吧" else "和${state.aiName}说点什么吧",
                    color = palette.contentSecondary,
                    fontSize = 15.sp,
                )
            }
        }
    }

    if (askVoiceSetup) {
        AlertDialog(
            onDismissRequest = { askVoiceSetup = false },
            title = { Text("先接一个转文字的服务") },
            text = { Text("语音要先转成文字再给${state.aiName.ifBlank { "TA" }}看。在设置「发语音」里选一个服务、填上 Key 就能用。") },
            confirmButton = {
                TextButton(onClick = {
                    askVoiceSetup = false
                    onOpenSettings()
                }) { Text("去设置") }
            },
            dismissButton = { TextButton(onClick = { askVoiceSetup = false }) { Text("算了") } },
        )
    }

    if (readingRecap) {
        RecapDialog(
            aiName = state.aiName,
            recap = state.recap,
            onSave = {
                vm.saveRecap(it)
                readingRecap = false
            },
            onDismiss = { readingRecap = false },
        )
    }
}

@Composable
private fun bubbleMaxWidth(): Dp {
    val width = LocalConfiguration.current.screenWidthDp
    // With avatars: the list's side padding, an avatar on one side, and as much space
    // left open on the other, so a long bubble never runs up against the far edge.
    return if (LocalFaces.current != null) width.dp - 24.dp - AvatarSlot * 2 else (width * 0.78f).dp
}

@Composable
private fun TimeStamp(at: Long) {
    val palette = LocalGlassPalette.current
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        GlassSurface(
            style = palette.bar,
            shape = GlassShape.Capsule,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(Dates.chatStamp(at), color = palette.contentSecondary, fontSize = 12.sp)
        }
    }
}

/** Where the messages sent verbatim begin; tapping it shows the recap. */
@Composable
private fun RecapMark(aiName: String, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        GlassSurface(
            modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onClick),
            style = palette.bar,
            shape = GlassShape.Capsule,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text("往上的，${aiName.ifBlank { "TA" }}记成了前情提要 ›", color = palette.contentSecondary, fontSize = 12.sp)
        }
    }
}

/** The recap, to read and to put right. */
@Composable
private fun RecapDialog(aiName: String, recap: String?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(recap.orEmpty()) }
    val name = aiName.ifBlank { "TA" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("前情提要") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "往上的聊天不再原样发给$name，${name}靠这段记着；聊得越多，它会自己往下续。哪里记得不对，可以改。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(Recap.MAX_STORED) },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    showFace: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpenImage: (String) -> Unit,
    transcribing: Boolean = false,
    playingFile: String? = null,
    onPlay: (String) -> Unit = {},
    onRetryVoice: () -> Unit = {},
) {
    val palette = LocalGlassPalette.current
    val mine = message.role == "user"
    val audio = remember(message.audio) { MessageAudios.decode(message.audio) }
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val faces = LocalFaces.current
    val pictures = remember(message.images) { MessageImages.decode(message.images) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        // Without its face, the bubble still keeps the face's room: a run lines up.
        if (faces != null && !mine) {
            if (showFace) {
                Avatar(faces.ai.file, faces.ai.letter, AvatarSize)
                Spacer(Modifier.width(AvatarGap))
            } else {
                Spacer(Modifier.width(AvatarSlot))
            }
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            // One menu for the whole message, so a message that is only pictures has one too.
            Box {
                Column(
                    horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (pictures.isNotEmpty()) {
                        PictureGroup(pictures, onOpen = onOpenImage, onLongPress = { menu = true })
                    }
                    if (audio != null) {
                        VoiceBubble(
                            audio = audio,
                            mine = mine,
                            playing = playingFile == audio.file,
                            transcript = message.content,
                            transcribing = transcribing,
                            onClick = { onPlay(audio.file) },
                            onLongClick = { menu = true },
                        )
                    } else if (message.content.isNotEmpty()) {
                        GlassSurface(
                            modifier = Modifier
                                .widthIn(max = bubbleMaxWidth())
                                .combinedClickable(
                                    interactionSource = null,
                                    indication = null,
                                    onClick = {},
                                    onLongClick = { menu = true },
                                ),
                            style = if (mine) palette.bubbleMine else palette.bubble,
                            shape = GlassShape.Rounded(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                message.content,
                                color = if (mine) Color.White else palette.content,
                                fontSize = 16.sp,
                                lineHeight = 23.sp,
                            )
                        }
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (message.content.isNotEmpty()) {
                        DropdownMenuItem(text = { Text("复制") }, onClick = {
                            menu = false
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", message.content))) }
                        })
                    }
                    if (audio != null && message.content.isBlank() && !transcribing) {
                        DropdownMenuItem(text = { Text("重新转文字") }, onClick = {
                            menu = false
                            onRetryVoice()
                        })
                    }
                    if (!mine && canRetry) {
                        DropdownMenuItem(text = { Text("重新回答") }, onClick = {
                            menu = false
                            onRetry()
                        })
                    }
                    DropdownMenuItem(text = { Text("删除") }, onClick = {
                        menu = false
                        onDelete()
                    })
                }
            }
            val error = message.error
            if (error != null) {
                // On its own capsule: this line sits between bubbles, i.e. straight on the
                // wallpaper, and red text over a dark photo is unreadable.
                GlassSurface(
                    modifier = Modifier.padding(top = 4.dp).widthIn(max = bubbleMaxWidth()),
                    style = palette.notice,
                    shape = GlassShape.Rounded(14.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            error,
                            color = if (error == ChatRepository.STOPPED) palette.contentSecondary else palette.error,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        // A reply that failed is asked again; a voice message that wasn't transcribed, transcribed again.
                        if (canRetry && (!mine || audio != null)) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "重试",
                                color = palette.accentContent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.combinedClickable(onClick = if (mine) onRetryVoice else onRetry),
                            )
                        }
                        if (message.content.isEmpty()) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "删除",
                                color = palette.contentSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.combinedClickable(onClick = onDelete),
                            )
                        }
                    }
                }
            }
        }
        if (faces != null && mine) {
            if (showFace) {
                Spacer(Modifier.width(AvatarGap))
                Avatar(faces.me.file, faces.me.letter, AvatarSize)
            } else {
                Spacer(Modifier.width(AvatarSlot))
            }
        }
    }
}

/**
 * A voice message: its length, which it plays on a tap, and underneath, what it said once it
 * has been turned into text. A longer recording draws a longer bubble, the way chat apps do.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceBubble(
    audio: MessageAudio,
    mine: Boolean,
    playing: Boolean,
    transcript: String,
    transcribing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    val ink = if (mine) Color.White else palette.content
    val length = 92.dp + 150.dp * (audio.ms.toFloat() / Voice.MAX_MS).coerceIn(0f, 1f)
    GlassSurface(
        modifier = Modifier
            .widthIn(min = length, max = bubbleMaxWidth())
            .combinedClickable(interactionSource = null, indication = null, onClick = onClick, onLongClick = onLongClick),
        style = if (mine) palette.bubbleMine else palette.bubble,
        shape = GlassShape.Rounded(20.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "停下" else "播放语音",
                    tint = ink,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(Voice.duration(audio.ms), color = ink, fontSize = 15.sp)
            }
            when {
                transcript.isNotBlank() -> Text(transcript, color = ink.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp)
                transcribing -> Text("转文字中…", color = ink.copy(alpha = 0.7f), fontSize = 13.sp)
            }
        }
    }
}

/** Above the input while recording: how long, how loud, and what letting go does; or a short hint. */
@Composable
private fun RecordingPill(
    backdrop: Backdrop,
    recording: Boolean,
    ms: Long,
    level: Float,
    cancelling: Boolean,
    hint: String?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    Row(
        modifier
            .liquidGlass(backdrop, palette.input, GlassShape.Capsule)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!recording) {
            Text(hint.orEmpty(), color = palette.content, fontSize = 14.sp)
            return@Row
        }
        val tint = if (cancelling) palette.error else palette.accent
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp + 12.dp * level).background(tint, CircleShape))
        }
        Spacer(Modifier.width(8.dp))
        val s = ms / 1000
        Text("${s / 60}:%02d".format(s % 60), color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(12.dp))
        Text(if (cancelling) "松开取消" else "松开发送，上滑取消", color = if (cancelling) palette.error else palette.contentSecondary, fontSize = 14.sp)
    }
}

/**
 * Hold to talk: pressing starts a recording, letting go sends it, and sliding up first then
 * letting go throws it away. The pointer stays this button's until it is lifted, wherever it goes.
 */
@Composable
private fun MicButton(button: Modifier, recording: Boolean, onStart: () -> Boolean, onMove: (Boolean) -> Unit, onEnd: (Boolean) -> Unit) {
    val palette = LocalGlassPalette.current
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    Box(
        button
            .background(if (recording) palette.accent else palette.content.copy(alpha = 0.1f))
            .semantics { contentDescription = "按住说话" }
            .pointerInput(Unit) {
                val away = 72.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (!start()) return@awaitEachGesture
                    var cancel = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val up = down.position.y - change.position.y > away
                        if (up != cancel) {
                            cancel = up
                            move(cancel)
                        }
                        change.consume()
                    }
                    end(cancel)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Mic, contentDescription = null, tint = if (recording) Color.White else palette.content, modifier = Modifier.size(22.dp))
    }
}

/**
 * Pictures sent with a message, outside the bubble: a glass pane behind a photo would only
 * blur its edges. One picture keeps its shape; several become a grid of squares.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PictureGroup(pictures: List<MessageImage>, onOpen: (String) -> Unit, onLongPress: () -> Unit) {
    val c = appContainer()
    val maxWidth = minOf(bubbleMaxWidth(), 240.dp)
    fun Modifier.picture(file: String) = clip(RoundedCornerShape(18.dp)).combinedClickable(
        onClick = { onOpen(file) },
        onLongClick = onLongPress,
    )
    if (pictures.size == 1) {
        val p = pictures[0]
        val ratio = (p.width.toFloat() / p.height.coerceAtLeast(1)).coerceIn(0.6f, 1.8f)
        AsyncImage(
            model = c.images.file(p.file),
            contentDescription = "图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                // A tall photo is held to a height, so one picture doesn't fill the screen.
                .width(minOf(maxWidth, MaxPictureHeight * ratio))
                .aspectRatio(ratio)
                .picture(p.file),
        )
    } else {
        val cell = (maxWidth - 4.dp) / 2
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            pictures.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { p ->
                        AsyncImage(
                            model = c.images.file(p.file),
                            contentDescription = "图片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(cell).picture(p.file),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveBubble(live: StreamingReply, aiName: String, onAnswer: (ChatRepository.Answer) -> Unit) {
    val palette = LocalGlassPalette.current
    val faces = LocalFaces.current
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (live.text.isNotEmpty() || (live.activity == null && live.asking == null)) {
            Row {
                if (faces != null) {
                    Avatar(faces.ai.file, faces.ai.letter, AvatarSize)
                    Spacer(Modifier.width(AvatarGap))
                }
                GlassSurface(
                    modifier = Modifier.widthIn(max = bubbleMaxWidth()),
                    style = palette.bubble,
                    shape = GlassShape.Rounded(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    if (live.text.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TypingDots()
                            if (live.thinking) {
                                Spacer(Modifier.width(8.dp))
                                Text("在想", color = palette.contentSecondary, fontSize = 13.sp)
                            }
                        }
                    } else {
                        Text(live.text, color = palette.content, fontSize = 16.sp, lineHeight = 23.sp)
                    }
                }
            }
        }
        live.asking?.let { AskCard(it, aiName, onAnswer) }
        live.activity?.let { ToolNote(it + "…", Icons.Rounded.AutoAwesome, running = true) }
    }
}

/** A TA's call to an outside service, waiting for the person to allow it. */
@Composable
private fun AskCard(ask: McpAsk, aiName: String, onAnswer: (ChatRepository.Answer) -> Unit) {
    val palette = LocalGlassPalette.current
    val who = aiName.ifBlank { "TA" }
    GlassSurface(
        modifier = Modifier
            .padding(start = if (LocalFaces.current != null) AvatarSlot else 0.dp)
            .widthIn(max = bubbleMaxWidth()),
        style = palette.bubble,
        shape = GlassShape.Rounded(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Extension, contentDescription = null, tint = palette.accentContent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("${who}想用${ask.service}的「${ask.tool}」", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(ask.arguments, color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Pill("允许", accent = true, enabled = true) { onAnswer(ChatRepository.Answer.Yes) }
                Pill("以后都允许", accent = false, enabled = true) { onAnswer(ChatRepository.Answer.Always) }
                Pill("不允许", accent = false, enabled = true) { onAnswer(ChatRepository.Answer.No) }
            }
            Text("「以后都允许」只管这一个工具，在设置里能改回来。", color = palette.contentSecondary, fontSize = 12.sp)
        }
    }
}

/**
 * One line for something done on the way to a reply (记下了待办「交报告」), a notice from
 * the app, or the person's answer to a request ([mine], on their side). A capsule of its
 * own, like the error lines: it sits on the wallpaper.
 */
@Composable
private fun ToolNote(text: String, icon: ImageVector, running: Boolean = false, mine: Boolean = false) {
    val palette = LocalGlassPalette.current
    val slot = if (LocalFaces.current != null) AvatarSlot else 0.dp
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = if (mine) 0.dp else slot, end = if (mine) slot else 0.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        GlassSurface(
            modifier = Modifier.widthIn(max = bubbleMaxWidth()),
            style = palette.notice,
            shape = GlassShape.Rounded(14.dp),
            contentPadding = PaddingValues(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (running) palette.contentSecondary else palette.accentContent,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(text, color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

/**
 * The model asking to see a little secret. Only the person ever sees this card, so it can
 * show which entry (date and title) even though the model was never told the title.
 */
@Composable
private fun RequestCard(message: MessageEntity, aiName: String, enabled: Boolean, onAnswer: (Boolean) -> Unit) {
    val palette = LocalGlassPalette.current
    val request = remember(message.content) { SecretRequests.decode(message.content) } ?: return
    val who = aiName.ifBlank { "TA" }
    GlassSurface(
        modifier = Modifier
            .padding(start = if (LocalFaces.current != null) AvatarSlot else 0.dp)
            .widthIn(max = bubbleMaxWidth()),
        style = palette.bubble,
        shape = GlassShape.Rounded(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = palette.accentContent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("${who}想看你的小秘密", color = palette.content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                Dates.monthDay(LocalDate.ofEpochDay(request.day)) + " · " + request.title.ifBlank { "没有标题" },
                color = palette.contentSecondary,
                fontSize = 13.sp,
            )
            if (request.reason.isNotBlank()) {
                Text("「${request.reason}」", color = palette.content, fontSize = 15.sp, lineHeight = 21.sp)
            }
            when (request.status) {
                SecretRequest.PENDING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Pill("给${who}看", accent = true, enabled = enabled) { onAnswer(true) }
                        Pill("不给", accent = false, enabled = enabled) { onAnswer(false) }
                    }
                    Text("给看只是这一次，日记还是锁着的。", color = palette.contentSecondary, fontSize = 12.sp)
                }
                SecretRequest.GRANTED -> Text("给${who}看了", color = palette.contentSecondary, fontSize = 13.sp)
                SecretRequest.DECLINED -> Text("没给${who}看", color = palette.contentSecondary, fontSize = 13.sp)
                else -> Text("这个小秘密已经不在了", color = palette.contentSecondary, fontSize = 13.sp)
            }
        }
    }
}

/** A plain pill, not glass: it sits on a glass card, where glass would look like a hole. */
@Composable
private fun Pill(text: String, accent: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .background(if (accent) palette.accent else palette.content.copy(alpha = 0.08f), CircleShape)
            .clickable(enabled = enabled, interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (accent) Color.White else palette.content, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TypingDots() {
    val palette = LocalGlassPalette.current
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(520, delayMillis = i * 160), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).alpha(a).background(palette.content, CircleShape))
        }
    }
}

/**
 * One piece of glass holds everything: the picture button, the text, and send (stop while
 * a reply is being written). Pictures waiting to go sit above the text, inside the same
 * glass. A round send button beside the field would be a second glass pane, rendered
 * offscreen on every frame, for one button.
 */
@Composable
private fun ChatInputBar(
    backdrop: Backdrop,
    text: String,
    onTextChange: (String) -> Unit,
    attachments: List<MessageImage>,
    attaching: Boolean,
    onPick: () -> Unit,
    onRemove: (MessageImage) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    recording: Boolean = false,
    onVoiceStart: () -> Boolean = { false },
    onVoiceMove: (Boolean) -> Unit = {},
    onVoiceEnd: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    val c = appContainer()
    val canSend = text.isNotBlank() || attachments.isNotEmpty()
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .heightIn(min = BarHeight)
            .liquidGlass(backdrop, palette.input, GlassShape.Rounded(BarHeight / 2)),
    ) {
        if (attachments.isNotEmpty() || attaching) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 10.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { img -> AttachmentThumb(c.images.file(img.file)) { onRemove(img) } }
                if (attaching) {
                    Box(Modifier.size(64.dp).background(palette.content.copy(alpha = 0.08f), RoundedCornerShape(14.dp)))
                }
            }
        }
        // Each button sits in the middle of a square as tall as the bar: on one line it is
        // centred, concentric with the capsule's round end; as the text grows to more lines
        // the squares stay at the bottom. (Padding the buttons by hand left them 4dp from
        // the top and 8dp from the bottom: the text row was 48dp in a 50dp bar.)
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(BarHeight), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !busy && attachments.size < MAX_ATTACHMENTS, onClick = onPick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "发图片", tint = palette.contentSecondary, modifier = Modifier.size(24.dp))
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = BarHeight)
                    .padding(end = 4.dp, top = 13.dp, bottom = 13.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text("说点什么…", color = palette.contentSecondary, fontSize = 16.sp)
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = TextStyle(color = palette.content, fontSize = 16.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(palette.accentContent),
                    maxLines = 6,
                    // The placeholder above is drawn beside the field, so a screen reader
                    // would otherwise announce an unnamed text box.
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "输入消息" },
                )
            }
            Box(Modifier.size(BarHeight), contentAlignment = Alignment.Center) {
                // Plain fills inside the glass, like the chips on a card: glass in glass reads as a hole.
                val button = Modifier.size(38.dp).clip(CircleShape)
                if (busy) {
                    Box(
                        button.background(palette.content.copy(alpha = 0.1f)).clickable(onClick = onStop),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = "停止", tint = palette.content, modifier = Modifier.size(20.dp))
                    }
                } else if (!canSend) {
                    // Nothing typed: the button is for talking instead.
                    MicButton(button, recording, onVoiceStart, onVoiceMove, onVoiceEnd)
                } else {
                    Box(
                        button
                            .background(palette.accent)
                            .clickable(onClick = onSend),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = "发送", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentThumb(file: File, onRemove: () -> Unit) {
    Box(Modifier.size(64.dp)) {
        AsyncImage(
            model = file,
            contentDescription = "要发的图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(22.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "去掉这张", tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun NoKeyCard(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    GlassSurface(modifier = modifier, shape = GlassShape.Rounded(28.dp), contentPadding = PaddingValues(24.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有接上模型", color = palette.content, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            Text(
                "在设置里填一个 API 地址和 Key（DeepSeek、OpenAI 这类兼容接口都可以），就能开始聊了。",
                color = palette.contentSecondary,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(16.dp))
            GlassButton(
                onClick = onOpenSettings,
                backdrop = com.cleo.cleos.glass.LocalWallpaperBackdrop.current,
                style = palette.accentSurface,
                contentColor = Color.White,
            ) {
                Text("去设置", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
