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
    /** Error text, dark enough on light glass (light enough on dark) to read. */
    val error: Color,
    /**
     * For short text that would otherwise sit straight on the wallpaper (notices, hints,
     * error lines): a capsule tinted heavily enough (0.82) that the text reads over any
     * photo. Bare text on a wallpaper is only readable until the wallpaper changes.
     */
    val notice: GlassStyle,
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
    /**
     * Each tunable part after the user's tuning and the contrast floor. Empty until
     * [GlassPalettes.build] fills it; the accessors below fall back to the families.
     */
    val parts: Map<GlassPart, GlassStyle> = emptyMap(),
    /** Each part's untuned default: where the glass lab starts and what "reset" returns to. */
    val partDefaults: Map<GlassPart, GlassStyle> = emptyMap(),
    val bubbleMineStyle: GlassStyle? = null,
    val topBarTitleStyle: GlassStyle? = null,
    /** Luminance of the wallpaper's darkest / brightest patch, when known. */
    val troughLum: Float? = null,
    val peakLum: Float? = null,
) {
    val bubble: GlassStyle get() = parts[GlassPart.Bubble] ?: surface
    val card: GlassStyle get() = parts[GlassPart.Card] ?: surface
    val topBar: GlassStyle get() = parts[GlassPart.TopBar] ?: chrome
    val tabBar: GlassStyle get() = parts[GlassPart.TabBar] ?: chrome
    val input: GlassStyle get() = parts[GlassPart.Input] ?: bar

    /** The user's own bubbles: the bubble tuning, in the accent colour. */
    val bubbleMine: GlassStyle get() = bubbleMineStyle ?: accentSurface

    /** The title capsule in the top bar: the top bar's glass, but never less tinted than [bar]. */
    val topBarTitle: GlassStyle get() = topBarTitleStyle ?: bar

    /** [style] as the app would actually draw it for [part] (with the contrast floor). */
    fun floored(part: GlassPart, style: GlassStyle): GlassStyle = GlassPalettes.floor(this, part, style)
}

object GlassPalettes {
    /** Violet, the accent when the wallpaper has no colour worth following. */
    const val DEFAULT_HUE = 4.93f // radians, ~282°

    /**
     * [trough]/[peak]: luminance of the wallpaper's darkest / brightest patch.
     * [tuning]: what the user saved in the glass lab, per part.
     */
    fun build(
        dark: Boolean,
        hue: Float = DEFAULT_HUE,
        chromaScale: Float = 1f,
        trough: Float? = null,
        peak: Float? = null,
        tuning: Map<GlassPart, GlassTuning> = emptyMap(),
    ): GlassPalette {
        val b = base(dark, hue, chromaScale).copy(troughLum = trough, peakLum = peak)
        val worst = b.worstLum
        val floored = b.copy(chrome = raise(b, b.chrome, worst, withAccent = true), bar = raise(b, b.bar, worst, withAccent = true))

        val defaults = mapOf(
            GlassPart.Bubble to floored.surface,
            GlassPart.Card to floored.surface,
            GlassPart.TopBar to b.chrome,
            GlassPart.TabBar to b.chrome,
            GlassPart.Input to b.bar,
        )
        val parts = defaults.mapValues { (part, style) ->
            floor(floored, part, tuning[part]?.applyTo(style, dark) ?: style)
        }
        // The user's own bubbles take the bubble tuning in the accent colour. Their text
        // is white, so what threatens it is the brightest patch, on light and dark glass
        // alike; with no wallpaper information, assume white.
        val mineTuned = tuning[GlassPart.Bubble]?.applyTo(b.accentSurface, dark) ?: b.accentSurface
        val mine = raiseFor(mineTuned, peak ?: 1f, Color.White, 4.5f)
        val topBar = parts.getValue(GlassPart.TopBar)
        val title = topBar.copy(tint = topBar.tint.copy(alpha = maxOf(topBar.tint.alpha, floored.bar.tint.alpha)))
        return floored.copy(
            parts = parts,
            partDefaults = defaults,
            bubbleMineStyle = mine,
            topBarTitleStyle = title,
        )
    }

    private val GlassPalette.worstLum: Float? get() = if (dark) peakLum else troughLum

    /**
     * The contrast floor for one part. Clear glass looks best, but light-or-dark is
     * decided for the wallpaper as a whole: a photo with a bright sky and dark hills gets
     * light glass, and a bar then sits over the hills with dark labels on barely tinted
     * glass. So tints are raised, only as far as needed, until text clears 4.5:1 over the
     * worst patch of the wallpaper (the darkest for light glass, the brightest for dark).
     * The user can tune a part as clear as they like; this is what keeps the words on it
     * readable anyway.
     */
    internal fun floor(p: GlassPalette, part: GlassPart, style: GlassStyle): GlassStyle =
        raise(p, style, p.worstLum, withAccent = part == GlassPart.TopBar || part == GlassPart.TabBar)

