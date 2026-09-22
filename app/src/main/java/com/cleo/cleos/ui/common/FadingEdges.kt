package com.cleo.cleos.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The usual fade under a floating top bar that starts [top] down (status bar + bar):
 * content is gone by the time it reaches the top of the bar and fully back just below it.
 */
fun Modifier.fadeUnderTopBar(top: Dp, bottom: Dp = 0.dp, bottomFade: Dp = 48.dp): Modifier =
    fadingEdges(top = top + 12.dp, bottom = bottom, fade = 56.dp, bottomFade = bottomFade)

/**
 * Fades scrolling content out as it passes under the floating bars.
 *
 * Without it, whatever scrolls under the top bar shows through the gaps between the
 * glass buttons at full contrast, and reads as clutter sitting next to the title.
 * [top]/[bottom] are where the content is fully visible again; the fade covers the
 * [fade] distance just before that. Needs its own offscreen layer, since the mask
 * (DstIn) must apply to this content alone and not to what is behind it.
 */
fun Modifier.fadingEdges(top: Dp, bottom: Dp, fade: Dp, bottomFade: Dp = fade): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val f = fade.toPx()
        val bf = bottomFade.toPx()
        val t = top.toPx()
        val b = bottom.toPx()
        if (t > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    ((t - f).coerceAtLeast(0f) / t) to Color.Transparent,
                    1f to Color.Black,
                    startY = 0f,
                    endY = t,
                ),
                size = Size(size.width, t),
                blendMode = BlendMode.DstIn,
            )
        }
        if (b > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black,
                    (bf / b).coerceAtMost(1f) to Color.Transparent,
                    1f to Color.Transparent,
                    startY = size.height - b,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - b),
                size = Size(size.width, b),
                blendMode = BlendMode.DstIn,
            )
        }
    }
