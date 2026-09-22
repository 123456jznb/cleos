package com.cleo.cleos.ui.theme

import android.app.Activity
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.data.GlassMode
import com.cleo.cleos.data.ImageStore
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassPalette
import com.cleo.cleos.glass.GlassPalettes
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.LocalWallpaperBackdrop
import com.cleo.cleos.glass.WallpaperOverscan
import com.cleo.cleos.glass.backdropSource
import com.cleo.cleos.glass.overscan
import com.cleo.cleos.glass.rememberBackdrop
import com.cleo.cleos.ui.wallpaper.DefaultWallpaper
import com.cleo.cleos.ui.wallpaper.WallpaperAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The root of every screen: decides light or dark glass and the accent from the
 * wallpaper, draws the wallpaper once as the backdrop everything else can look
 * through, and gives Material components colours that match the glass.
 */
@Composable
fun CleosTheme(settings: AppSettings, images: ImageStore, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val custom = settings.wallpaper
    val dark = when (settings.glassMode) {
        GlassMode.Light -> false
        GlassMode.Dark -> true
        GlassMode.Auto -> if (custom != null) settings.wallpaperDark ?: systemDark else systemDark
    }
    val palette = remember(dark, custom, settings.wallpaperHue, settings.wallpaperChroma) {
        if (custom != null && settings.wallpaperHue != null) {
            GlassPalettes.build(dark, settings.wallpaperHue, WallpaperAnalyzer.chromaScale(settings.wallpaperChroma))
        } else {
            GlassPalettes.build(dark)
        }
    }
    val wallpaperBackdrop = rememberBackdrop(flat = true)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !palette.dark
                isAppearanceLightNavigationBars = !palette.dark
            }
        }
    }

    MaterialTheme(colorScheme = colorSchemeFor(palette)) {
        CompositionLocalProvider(
            LocalGlassPalette provides palette,
            LocalWallpaperBackdrop provides wallpaperBackdrop,
        ) {
            Box(Modifier.fillMaxSize()) {
                Wallpaper(custom, dark, images, wallpaperBackdrop)
                content()
            }
        }
    }
}

@Composable
private fun Wallpaper(file: String?, dark: Boolean, images: ImageStore, backdrop: Backdrop) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val longEdge = with(density) { max(config.screenWidthDp, config.screenHeightDp).dp.roundToPx() }
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        bitmap = if (file == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val f = images.file(file)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, bounds)
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longEdge) sample *= 2
                    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val modifier = Modifier.fillMaxSize().overscan(WallpaperOverscan).backdropSource(backdrop)
    val image = bitmap
    if (file != null && image != null) {
        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        DefaultWallpaper(dark, modifier)
    }
}

private fun colorSchemeFor(p: GlassPalette) = if (p.dark) {
    darkColorScheme(
        primary = p.accentContent,
        onPrimary = Color(0xFF15131B),
        primaryContainer = p.accent,
        onPrimaryContainer = Color.White,
        secondary = p.accentContent,
        background = Color(0xFF15131C),
        onBackground = p.content,
        surface = Color(0xFF1F1D26),
        onSurface = p.content,
        onSurfaceVariant = p.contentSecondary,
        surfaceContainerLowest = Color(0xFF15131C),
        surfaceContainerLow = Color(0xFF1C1A23),
        surfaceContainer = Color(0xFF221F29),
        surfaceContainerHigh = Color(0xFF28252F),
        surfaceContainerHighest = Color(0xFF2E2B36),
        outline = p.content.copy(alpha = 0.35f),
        outlineVariant = p.content.copy(alpha = 0.14f),
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = Color.White,
        primaryContainer = p.accent.copy(alpha = 0.14f),
        onPrimaryContainer = p.accentContent,
        secondary = p.accentContent,
        background = Color(0xFFF7F5FA),
        onBackground = p.content,
        surface = Color(0xFFFCFBFE),
        onSurface = p.content,
        onSurfaceVariant = p.contentSecondary,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF8F6FB),
        surfaceContainer = Color(0xFFF3F1F7),
        surfaceContainerHigh = Color(0xFFEDEAF2),
        surfaceContainerHighest = Color(0xFFE7E4ED),
        outline = p.content.copy(alpha = 0.35f),
        outlineVariant = p.content.copy(alpha = 0.12f),
    )
}
