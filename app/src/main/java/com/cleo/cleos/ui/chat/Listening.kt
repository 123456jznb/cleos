package com.cleo.cleos.ui.chat

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.ai.LyricLine
import com.cleo.cleos.ai.Lrc
import com.cleo.cleos.ai.NowPlaying
import com.cleo.cleos.glass.Backdrop
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.LocalGlassPalette
import com.cleo.cleos.glass.liquidGlass
import kotlinx.coroutines.delay

/** How much room the bar takes at the top of the chat, for the list to leave. */
internal val ListeningBarSpace = 58.dp

/** A paused song stays up this long, to start it again from here; then the bar goes. */
internal const val PAUSED_SHOWN_MS = 10 * 60_000L

/**
 * What is playing, at the top of the chat while 一起听歌 is on: the cover as a record, the song,
 * the line being sung, and pause and next without leaving. Tapping the rest opens the
 * player itself. The line moves on by the clock, from where the player last said it was; no one
 * is asked every second.
 */
@Composable
internal fun ListeningBar(
    backdrop: Backdrop,
    np: NowPlaying,
    words: List<LyricLine>?,
    who: String,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalGlassPalette.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(np) {
        now = SystemClock.elapsedRealtime()
        while (np.playing) {
            delay(TICK_MS)
            now = SystemClock.elapsedRealtime()
        }
    }
    val position = np.position(now)
    val index = words?.let { Lrc.at(it, position) } ?: -1
    val line = words?.getOrNull(index)?.text?.takeIf { it.isNotBlank() }
    // What moves the record on: the next line sung, or, for a song without words, every few seconds of it.
    val step = if (!words.isNullOrEmpty()) index else (position / STEP_MS).toInt()
    Row(
        modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .liquidGlass(backdrop, palette.input, GlassShape.Capsule)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen)
            .padding(start = 7.dp, end = 5.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Record(np.art, step)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(np.title) }
                    if (np.artist.isNotBlank()) withStyle(SpanStyle(color = palette.contentSecondary)) { append(" · ${np.artist}") }
                },
                color = palette.content,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Crossfade(targetState = line ?: "和${who}一起听", label = "lyric") {
                Text(it, color = palette.contentSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Control(if (np.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (np.playing) "暂停" else "接着放", onToggle)
        Control(Icons.Rounded.SkipNext, "下一首", onNext)
    }
}

/**
 * The cover as a record, turning a little each time the song moves on ([step]: a new line, or a
 * few seconds without words) and still while it is paused. It used to spin all the time, and
 * anything moving on glass redraws the screen every frame: the phone drew 90 frames a second
 * for as long as music played with the chat open, for a disc turning slowly. Now it draws for
 * the moment of each turn, and the rest of the time nothing.
 */
@Composable
private fun Record(art: Bitmap?, step: Int) {
    val palette = LocalGlassPalette.current
    // Always forward: a new song starts its lines from the top again, the record doesn't turn back.
    var turns by remember { mutableIntStateOf(0) }
    LaunchedEffect(step) { turns++ }
    val angle by animateFloatAsState(turns * TURN_DEGREES, tween(TURN_MS, easing = FastOutSlowInEasing), label = "record")
    val image = remember(art) { art?.asImageBitmap() }
    Box(
        Modifier
            .size(40.dp)
            .graphicsLayer { rotationZ = angle }
            .clip(CircleShape)
            .background(palette.content.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = palette.content, modifier = Modifier.size(20.dp))
        }
        // The hole in the middle.
        Box(
            Modifier
                .size(8.dp)
                .background(Color.White.copy(alpha = 0.85f), CircleShape)
                .border(1.dp, Color.Black.copy(alpha = 0.15f), CircleShape),
        )
    }
}

@Composable
private fun Control(icon: ImageVector, label: String, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = palette.content, modifier = Modifier.size(26.dp))
    }
}

private const val TICK_MS = 400L
private const val TURN_MS = 700
private const val TURN_DEGREES = 30f
private const val STEP_MS = 5_000L
