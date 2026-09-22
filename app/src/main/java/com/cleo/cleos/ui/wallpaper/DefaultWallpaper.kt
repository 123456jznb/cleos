package com.cleo.cleos.ui.wallpaper

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The wallpaper used until one is picked.
 *
 * Glass over a flat colour is invisible, and refraction only shows where there is an
 * edge to bend, so this is soft colour fields plus a handful of crisp bokeh discs.
 */
@Composable
fun DefaultWallpaper(dark: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) { if (dark) drawNight() else drawDawn() }
}

private fun DrawScope.blob(x: Float, y: Float, r: Float, color: Color, alpha: Float) {
    val center = Offset(size.width * x, size.height * y)
    val radius = size.width * r
    drawCircle(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            0.55f to color.copy(alpha = alpha * 0.55f),
            1f to color.copy(alpha = 0f),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

private fun DrawScope.disc(x: Float, y: Float, r: Float, fill: Color, rim: Color) {
    val center = Offset(size.width * x, size.height * y)
    val radius = size.width * r
    drawCircle(fill, radius, center)
    drawCircle(rim, radius, center, style = Stroke(width = 1.5f * density))
}

private fun DrawScope.drawDawn() {
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFFFCE7E3),
            0.5f to Color(0xFFE9E3FA),
            1f to Color(0xFFDCEAFB),
        ),
    )
    blob(0.10f, 0.14f, 0.70f, Color(0xFFFFA98E), 0.85f)
    blob(0.98f, 0.30f, 0.70f, Color(0xFFAE9BFF), 0.80f)
    blob(0.20f, 0.64f, 0.72f, Color(0xFF7CC8FF), 0.72f)
    blob(0.92f, 0.88f, 0.62f, Color(0xFFFFC978), 0.70f)
    blob(0.58f, 0.46f, 0.42f, Color(0xFFFF93C0), 0.50f)

    disc(0.80f, 0.12f, 0.12f, Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.55f))
    disc(0.18f, 0.38f, 0.07f, Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.5f))
    disc(0.66f, 0.58f, 0.16f, Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.42f))
    disc(0.30f, 0.86f, 0.10f, Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.5f))
    disc(0.88f, 0.74f, 0.05f, Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.6f))
}

private fun DrawScope.drawNight() {
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF16122E),
            0.55f to Color(0xFF111A33),
            1f to Color(0xFF0B1426),
        ),
    )
    blob(0.08f, 0.16f, 0.72f, Color(0xFF6B45FF), 0.62f)
    blob(1.00f, 0.34f, 0.66f, Color(0xFFFF4F9A), 0.42f)
    blob(0.22f, 0.68f, 0.70f, Color(0xFF1DB6A6), 0.44f)
    blob(0.90f, 0.90f, 0.62f, Color(0xFF3A78FF), 0.50f)
    blob(0.55f, 0.48f, 0.40f, Color(0xFFB65CFF), 0.30f)

    disc(0.80f, 0.12f, 0.12f, Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.22f))
    disc(0.18f, 0.38f, 0.07f, Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.24f))
    disc(0.66f, 0.58f, 0.16f, Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.18f))
    disc(0.30f, 0.86f, 0.10f, Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.22f))
    disc(0.88f, 0.74f, 0.05f, Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.28f))
}
