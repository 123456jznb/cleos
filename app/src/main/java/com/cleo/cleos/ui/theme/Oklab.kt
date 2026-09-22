package com.cleo.cleos.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * OKLab / OKLCH. Colour maths in this app goes through here rather than HSL/HSV.
 *
 * HSL "saturation" is not something the eye measures. Near black it is noise:
 * rgb(10, 8, 12) is black to anyone looking but has HSV saturation 0.33 and a violet hue,
 * so a mostly-black wallpaper "votes" violet with every pixel. And the same saturation
 * value is soft on one hue and garish on another (green needs far more chroma than
 * brown to reach the same lightness). OKLab's lightness and chroma are perceptual, so
 * "same lightness, same chroma, different hue" actually looks like the same strength.
 */
object Oklab {
    data class Lab(val l: Float, val a: Float, val b: Float) {
        val chroma: Float get() = hypot(a, b)
        val hue: Float get() = atan2(b, a)
    }

    fun toLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    fun fromLinear(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else (1.055 * c.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()

    /** WCAG relative luminance of an sRGB colour. */
    fun luminance(r: Float, g: Float, b: Float): Float =
        0.2126f * toLinear(r) + 0.7152f * toLinear(g) + 0.0722f * toLinear(b)

    fun fromSrgb(r: Float, g: Float, b: Float): Lab {
        val lr = toLinear(r)
        val lg = toLinear(g)
        val lb = toLinear(b)
        val l = cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb)
        val m = cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb)
        val s = cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb)
        return Lab(
            l = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            b = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
        )
    }

    /** Linear-light RGB, possibly out of [0, 1] when the colour is outside sRGB. */
    private fun toLinearRgb(lab: Lab): FloatArray {
        val l = lab.l + 0.3963377774f * lab.a + 0.2158037573f * lab.b
        val m = lab.l - 0.1055613458f * lab.a - 0.0638541728f * lab.b
        val s = lab.l - 0.0894841775f * lab.a - 1.2914855480f * lab.b
        val l3 = l * l * l
        val m3 = m * m * m
        val s3 = s * s * s
        return floatArrayOf(
            4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3,
            -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3,
            -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3,
        )
    }

    private fun inGamut(rgb: FloatArray) = rgb.all { it in -0.0001f..1.0001f }

    /**
     * The colour at lightness [l], hue [hue] (radians) with as much of [chroma] as sRGB
     * can show. Chroma is reduced (by bisection) rather than clipping channels, because
     * clipping shifts the hue.
     */
    fun lch(l: Float, chroma: Float, hue: Float): Color {
        var lo = 0f
        var hi = chroma
        var best = Lab(l, 0f, 0f)
        if (inGamut(toLinearRgb(Lab(l, chroma * cos(hue), chroma * sin(hue))))) {
            best = Lab(l, chroma * cos(hue), chroma * sin(hue))
        } else {
            repeat(20) {
                val mid = (lo + hi) / 2f
                val lab = Lab(l, mid * cos(hue), mid * sin(hue))
                if (inGamut(toLinearRgb(lab))) {
                    best = lab
                    lo = mid
                } else {
                    hi = mid
                }
            }
        }
        val rgb = toLinearRgb(best)
        return Color(
            fromLinear(rgb[0].coerceIn(0f, 1f)),
            fromLinear(rgb[1].coerceIn(0f, 1f)),
            fromLinear(rgb[2].coerceIn(0f, 1f)),
        )
    }
}
