package com.cleo.cleos.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.Opening
import com.cleo.cleos.glass.GlassTab
import com.cleo.cleos.glass.GlassTabBar
import com.cleo.cleos.glass.LocalWallpaperBackdrop
import com.cleo.cleos.glass.WallpaperOverscan
import com.cleo.cleos.glass.backdropSource
import com.cleo.cleos.glass.rememberBackdrop
import com.cleo.cleos.ui.chat.ChatTab
import com.cleo.cleos.ui.common.appContainer
import com.cleo.cleos.ui.diary.DiaryTab
import com.cleo.cleos.ui.home.HomeTab
import com.cleo.cleos.ui.todo.TodoTab

/**
 * Space the floating tab bar takes above the navigation bar (its 14dp overhang, the 64dp
 * bar, and a gap), which each tab reserves at the bottom of its content.
 */
private val TabBarReserve = 88.dp

@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenConversations: () -> Unit,
    onOpenDiaryEntry: (id: Long, secret: Boolean) -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenLetters: () -> Unit,
    onOpenMemory: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // A notification about a conversation was tapped: the chat, which already shows it.
    val c = appContainer()
    val opening by c.opening.collectAsStateWithLifecycle()
    LaunchedEffect(opening) {
        if (opening is Opening.Chat) {
            tab = 0
            c.opening.value = null
        }
    }
    // Tab pages slide across rather than cutting. `from` is the page being left and
    // `slide` runs 0..1; while they differ both pages exist, and only then.
    var from by remember { mutableIntStateOf(tab) }
    val slide = remember { Animatable(1f) }
    var pageWidth by remember { mutableIntStateOf(0) }
    LaunchedEffect(tab) {
        val target = tab
        if (from == target) return@LaunchedEffect
        slide.snapTo(0f)
        // Two frames for the page coming in to build and draw itself off to the side, where
        // nobody sees it, before anything moves: its first composition and its glass are the
        // heavy part, and inside the slide they cost a frame the eye catches.
        withFrameNanos { }
        withFrameNanos { }
        slide.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        // Left until the end on purpose: it is what keeps the outgoing page alive, and
        // if this coroutine is cancelled by another tap it stays put, so the next slide
        // starts from the page actually on screen instead of jumping.
        from = target
    }

    val holder = rememberSaveableStateHolder()
    val page = rememberBackdrop()
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    val bottomInset = navBottom + TabBarReserve
    val tabs = remember {
        listOf(
            GlassTab("聊天", Icons.Outlined.ChatBubbleOutline, Icons.Rounded.ChatBubble),
            GlassTab("日记", Icons.Outlined.AutoStories, Icons.Rounded.AutoStories),
            GlassTab("待办", Icons.Outlined.TaskAlt, Icons.Rounded.TaskAlt),
            GlassTab("主页", Icons.Outlined.Home, Icons.Rounded.Home),
        )
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { pageWidth = it.width }
                .backdropSource(page, behind = LocalWallpaperBackdrop.current, overscan = WallpaperOverscan),
        ) {
            @Composable
            fun Page(index: Int, shift: () -> Float) {
                Box(
                    // ⚠️ `offset` with a lambda, not a transform. It moves the page by
                    // **placing it somewhere else**, which is a real layout position, so
                    // the glass inside the page is told it moved and re-reads the
                    // wallpaper under its new spot. A page slid by a transform would keep
                    // refracting the wallpaper from where it started — the reason
                    // CleosNavHost cross-fades instead of sliding.
                    //
                    // Reading the animation in here and nowhere else also keeps the whole
                    // page out of recomposition: it is re-placed each frame, not rebuilt.
                    Modifier.fillMaxSize().offset {
                        IntOffset((shift() * pageWidth).roundToInt(), 0)
                    },
                ) {
                    holder.SaveableStateProvider(index) {
                        when (index) {
                            0 -> ChatTab(bottomInset, onOpenSettings, onOpenConversations, onOpenImage)
                            1 -> DiaryTab(bottomInset, onOpenDiaryEntry, onOpenSettings)
                            2 -> TodoTab(bottomInset, onOpenSettings)
                            else -> HomeTab(bottomInset, onOpenSettings, onOpenLetters, onOpenMemory)
                        }
                    }
                }
            }

            // Only these two are ever on screen: tab 0 to tab 3 slides once, it does not flip
            // through 1 and 2 — they would show for a few frames, each paying to build itself,
            // for a glimpse nobody asked for.
            //
            // Each page under its own key, wherever it stands in the list: the page on screen
            // before a slide is the same page during it, and the one that slid in is the same
            // page after. Drawn from two places in the code instead (one for still, two for
            // sliding), every start and end of a slide threw the page on screen away and built
            // it again, and for a frame its glass had nothing under it: the black flash.
            val dir = if (tab > from) 1f else -1f
            val shown = if (from == tab) listOf(tab) else listOf(from, tab)
            for (index in shown) {
                key(index) {
                    Page(index) {
                        when {
                            from == tab -> 0f
                            index == from -> -slide.value * dir
                            else -> (1f - slide.value) * dir
                        }
                    }
                }
            }
        }
        // The keyboard covers the bar anyway; sliding it away lets the input sit on the keys.
        AnimatedVisibility(
            visible = !keyboardOpen,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            GlassTabBar(
                tabs = tabs,
                selectedIndex = tab,
                onSelect = { tab = it },
                backdrop = page,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 28.dp)
                    .fillMaxWidth(),
            )
        }
    }
}
