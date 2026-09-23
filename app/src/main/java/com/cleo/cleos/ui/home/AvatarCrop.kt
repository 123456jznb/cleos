package com.cleo.cleos.ui.home

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.ui.common.appContainer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Avatars are shown at most around 100dp; this is plenty at any screen density. */
private const val AVATAR_PX = 512

/**
 * Choose the round part of [uri] to use as an avatar: drag to move, pinch to zoom. The
 * circle is always covered by the picture, so there is no way to crop in empty space.
 */
@Composable
fun AvatarCropDialog(uri: Uri, onDismiss: () -> Unit, onCropped: (Bitmap) -> Unit) {
    val c = appContainer()
    val source by produceState<Result<Bitmap>?>(null, uri) {
        value = runCatching { c.images.decode(uri) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val bitmap = source?.getOrNull()
            when {
                bitmap != null -> Cropper(bitmap, onDismiss, onCropped)
                source != null -> Message("这张图打不开，换一张试试", onDismiss)
                else -> Message("正在打开…", null)
            }
        }
    }
}

@Composable
private fun Message(text: String, onClose: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Color.White,
            fontSize = 16.sp,
            modifier = if (onClose != null) Modifier.clickable(onClick = onClose) else Modifier,
        )
    }
}

@Composable
private fun Cropper(bitmap: Bitmap, onCancel: () -> Unit, onCropped: (Bitmap) -> Unit) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val density = LocalDensity.current
    val margin = with(density) { 36.dp.toPx() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }

    // Read inside the gesture callback, so they must not be captured as values.
    fun diameter() = (min(box.width, box.height) - 2 * margin).coerceAtLeast(1f)
    fun scale() = CropMath.coverScale(bitmap.width, bitmap.height, diameter()) * zoom

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = (zoom * gestureZoom).coerceIn(1f, CropMath.MAX_ZOOM)
                    val (x, y) = CropMath.clamp(dx + pan.x, dy + pan.y, bitmap.width, bitmap.height, diameter(), scale())
                    dx = x
                    dy = y
                }
            },
    ) {
        if (box != IntSize.Zero) {
            Canvas(Modifier.fillMaxSize()) {
                val s = scale()
                val w = bitmap.width * s
                val h = bitmap.height * s
                drawImage(
                    image,
                    dstOffset = IntOffset((size.width / 2 + dx - w / 2).roundToInt(), (size.height / 2 + dy - h / 2).roundToInt()),
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    filterQuality = FilterQuality.High,
                )
                val r = diameter() / 2
                val circle = Path().apply { addOval(Rect(center, r)) }
                clipPath(circle, clipOp = ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.62f)) }
                drawCircle(Color.White.copy(alpha = 0.9f), radius = r, style = Stroke(2.dp.toPx()))
            }
        }
        Text(
            "拖动、双指缩放，圆圈里的就是头像",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 24.dp),
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CropButton("取消", accent = false, onClick = onCancel)
            CropButton("用这张", accent = true) {
                val sq = CropMath.sourceSquare(bitmap.width, bitmap.height, diameter(), scale(), dx, dy)
                val cut = Bitmap.createBitmap(bitmap, sq.left, sq.top, sq.size, sq.size)
                val out = if (sq.size > AVATAR_PX) Bitmap.createScaledBitmap(cut, AVATAR_PX, AVATAR_PX, true) else cut
                onCropped(out)
            }
        }
    }
}

@Composable
private fun CropButton(text: String, accent: Boolean, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .background(if (accent) palette.accent else Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 12.dp),
    ) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/**
 * The geometry of the cropper, apart from the screen for testing. The picture is drawn
 * centred, scaled by [coverScale] × zoom and moved by (dx, dy); the circle of [diameter]
 * stays in the middle.
 */
internal object CropMath {
    const val MAX_ZOOM = 6f

    /** The scale at which the picture just covers the circle. */
    fun coverScale(w: Int, h: Int, diameter: Float): Float = max(diameter / w, diameter / h)

    /** How far the picture may move before the circle would reach past its edge. */
    fun clamp(dx: Float, dy: Float, w: Int, h: Int, diameter: Float, scale: Float): Pair<Float, Float> {
        val maxX = ((w * scale - diameter) / 2f).coerceAtLeast(0f)
        val maxY = ((h * scale - diameter) / 2f).coerceAtLeast(0f)
        return dx.coerceIn(-maxX, maxX) to dy.coerceIn(-maxY, maxY)
    }

    data class Square(val left: Int, val top: Int, val size: Int)

    /** The square of the source picture under the circle, in its own pixels. */
    fun sourceSquare(w: Int, h: Int, diameter: Float, scale: Float, dx: Float, dy: Float): Square {
        val size = (diameter / scale).roundToInt().coerceIn(1, min(w, h))
        // Moving the picture right shows more of its left side: the centre moves the other way.
        val cx = w / 2f - dx / scale
        val cy = h / 2f - dy / scale
        val left = (cx - size / 2f).roundToInt().coerceIn(0, w - size)
        val top = (cy - size / 2f).roundToInt().coerceIn(0, h - size)
        return Square(left, top, size)
    }
}
