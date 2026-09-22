package com.cleo.cleos.glass

import androidx.compose.ui.graphics.Color
import com.cleo.cleos.ui.theme.Oklab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassPaletteTest {
    private fun lum(c: Color) = Oklab.luminance(c.red, c.green, c.blue)

    private fun contrast(a: Color, b: Color): Float {
        val la = lum(a)
        val lb = lum(b)
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    /** What the shader shows: tint mixed over the background on sRGB values. */
    private fun over(tint: Color, bgLum: Float): Color {
        val bg = Oklab.fromLinear(bgLum)
        val a = tint.alpha
        return Color(a * tint.red + (1 - a) * bg, a * tint.green + (1 - a) * bg, a * tint.blue + (1 - a) * bg)
    }

    @Test
    fun lightGlassOverADarkPatchGetsEnoughTintForText() {
        val p = GlassPalettes.build(dark = false, trough = 0.01f)
        for (style in listOf(p.chrome, p.bar)) {
            val c = contrast(p.content, over(style.tint, 0.01f))
            assertTrue("contrast $c for tint ${style.tint.alpha}", c >= 4.5f - 0.01f)
        }
    }

    @Test
    fun theSelectedTabAccentStaysVisibleOverADarkPatch() {
        val p = GlassPalettes.build(dark = false, trough = 0.01f)
        val c = contrast(p.accentContent, over(p.chrome.tint, 0.01f))
        assertTrue("accent contrast $c", c >= 3f - 0.01f)
    }

    @Test
    fun darkGlassOverABrightPatchGetsEnoughTintForText() {
        val p = GlassPalettes.build(dark = true, peak = 1f)
        for (style in listOf(p.chrome, p.bar)) {
            val c = contrast(p.content, over(style.tint, 1f))
            assertTrue("contrast $c for tint ${style.tint.alpha}", c >= 4.5f - 0.01f)
        }
    }

    @Test
    fun aWallpaperThatIsFineAlreadyKeepsClearGlass() {
        val base = GlassPalettes.build(dark = false)
        val bright = GlassPalettes.build(dark = false, trough = 0.6f)
        assertEquals(base.chrome.tint.alpha, bright.chrome.tint.alpha, 0f)
        assertEquals(base.bar.tint.alpha, bright.bar.tint.alpha, 0f)
    }

    @Test
    fun textSurfacesCarryTheirOwnContrastOverAnyWallpaper() {
        for (dark in listOf(false, true)) {
            val p = GlassPalettes.build(dark)
            for (bg in listOf(0f, 0.5f, 1f)) {
                val c = contrast(p.content, over(p.surface.tint, bg))
                assertTrue("dark=$dark bg=$bg contrast $c", c >= 4.5f)
            }
        }
    }

    @Test
    fun whiteTextOnTheAccentBubbleIsReadableOverWhite() {
        for (dark in listOf(false, true)) {
            val p = GlassPalettes.build(dark)
            val c = contrast(Color.White, over(p.accentSurface.tint, 1f))
            assertTrue("dark=$dark contrast $c", c >= 4.5f)
        }
    }
}
