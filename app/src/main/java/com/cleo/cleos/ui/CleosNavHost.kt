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
import com.cleo.cleos.ui.diary.DiaryEditorScreen
import com.cleo.cleos.ui.diary.ImageViewerScreen
import com.cleo.cleos.ui.lab.GlassLabScreen
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

/** id 0 means a new entry. */
@Serializable
data class DiaryRoute(val id: Long)

@Serializable
data class ImageRoute(val file: String)

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
                onOpenDiaryEntry = { nav.go(DiaryRoute(it)) },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = nav::back, onOpenLab = { nav.go(LabRoute) })
        }
        composable<LabRoute> { GlassLabScreen(onBack = nav::back) }
        composable<ConversationsRoute> { ConversationsScreen(onBack = nav::back) }
        composable<DiaryRoute> { entry ->
            DiaryEditorScreen(
                id = entry.toRoute<DiaryRoute>().id,
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
