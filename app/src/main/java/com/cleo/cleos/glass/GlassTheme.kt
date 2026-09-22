package com.cleo.cleos.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cleo.cleos.ui.theme.Oklab

/**
 * Everything a screen needs to draw glass consistently: whether the glass is light or
 * dark, the accent, the colours for text on glass, and the style families.
 *
 * Light vs dark follows the wallpaper, not the system theme: dark glass over a pale
 * wallpaper (or the reverse) is exactly the case where text on glass stops being
 * readable.
 */
@Immutable
data class GlassPalette(
    val dark: Boolean,
    /** Fills that carry white text: the user's bubbles, primary buttons. */
    val accent: Color,
    /** Accent-coloured text and icons sitting directly on glass. */
    val accentContent: Color,
    val content: Color,
    val contentSecondary: Color,
    /** Bars and buttons: clear, strongly refracting. */
    val chrome: GlassStyle,
    /**
     * Chrome that carries words (a title, the text being typed). Same bend as [chrome],
     * but with enough tint that the words stay readable while content scrolls beneath.
     */
    val bar: GlassStyle,
    /** Surfaces with body text: bubbles, cards. Tinted enough to guarantee contrast. */
    val surface: GlassStyle,
    /** The user's own bubbles: accent glass. */
    val accentSurface: GlassStyle,
    /** Tab bar selection at rest, and held. */
    val lensRest: GlassStyle,
    val lensHeld: GlassStyle,
)

object GlassPalettes {
    /** Violet, the accent when the wallpaper has no colour worth following. */
    const val DEFAULT_HUE = 4.93f // radians, ~282°

    fun build(dark: Boolean, hue: Float = DEFAULT_HUE, chromaScale: Float = 1f): GlassPalette {
        // Accent fills always sit at OKLab L 0.52: dark enough that white text on them
        // clears 4.5:1 even after the 10% of glass tint lets a white wallpaper through.
        val accent = Oklab.lch(0.52f, 0.15f * chromaScale, hue)
        val accentContent = if (dark) Oklab.lch(0.80f, 0.11f * chromaScale, hue) else Oklab.lch(0.48f, 0.15f * chromaScale, hue)
        return if (dark) {
            GlassPalette(
                dark = true,
                accent = accent,
                accentContent = accentContent,
                content = Color(0xFFF3F1F7),
                contentSecondary = Color(0xFFF3F1F7).copy(alpha = 0.66f),
                chrome = GlassStyle(
                    blur = 3.dp, refraction = 22.dp, bevel = 18.dp, dispersion = 0.35f,
                    tint = Color(0xFF16141C).copy(alpha = 0.30f), saturation = 1.5f, lift = -0.02f,
                    highlight = 0.55f, shadowAlpha = 0f,
                ),
                bar = GlassStyle(
                    blur = 8.dp, refraction = 20.dp, bevel = 16.dp, dispersion = 0.3f,
                    tint = Color(0xFF16141C).copy(alpha = 0.58f), saturation = 1.4f, lift = -0.02f,
                    highlight = 0.55f, shadowAlpha = 0f,
                ),
                surface = GlassStyle(
                    blur = 14.dp, refraction = 12.dp, bevel = 12.dp, dispersion = 0.2f,
                    tint = Color(0xFF1B1922).copy(alpha = 0.64f), saturation = 1.3f, lift = 0f,
                    highlight = 0.45f, shadowAlpha = 0f,
                ),
                accentSurface = GlassStyle(
                    blur = 14.dp, refraction = 12.dp, bevel = 12.dp, dispersion = 0.2f,
                    tint = accent.copy(alpha = 0.9f), saturation = 1.2f, lift = 0f,
                    highlight = 0.5f, shadowAlpha = 0f,
                ),
                lensRest = GlassStyle(
                    blur = 0.dp, refraction = 10.dp, bevel = 12.dp, dispersion = 0.3f,
                    tint = Color.White.copy(alpha = 0.10f), saturation = 1.2f, lift = 0.02f,
                    highlight = 0.6f, shadowAlpha = 0f,
                ),
                lensHeld = GlassStyle(
                    blur = 0.dp, refraction = 18.dp, bevel = 16.dp, dispersion = 0.6f,
                    tint = Color.White.copy(alpha = 0.03f), saturation = 1.3f, lift = 0.03f,
                    highlight = 0.95f, shadowAlpha = 0f, zoom = 1.18f,
                ),
            )
        } else {
            GlassPalette(
                dark = false,
                accent = accent,
                accentContent = accentContent,
                content = Color(0xFF1C1A22),
                contentSecondary = Color(0xFF1C1A22).copy(alpha = 0.6f),
                chrome = GlassStyle(
                    blur = 2.dp, refraction = 22.dp, bevel = 18.dp, dispersion = 0.35f,
                    tint = Color.White.copy(alpha = 0.16f), saturation = 1.5f, lift = 0.03f,
                    highlight = 0.8f, shadowAlpha = 0.08f,
                ),
                bar = GlassStyle(
                    blur = 8.dp, refraction = 20.dp, bevel = 16.dp, dispersion = 0.3f,
                    tint = Color.White.copy(alpha = 0.55f), saturation = 1.4f, lift = 0.02f,
                    highlight = 0.8f, shadowAlpha = 0.07f,
                ),
                surface = GlassStyle(
                    blur = 14.dp, refraction = 12.dp, bevel = 12.dp, dispersion = 0.2f,
                    tint = Color.White.copy(alpha = 0.62f), saturation = 1.3f, lift = 0f,
                    highlight = 0.6f, shadowAlpha = 0.07f,
                ),
                accentSurface = GlassStyle(
                    blur = 14.dp, refraction = 12.dp, bevel = 12.dp, dispersion = 0.2f,
                    tint = accent.copy(alpha = 0.9f), saturation = 1.2f, lift = 0f,
                    highlight = 0.55f, shadowAlpha = 0.08f,
                ),
                lensRest = GlassStyle(
                    blur = 0.dp, refraction = 10.dp, bevel = 12.dp, dispersion = 0.3f,
                    tint = Color.White.copy(alpha = 0.34f), saturation = 1.2f, lift = 0.02f,
                    highlight = 0.7f, shadowAlpha = 0f,
                ),
                lensHeld = GlassStyle(
                    blur = 0.dp, refraction = 18.dp, bevel = 16.dp, dispersion = 0.6f,
                    tint = Color.White.copy(alpha = 0.06f), saturation = 1.3f, lift = 0.03f,
                    highlight = 1f, shadowAlpha = 0.12f, zoom = 1.18f,
                ),
            )
        }
    }
}

val LocalGlassPalette = staticCompositionLocalOf { GlassPalettes.build(dark = false) }

/** The wallpaper on its own, for glass that sits inside scrolling content. */
val LocalWallpaperBackdrop = staticCompositionLocalOf<Backdrop> {
    error("LocalWallpaperBackdrop is provided at the root of the app")
}
