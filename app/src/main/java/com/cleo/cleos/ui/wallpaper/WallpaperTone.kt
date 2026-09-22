package com.cleo.cleos.ui.wallpaper

import android.graphics.Bitmap
import com.cleo.cleos.ui.theme.Oklab
import kotlin.math.atan2

/**
 * What the glass needs to know about a wallpaper: light or dark, its colour, and how
 * dark its darkest part / bright its brightest part gets ([trough] / [peak], luminance).
 */
data class WallpaperTone(val dark: Boolean, val hue: Float, val chroma: Float, val trough: Float, val peak: Float)

object WallpaperAnalyzer {
    /**
     * Mean WCAG luminance below 0.179 counts as dark. That is where white and black
     * text have equal contrast. 0.5 would be wrong: luminance is not a percentage of
     * "bright", and mid-grey #828282 sits at only 0.22, so a 0.5 cut-off would call
     * almost every wallpaper dark.
     *
     * The hue is a weighted circular mean in OKLab, each pixel weighing chroma x lightness.
     * Both factors matter: by chroma alone, a big near-black area that happens to lean
     * cold (as dark photos usually do) out-votes a small bright subject; lightness makes
     * the colour you actually see win.
     */
    fun analyze(bitmap: Bitmap): WallpaperTone {
        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val pixels = IntArray(32 * 32)
        small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        if (small !== bitmap) small.recycle()

        var luminance = 0.0
        var sx = 0.0
        var sy = 0.0
        var chromaSum = 0.0
        var lightSum = 0.0
        val lums = FloatArray(pixels.size)
        for ((i, p) in pixels.withIndex()) {
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val lum = Oklab.luminance(r, g, b)
            lums[i] = lum
            luminance += lum
            val lab = Oklab.fromSrgb(r, g, b)
            val c = lab.chroma
            val w = c * lab.l
            if (c > 1e-4f) {
                sx += lab.a / c * w
                sy += lab.b / c * w
            }
            chromaSum += c * lab.l
            lightSum += lab.l
        }
        val n = pixels.size
        lums.sort()
        return WallpaperTone(
            dark = luminance / n < 0.179,
            hue = atan2(sy, sx).toFloat(),
            // Lightness-weighted mean chroma: how colourful the visible part of the image is.
            chroma = if (lightSum > 0) (chromaSum / lightSum).toFloat() else 0f,
            // Third darkest / brightest, not a percentile. Each of the 32x32 samples is
            // already the average of a patch about the size of a card, so p5/p95 (the
            // 51st darkest) would miss the one dark patch a bar happens to sit on, and a
            // small highlight is enough to wash out a line of text.
            trough = lums[2],
            peak = lums[n - 3],
        )
    }

    /** Scale for the accent's chroma: a grey wallpaper gets a muted accent, not a loud one. */
    fun chromaScale(chroma: Float?): Float = when {
        chroma == null -> 1f
        chroma < 0.02f -> 0.25f
        chroma < 0.05f -> 0.6f
        else -> 1f
    }
}
