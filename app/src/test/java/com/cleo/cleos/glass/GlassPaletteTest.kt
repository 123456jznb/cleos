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

    @Test
    fun aBubbleColourPickedGetsTheTextThatReadsOnIt() {
        // A light colour: dark text, readable over the darkest patch behind the glass.
        val light = GlassPalettes.build(dark = false, trough = 0.02f, peak = 0.9f, mine = Color(0xFFFFE08A))
        assertTrue(light.mineContent != Color.White)
        assertTrue(contrast(light.mineContent, over(light.bubbleMine.tint, 0.02f)) >= 4.5f - 0.01f)
        // A deep one: white text, readable over the brightest patch.
        val deep = GlassPalettes.build(dark = true, trough = 0.02f, peak = 0.9f, mine = Color(0xFF2F7D5B))
        assertEquals(Color.White, deep.mineContent)
        assertTrue(contrast(Color.White, over(deep.bubbleMine.tint, 0.9f)) >= 4.5f - 0.01f)
        // It is still the colour picked, and glass: only the tint changed.
        assertEquals(Color(0xFF2F7D5B).copy(alpha = deep.bubbleMine.tint.alpha), deep.bubbleMine.tint)
        assertEquals(deep.accentSurface.blur, deep.bubbleMine.blur)
        // None picked: the accent, with white text, as before.
        val auto = GlassPalettes.build(dark = false, peak = 0.9f)
        assertEquals(Color.White, auto.mineContent)
        // Trying a colour out gives what building with it would.
        assertEquals(light.bubbleMine to light.mineContent, GlassPalettes.mine(GlassPalettes.build(dark = false, trough = 0.02f, peak = 0.9f), Color(0xFFFFE08A)))
    }

    @Test
    fun aPickedColourShowsOnBubblesTunedAlmostClear() {
        // Bubbles tuned almost clear, over a light wallpaper: dark text reads on a light or
        // mid colour without any help from the contrast floor, so nothing else makes it show.
        val clear = mapOf(GlassPart.Bubble to GlassTuning.of(GlassPalettes.build(dark = false).surface).copy(tintAlpha = 0.15f))
        fun tint(dark: Boolean, trough: Float, peak: Float, mine: Long?) =
            GlassPalettes.build(dark, trough = trough, peak = peak, tuning = clear, mine = mine?.let { Color(it) }).bubbleMine.tint.alpha
        for (c in listOf(0xFFFFE08A, 0xFFFFB38A, 0xFFB8D8FF, 0xFF95EC69, 0xFF3E7BFA, 0xFF1F9E99, 0xFFD9467A)) {
            val a = tint(dark = false, trough = 0.3f, peak = 0.9f, mine = c)
            assertTrue("${c.toString(16)} tint $a", a >= GlassPalettes.PICKED_TINT - 0.01f)
        }
        // White has no hue to lose: the glass stays as clear as it was tuned.
        assertEquals(0.15f, tint(dark = false, trough = 0.3f, peak = 0.9f, mine = 0xFFF4F4F6), 0.01f)
        // Nothing picked: the accent keeps the tuned clarity wherever its white text reads anyway.
        assertEquals(0.15f, tint(dark = true, trough = 0.01f, peak = 0.05f, mine = null), 0.01f)
    }

    @Test
    fun theTabLensCanBeTunedAndHoldingItFollowsTheTuning() {
        for (dark in listOf(false, true)) {
            val untuned = GlassPalettes.build(dark = dark, trough = 0.05f, peak = 0.95f)
            assertEquals(untuned.lensRest, untuned.lens)
            assertEquals(untuned.lensHeld, untuned.lensPressed)

            // The rainbow tuned away stays away when the lens is held.
            val noRainbow = GlassTuning.of(untuned.lensRest, dark).copy(dispersion = 0f)
            val tuned = GlassPalettes.build(dark = dark, trough = 0.05f, peak = 0.95f, tuning = mapOf(GlassPart.Lens to noRainbow))
            assertEquals(0f, tuned.lens.dispersion, 0f)
            assertEquals(0f, tuned.lensPressed.dispersion, 0f)
            // What was left alone swells as before when held.
            assertEquals(untuned.lensHeld.refraction.value, tuned.lensPressed.refraction.value, 0.001f)
            assertEquals(untuned.lensHeld.zoom, tuned.lensPressed.zoom, 0.001f)
            // Twice the bend at rest, twice the bend held.
            val bent = GlassTuning.of(untuned.lensRest, dark).copy(refraction = untuned.lensRest.refraction.value * 2)
            val bentHeld = GlassPalettes.build(dark = dark, tuning = mapOf(GlassPart.Lens to bent)).lensPressed
            assertEquals(untuned.lensHeld.refraction.value * 2, bentHeld.refraction.value, 0.001f)
            // A knob the resting lens has at 0 (blur) moves by the same amount when held, not by a ratio of nothing.
            val blurred = GlassTuning.of(untuned.lensRest, dark).copy(blur = 3f)
            val blurredHeld = GlassPalettes.build(dark = dark, tuning = mapOf(GlassPart.Lens to blurred)).lensPressed
            assertEquals(3f + untuned.lensHeld.blur.value - untuned.lensRest.blur.value, blurredHeld.blur.value, 0.001f)
            // It carries no words, so the contrast floor leaves it as tuned, however clear.
            val clear = GlassTuning.of(untuned.lensRest, dark).copy(tintAlpha = 0f)
            assertEquals(0f, GlassPalettes.build(dark = dark, trough = 0.05f, peak = 0.95f, tuning = mapOf(GlassPart.Lens to clear)).lens.tint.alpha, 0f)
        }
    }
}