    private fun raise(p: GlassPalette, style: GlassStyle, worstLum: Float?, withAccent: Boolean): GlassStyle {
        if (worstLum == null) return style
        // Text at 4.5:1; accent icons and bold accent labels (the selected tab) at 3:1,
        // the level for graphics and large text.
        val needText = minTintAlpha(style.tint, worstLum, p.content, 4.5f)
        val need = if (withAccent) maxOf(needText, minTintAlpha(style.tint, worstLum, p.accentContent, 3f)) else needText
        return atLeast(style, need)
    }

    private fun raiseFor(style: GlassStyle, worstLum: Float, text: Color, ratio: Float): GlassStyle =
        atLeast(style, minTintAlpha(style.tint, worstLum, text, ratio))

    /**
     * Rounded *up* to the next 1/255: an sRGB Color keeps 8 bits of alpha, and letting the
     * nearest step round down left white-on-accent at 4.48:1 against a 4.5 target.
     */
    private fun atLeast(style: GlassStyle, need: Float): GlassStyle {
        if (need <= style.tint.alpha) return style
        val stored = (kotlin.math.ceil(need * 255f) / 255f).coerceAtMost(1f)
        return style.copy(tint = style.tint.copy(alpha = stored))
    }

    /**
     * Smallest alpha of [tint] over a grey of luminance [bgLum] that gives [content]
     * [ratio]:1. The mix is done on sRGB values, the same way the shader mixes, and the
     * contrast is measured on luminance, so it is found by bisection.
     */
    private fun minTintAlpha(tint: Color, bgLum: Float, content: Color, ratio: Float): Float {
        val bg = Oklab.fromLinear(bgLum.coerceIn(0f, 1f))
        val lc = Oklab.luminance(content.red, content.green, content.blue)
        fun contrast(a: Float): Float {
            val l = Oklab.luminance(
                a * tint.red + (1 - a) * bg,
                a * tint.green + (1 - a) * bg,
                a * tint.blue + (1 - a) * bg,
            )
            return (maxOf(l, lc) + 0.05f) / (minOf(l, lc) + 0.05f)
        }
        if (contrast(0f) >= ratio) return 0f
        if (contrast(1f) < ratio) return 1f
        var lo = 0f
        var hi = 1f
        repeat(18) {
            val mid = (lo + hi) / 2f
            if (contrast(mid) >= ratio) hi = mid else lo = mid
        }
        return hi
    }

    private fun base(dark: Boolean, hue: Float, chromaScale: Float): GlassPalette {
        // Accent fills always sit at OKLab L 0.52: dark enough that white text on them
        // clears 4.5:1 even after the 10% of glass tint lets a white wallpaper through.
        val accent = Oklab.lch(0.52f, 0.15f * chromaScale, hue)
        val accentContent = if (dark) Oklab.lch(0.80f, 0.11f * chromaScale, hue) else Oklab.lch(0.44f, 0.14f * chromaScale, hue)
        return if (dark) {
            GlassPalette(
                dark = true,
                accent = accent,
                accentContent = accentContent,
                content = Color(0xFFF3F1F7),
                contentSecondary = Color(0xFFF3F1F7).copy(alpha = 0.66f),
                error = Color(0xFFFF8F87),
                notice = GlassStyle(
                    blur = 10.dp, refraction = 10.dp, bevel = 10.dp, dispersion = 0.2f,
                    tint = Color(0xFF1B1922).copy(alpha = 0.82f), saturation = 1.2f, lift = 0f,
                    highlight = 0.4f, shadowAlpha = 0f,
                ),
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
                error = Color(0xFFB42318),
                notice = GlassStyle(
                    blur = 10.dp, refraction = 10.dp, bevel = 10.dp, dispersion = 0.2f,
                    tint = Color.White.copy(alpha = 0.82f), saturation = 1.2f, lift = 0f,
                    highlight = 0.5f, shadowAlpha = 0.05f, shadowRadius = 10.dp, shadowOffsetY = 2.dp,
                ),
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
                    highlight = 0.6f, shadowAlpha = 0.07f, shadowRadius = 12.dp, shadowOffsetY = 3.dp,
                ),
                accentSurface = GlassStyle(
                    blur = 14.dp, refraction = 12.dp, bevel = 12.dp, dispersion = 0.2f,
                    tint = accent.copy(alpha = 0.9f), saturation = 1.2f, lift = 0f,
                    highlight = 0.55f, shadowAlpha = 0.08f, shadowRadius = 12.dp, shadowOffsetY = 3.dp,
                ),
                // The resting lens lies over the selected label, so its tint washes that
                // label out; kept thin, with the rim highlight doing the work of showing it.
                lensRest = GlassStyle(
                    blur = 0.dp, refraction = 10.dp, bevel = 12.dp, dispersion = 0.3f,
                    tint = Color.White.copy(alpha = 0.16f), saturation = 1.2f, lift = 0.03f,
                    highlight = 0.9f, shadowAlpha = 0f,
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
