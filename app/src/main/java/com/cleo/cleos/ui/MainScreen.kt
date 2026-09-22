package com.cleo.cleos.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.cleo.cleos.glass.GlassTab
import com.cleo.cleos.glass.GlassTabBar
import com.cleo.cleos.glass.LocalWallpaperBackdrop
import com.cleo.cleos.glass.WallpaperOverscan
import com.cleo.cleos.glass.backdropSource
import com.cleo.cleos.glass.rememberBackdrop
import com.cleo.cleos.ui.chat.ChatTab
import com.cleo.cleos.ui.diary.DiaryTab
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
    onOpenDiaryEntry: (Long) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
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
        )
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .backdropSource(page, behind = LocalWallpaperBackdrop.current, overscan = WallpaperOverscan),
        ) {
            holder.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> ChatTab(bottomInset, onOpenSettings, onOpenConversations)
                    1 -> DiaryTab(bottomInset, onOpenDiaryEntry, onOpenSettings)
                    else -> TodoTab(bottomInset, onOpenSettings)
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
