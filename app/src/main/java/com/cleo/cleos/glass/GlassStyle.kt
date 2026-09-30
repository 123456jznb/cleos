package com.cleo.cleos.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The outline of a piece of glass. Only rounded rectangles: the shader's SDF is one. */
@Immutable
sealed interface GlassShape {
    fun radiusPx(size: Size, density: Density): Float
    val shape: Shape

    /** Fully rounded ends (a pill; a circle when square). */
    data object Capsule : GlassShape {
        override fun radiusPx(size: Size, density: Density) = size.minDimension / 2f
        override val shape: Shape = RoundedCornerShape(50)
    }

    data class Rounded(val radius: Dp) : GlassShape {
        override fun radiusPx(size: Size, density: Density) =
            with(density) { radius.toPx() }.coerceAtMost(size.minDimension / 2f)

        override val shape: Shape = RoundedCornerShape(radius)
    }
}

/**
 * How a piece of glass bends and colours what is behind it.
 *
 * Two families, and the split matters:
 *  - chrome (bars, buttons, the tab lens): clear, strongly refracting, thin tint.
 *    It only has to carry icons, which survive almost any background.
 *  - surfaces that carry body text (bubbles, cards): a tint of 0.6 or more. Glass that
 *    stays readable only because the wallpaper behind it happens to be calm stops being
 *    readable the day the wallpaper changes, so the tint alone has to guarantee contrast.
 *    0.6 white over pure black composites to #999, which still gives near-black text
 *    about 6:1.
 */
@Immutable
data class GlassStyle(
    val blur: Dp,
    val refraction: Dp,
    val bevel: Dp,
    val dispersion: Float,
    val tint: Color,
    val saturation: Float,
    val lift: Float,
    val highlight: Float,
    val rimWidth: Dp = 1.2.dp,
    val shadowAlpha: Float,
    val shadowRadius: Dp = 18.dp,
    val shadowOffsetY: Dp = 6.dp,
    val zoom: Float = 1f,
)

/** Linear blend between two styles; used to morph the tab lens between rest and held. */
fun lerp(a: GlassStyle, b: GlassStyle, t: Float): GlassStyle {
    fun f(x: Float, y: Float) = x + (y - x) * t
    fun d(x: Dp, y: Dp) = Dp(f(x.value, y.value))
    return GlassStyle(
        blur = d(a.blur, b.blur),
        refraction = d(a.refraction, b.refraction),
        bevel = d(a.bevel, b.bevel),
        dispersion = f(a.dispersion, b.dispersion),
        tint = androidx.compose.ui.graphics.lerp(a.tint, b.tint, t),
        saturation = f(a.saturation, b.saturation),
        lift = f(a.lift, b.lift),
        highlight = f(a.highlight, b.highlight),
        rimWidth = d(a.rimWidth, b.rimWidth),
        shadowAlpha = f(a.shadowAlpha, b.shadowAlpha),
        shadowRadius = d(a.shadowRadius, b.shadowRadius),
        shadowOffsetY = d(a.shadowOffsetY, b.shadowOffsetY),
        zoom = f(a.zoom, b.zoom),
    )
}

/**
 * A second rounded rect this glass melts into: two shapes near each other grow a waist
 * between them, and pulling them apart draws that waist out into a thread which finally
 * snaps. The tab lens uses it to stay attached to the tab it is leaving.
 *
 * Coordinates are px relative to the **node's top-left**, so the blob normally sits
 * outside the node — that is the point, it marks where the glass came from. The glass
 * grows its own layer to cover it.
 *
 * ## Why this is cheap
 *
 * The usual way to do this is "blur everything, then cut at a threshold": two passes over
 * the whole area, and the edge comes out soft. Here both shapes already report, for every
 * pixel, how far away their edge is — taking a **soft minimum** of those two distances
 * bulges the waist on its own, exactly, for the cost of one more distance evaluation.
 */
@Immutable
data class GlassBlob(
    /** Centre, relative to the node's top-left. */
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val radius: Float,
    /**
     * How far the two shapes reach for each other, px — the whole character of the
     * effect: how thick the thread is, and how far they get before it snaps.
     *
     * **0 turns the merge off**, and off is what every other piece of glass in the app
     * leaves it at, so none of them pay for this.
     */
    val merge: Float,
)

/**
 * Live state of one piece of glass: where a finger is (the glass lights up under it),
 * how far the outline has swollen past the view's bounds (in dp per side), and which
 * second shape it is melting into.
 * Whoever handles the gesture animates these; the glass only reads them, and because
 * they are read while drawing, animating them redraws the glass without recomposing.
 */
@Stable
class GlassMotion {
    var touch by mutableStateOf(Offset.Unspecified)
    var glow by mutableFloatStateOf(0f)
    var swellX by mutableFloatStateOf(0f)
    var swellY by mutableFloatStateOf(0f)
    var blob by mutableStateOf<GlassBlob?>(null)
}
