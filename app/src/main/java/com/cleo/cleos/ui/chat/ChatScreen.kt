package com.cleo.cleos.ui.chat

import android.content.ClipData
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
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cleo.cleos.ai.ChatRepository
import com.cleo.cleos.ai.Prompt
import com.cleo.cleos.ai.SecretRequest
import com.cleo.cleos.ai.SecretRequests
import com.cleo.cleos.ai.StreamingReply
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

/**
 * Newest first, because the list is laid out bottom-up. Several messages in a row from one
 * side show the avatar once, beside the newest, the way chat apps stack them; a line or a
 * time between them starts a new run.
 */
private fun buildRows(messages: List<MessageEntity>): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + 8)
    var newerSide: Boolean? = null
    for (i in messages.indices.reversed()) {
        val m = messages[i]
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
    val rows = remember(state.messages) { buildRows(state.messages) }
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
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = inputBottom)
                    .onSizeChanged { inputHeight = it.height },
            )
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
                    item(key = "live") { LiveBubble(live) }
                }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ChatRow.Stamp -> TimeStamp(row.at)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    showFace: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onOpenImage: (String) -> Unit,
) {
    val palette = LocalGlassPalette.current
    val mine = message.role == "user"
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
                    if (message.content.isNotEmpty()) {
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
                        if (canRetry && !mine) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "重试",
                                color = palette.accentContent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.combinedClickable(onClick = onRetry),
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
private fun LiveBubble(live: StreamingReply) {
    val palette = LocalGlassPalette.current
    val faces = LocalFaces.current
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (live.text.isNotEmpty() || live.activity == null) {
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
        live.activity?.let { ToolNote(it + "…", Icons.Rounded.AutoAwesome, running = true) }
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
                } else {
                    Box(
                        button
                            .background(if (canSend) palette.accent else palette.accent.copy(alpha = 0.35f))
                            .clickable(enabled = canSend, onClick = onSend),
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
