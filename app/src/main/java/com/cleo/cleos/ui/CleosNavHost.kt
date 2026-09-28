package com.cleo.cleos.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.cleo.cleos.ui.chat.ConversationsScreen
import com.cleo.cleos.ui.chat.SearchScreen
import com.cleo.cleos.ui.diary.DiaryEditorScreen
import com.cleo.cleos.ui.diary.ImageViewerScreen
import com.cleo.cleos.ui.lab.GlassLabScreen
import com.cleo.cleos.ui.letters.LetterScreen
import com.cleo.cleos.ui.letters.LettersScreen
import com.cleo.cleos.ui.memory.MemoryEditScreen
import com.cleo.cleos.ui.memory.MemoryScreen
import com.cleo.cleos.ui.settings.McpEditScreen
import com.cleo.cleos.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
object MainRoute

@Serializable
object SettingsRoute

@Serializable
object LabRoute

@Serializable
object ConversationsRoute

/** Finding what was said with the current TA. */
@Serializable
object SearchRoute

/** id 0 means a new entry; [secret] says whether a new one starts locked. */
@Serializable
data class DiaryRoute(val id: Long, val secret: Boolean = false)

@Serializable
data class ImageRoute(val file: String)

@Serializable
object LettersRoute

/** id 0 means a new letter to the current TA. */
@Serializable
data class LetterRoute(val id: Long)

@Serializable
object MemoryRoute

/** id 0 means a new memory, written by the person, for the current TA. */
@Serializable
data class MemoryEditRoute(val id: Long)

/** An MCP service to edit; an empty id adds one. */
@Serializable
data class McpEditRoute(val id: String)

/**
 * Screens cross-fade rather than slide. Glass samples what is behind it at the position
 * it was last laid out at; a screen sliding in via a transform moves without being laid
 * out again, so its glass would show the wallpaper from where the screen started.
 */
@Composable
fun CleosNavHost() {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = MainRoute,
        enterTransition = { fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(180)) },
    ) {
        composable<MainRoute> {
            MainScreen(
                onOpenSettings = { nav.go(SettingsRoute) },
                onOpenConversations = { nav.go(ConversationsRoute) },
                onOpenDiaryEntry = { id, secret -> nav.go(DiaryRoute(id, secret)) },
                onOpenImage = { nav.go(ImageRoute(it)) },
                onOpenLetters = { nav.go(LettersRoute) },
                onOpenMemory = { nav.go(MemoryRoute) },
            )
        }
        composable<LettersRoute> { LettersScreen(onBack = nav::back, onOpen = { nav.go(LetterRoute(it)) }) }
        composable<LetterRoute> { entry -> LetterScreen(entry.toRoute<LetterRoute>().id, onBack = nav::back) }
        composable<MemoryRoute> { MemoryScreen(onBack = nav::back, onOpen = { nav.go(MemoryEditRoute(it)) }) }
        composable<MemoryEditRoute> { entry -> MemoryEditScreen(entry.toRoute<MemoryEditRoute>().id, onBack = nav::back) }
        composable<SettingsRoute> {
            SettingsScreen(onBack = nav::back, onOpenLab = { nav.go(LabRoute) }, onOpenMcp = { nav.go(McpEditRoute(it)) })
        }
        composable<LabRoute> { GlassLabScreen(onBack = nav::back) }
        composable<McpEditRoute> { entry -> McpEditScreen(entry.toRoute<McpEditRoute>().id, onBack = nav::back) }
        composable<ConversationsRoute> { ConversationsScreen(onBack = nav::back, onSearch = { nav.go(SearchRoute) }) }
        // A result opens in the chat: back past the conversation list too.
        composable<SearchRoute> { SearchScreen(onBack = nav::back, onFound = { nav.popBackStack(MainRoute, inclusive = false) }) }
        composable<DiaryRoute> { entry ->
            val route = entry.toRoute<DiaryRoute>()
            DiaryEditorScreen(
                id = route.id,
                startSecret = route.secret,
                onBack = nav::back,
                onOpenImage = { nav.go(ImageRoute(it)) },
            )
        }
        composable<ImageRoute> { entry -> ImageViewerScreen(entry.toRoute<ImageRoute>().file, onBack = nav::back) }
    }
}

/**
 * Pops only while the current screen is fully shown. A second tap on "back" during the
 * fade-out would otherwise pop the screen underneath too, down to an empty host.
 */
private fun NavHostController.back() {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) popBackStack()
}

/**
 * Same guard going forward: a quick double tap (or a key repeating on a focused button)
 * would otherwise stack the same screen two or three times, and "back" would then seem
 * not to work.
 */
private fun NavHostController.go(route: Any) {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) navigate(route)
}
