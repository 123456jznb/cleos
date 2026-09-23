package com.cleo.cleos.ui.chat

import android.content.ClipData
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.cleo.cleos.ai.ChatRepository
import com.cleo.cleos.ai.StreamingReply
import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassButton
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.liquidGlass
import com.cleo.cleos.ui.common.Dates
import com.cleo.cleos.ui.common.GlassPage
import com.cleo.cleos.ui.common.GlassTopBar
import com.cleo.cleos.ui.common.TopBarHeight
import com.cleo.cleos.ui.common.appViewModel
import com.cleo.cleos.ui.common.fadeUnderTopBar
import kotlinx.coroutines.launch

/** A gap longer than this between two messages gets a time line between them. */
private const val TIME_GAP_MS = 10 * 60 * 1000L

private sealed interface ChatRow {
    val key: Any

    data class Stamp(val at: Long) : ChatRow {
        override val key: Any get() = "t$at"
    }

    data class Message(val message: MessageEntity, val isLast: Boolean) : ChatRow {
        override val key: Any get() = message.id
    }
}

/** Newest first, because the list is laid out bottom-up. */
private fun buildRows(messages: List<MessageEntity>): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + 8)
    for (i in messages.indices.reversed()) {
        val m = messages[i]
        rows += ChatRow.Message(m, isLast = i == messages.lastIndex)
        val prev = messages.getOrNull(i - 1)
        if (prev == null || m.createdAt - prev.createdAt > TIME_GAP_MS) rows += ChatRow.Stamp(m.createdAt)
    }
    return rows
}

@Composable
fun ChatTab(
    bottomInset: Dp,
    onOpenSettings: () -> Unit,
    onOpenConversations: () -> Unit,
) {
    val vm = appViewModel { ChatViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    var inputHeight by remember { mutableIntStateOf(0) }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val inputBottom = if (imeBottom > bottomInset) imeBottom + 8.dp else bottomInset
    val rows = remember(state.messages) { buildRows(state.messages) }

    // Follow new messages, unless the user has scrolled up to read something older.
    LaunchedEffect(state.messages.lastOrNull()?.id, state.streaming?.text?.isEmpty()) {
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }

    GlassPage(
        overlay = { page ->
            GlassTopBar(
                title = state.aiName.ifBlank { "聊天" },
                subtitle = state.model.takeIf { it.isNotBlank() },
                backdrop = page,
                leading = { GlassIconButton(Icons.Rounded.Forum, "对话记录", onOpenConversations, page) },
                trailing = {
                    GlassIconButton(Icons.Rounded.AddComment, "新对话", vm::newConversation, page)
                    GlassIconButton(Icons.Rounded.Settings, "设置", onOpenSettings, page)
                },
            )
            ChatInputBar(
                backdrop = page,
                text = input,
                onTextChange = { input = it },
                busy = state.streaming != null && state.streaming?.savedId == null,
                onSend = {
                    vm.send(input)
                    input = ""
                },
                onStop = vm::stop,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = inputBottom)
                    .onSizeChanged { inputHeight = it.height },
            )
        },
    ) {
        val inputTop = inputBottom + with(density) { inputHeight.toDp() }
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
                    is ChatRow.Message -> MessageBubble(
                        message = row.message,
                        canRetry = row.isLast && state.streaming == null,
                        onRetry = { vm.retry(row.message.id) },
                        onDelete = { vm.delete(row.message.id) },
                    )
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
private fun bubbleMaxWidth(): Dp = (LocalConfiguration.current.screenWidthDp * 0.78f).dp

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
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    val mine = message.role == "user"
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        if (message.content.isNotEmpty()) {
            Box {
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
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("复制") }, onClick = {
                        menu = false
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", message.content))) }
                    })
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
}

@Composable
private fun LiveBubble(live: StreamingReply) {
    val palette = LocalGlassPalette.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
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

@Composable
private fun ChatInputBar(
    backdrop: Backdrop,
    text: String,
    onTextChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 50.dp)
                .liquidGlass(backdrop, palette.input, GlassShape.Rounded(25.dp))
                .padding(horizontal = 18.dp, vertical = 13.dp),
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
        Spacer(Modifier.width(8.dp))
        if (busy) {
            GlassIconButton(Icons.Rounded.Stop, "停止", onStop, backdrop, size = 50.dp)
        } else {
            GlassIconButton(
                Icons.Rounded.ArrowUpward,
                "发送",
                onSend,
                backdrop,
                style = palette.accentSurface,
                tint = Color.White,
                enabled = text.isNotBlank(),
                size = 50.dp,
            )
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
