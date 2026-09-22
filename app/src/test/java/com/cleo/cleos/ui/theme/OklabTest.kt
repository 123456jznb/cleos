package com.cleo.cleos.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OklabTest {
    @Test
    fun luminanceEnds() {
        assertEquals(1f, Oklab.luminance(1f, 1f, 1f), 1e-4f)
        assertEquals(0f, Oklab.luminance(0f, 0f, 0f), 1e-6f)
    }

    @Test
    fun midGreyIsNotHalfLuminance() {
        // #828282: the reason light/dark is split at 0.179 and not 0.5.
        val l = Oklab.luminance(0x82 / 255f, 0x82 / 255f, 0x82 / 255f)
        assertTrue("got $l", l in 0.2f..0.24f)
    }

    @Test
    fun lchKeepsLightnessAndHue() {
        val hue = 1.2f
        val c = Oklab.lch(0.6f, 0.1f, hue)
        val lab = Oklab.fromSrgb(c.red, c.green, c.blue)
        assertEquals(0.6f, lab.l, 0.01f)
        assertTrue("hue ${lab.hue}", abs(lab.hue - hue) < 0.05f)
    }

    @Test
    fun outOfGamutChromaIsReducedNotClipped() {
        // Asking for far more chroma than sRGB has must still land on the requested hue.
        val hue = 2.4f
        val c = Oklab.lch(0.7f, 0.5f, hue)
        val lab = Oklab.fromSrgb(c.red, c.green, c.blue)
        assertTrue("hue ${lab.hue}", abs(lab.hue - hue) < 0.08f)
        assertEquals(0.7f, lab.l, 0.02f)
    }

    @Test
    fun nearBlackHasNoChromaToVoteWith() {
        // rgb(10, 8, 12): HSV calls it 33% saturated violet; OKLab barely any chroma.
        val lab = Oklab.fromSrgb(10 / 255f, 8 / 255f, 12 / 255f)
        assertTrue("chroma ${lab.chroma}", lab.chroma < 0.01f)
    }
}
