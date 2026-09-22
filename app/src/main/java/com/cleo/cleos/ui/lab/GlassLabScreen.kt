package com.cleo.cleos.ui.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.GlassIconButton
import com.cleo.cleos.glass.GlassMotion
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassStyle
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.LocalWallpaperBackdrop
import com.cleo.cleos.glass.WallpaperOverscan
import com.cleo.cleos.glass.backdropSource
import com.cleo.cleos.glass.glassPress
import com.cleo.cleos.glass.liquidGlass
import com.cleo.cleos.glass.rememberBackdrop
import kotlin.math.roundToInt

/**
 * A playground for the glass: one piece you can drag over detailed content, and a
 * slider for every parameter the shader takes. Useful for tuning, and for seeing what
 * each knob actually does before changing the app-wide styles.
 */
@Composable
fun GlassLabScreen(onBack: () -> Unit) {
    val palette = LocalGlassPalette.current
    val density = LocalDensity.current
    val page = rememberBackdrop()
    var style by remember { mutableStateOf(palette.chrome) }
    var corner by remember { mutableFloatStateOf(100f) }
    var pieceOffset by remember { mutableStateOf(with(density) { Offset(40.dp.toPx(), 260.dp.toPx()) }) }
    var panelOpen by remember { mutableStateOf(true) }
    val motion = remember { GlassMotion() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .backdropSource(page, behind = LocalWallpaperBackdrop.current, overscan = WallpaperOverscan),
        ) {
            Specimen(Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp))
        }

        val shape = if (corner >= 100f) GlassShape.Capsule else GlassShape.Rounded(corner.dp)
        Box(
            Modifier
                .offset { IntOffset(pieceOffset.x.roundToInt(), pieceOffset.y.roundToInt()) }
                .size(240.dp, 110.dp)
                .liquidGlass(page, style, shape, motion)
                .glassPress(motion, swell = 4.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        pieceOffset += drag
                    }
                },
        )

        Row(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, page)
            Spacer(Modifier.width(12.dp))
            Text("玻璃实验室", color = palette.content, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth()
                .liquidGlass(page, palette.surface, GlassShape.Rounded(28.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Preset("清透") { style = palette.chrome; corner = 100f }
                Spacer(Modifier.width(8.dp))
                Preset("常规") { style = palette.surface; corner = 24f }
                Spacer(Modifier.width(8.dp))
                Preset("透镜") { style = palette.lensHeld; corner = 100f }
                Spacer(Modifier.weight(1f))
                Preset(if (panelOpen) "收起" else "展开") { panelOpen = !panelOpen }
            }
            if (panelOpen) {
                Knob("模糊", style.blur.value, 0f..30f) { style = style.copy(blur = it.dp) }
                Knob("折射", style.refraction.value, -40f..40f) { style = style.copy(refraction = it.dp) }
                Knob("边缘宽度", style.bevel.value, 0f..48f) { style = style.copy(bevel = it.dp) }
                Knob("色散", style.dispersion, 0f..1f) { style = style.copy(dispersion = it) }
                Knob("放大", style.zoom, 0.6f..1.6f) { style = style.copy(zoom = it) }
                Knob("着色", style.tint.alpha, 0f..1f) { style = style.copy(tint = style.tint.copy(alpha = it)) }
                Knob("饱和度", style.saturation, 0f..2.5f) { style = style.copy(saturation = it) }
                Knob("高光", style.highlight, 0f..1.5f) { style = style.copy(highlight = it) }
                Knob("阴影", style.shadowAlpha, 0f..0.5f) { style = style.copy(shadowAlpha = it) }
                Knob("圆角", corner, 0f..100f, label = if (corner >= 100f) "胶囊" else "${corner.roundToInt()}") { corner = it }
            }
        }
    }
}

/**
 * Plain pills, not glass: they sit on the glass panel, and glass can only see the page
 * behind the panel, so glass buttons here would look like holes cut through it.
 */
@Composable
private fun Preset(text: String, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .background(palette.content.copy(alpha = 0.07f), CircleShape)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, fontSize = 14.sp, color = palette.content)
    }
}

@Composable
private fun Knob(
    name: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String = if (range.endInclusive > 5f) "${value.roundToInt()}" else "%.2f".format(value),
    onChange: (Float) -> Unit,
) {
    val palette = LocalGlassPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(34.dp)) {
        Text(name, color = palette.content, fontSize = 13.sp, modifier = Modifier.width(64.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = palette.accent,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.content.copy(alpha = 0.15f),
            ),
        )
        Text(label, color = palette.contentSecondary, fontSize = 12.sp, modifier = Modifier.width(44.dp).padding(start = 8.dp))
    }
}

/** High-detail content for the glass to bend: big type, stripes, colour dots, a grid. */
@Composable
private fun Specimen(modifier: Modifier) {
    val palette = LocalGlassPalette.current
    Column(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("液态玻璃", color = palette.content, fontSize = 64.sp, fontWeight = FontWeight.Black)
        Text("Liquid Glass · 光在边缘弯折", color = palette.content, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val colors = listOf(Color(0xFFFF5A5F), Color(0xFFFFB400), Color(0xFF00A699), Color(0xFF007AFF), Color(0xFF8E44FF))
            val bar = size.width / 20f
            for (i in 0 until 20) {
                drawRect(colors[i % colors.size], topLeft = Offset(i * bar, 0f), size = androidx.compose.ui.geometry.Size(bar * 0.5f, size.height))
            }
        }
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val step = 24.dp.toPx()
            var x = 0f
            while (x <= size.width) {
                drawLine(palette.content.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                x += step
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(palette.content.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                y += step
            }
            drawCircle(Color(0xFFFF2D55), 28.dp.toPx(), Offset(size.width * 0.2f, size.height * 0.5f))
            drawCircle(Color(0xFF34C759), 22.dp.toPx(), Offset(size.width * 0.5f, size.height * 0.35f))
            drawCircle(Color(0xFF5856D6), 34.dp.toPx(), Offset(size.width * 0.78f, size.height * 0.6f), style = Stroke(6.dp.toPx()))
        }
        Text(
            "把上面那块玻璃拖到文字和条纹上，看边缘怎样把后面的东西弯进来。按住它，它会亮起来、鼓起来。",
            color = palette.content,
            fontSize = 17.sp,
            lineHeight = 26.sp,
        )
    }
}
