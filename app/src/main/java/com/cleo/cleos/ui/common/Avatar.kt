package com.cleo.cleos.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cleo.cleos.glass.LocalGlassPalette

/**
 * A round picture, or before one is chosen, [letter] on the accent (see [avatarLetter]).
 * The thin light ring keeps a dark photo from dissolving into dark glass.
 */
@Composable
fun Avatar(file: String?, letter: String, size: Dp, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    val c = appContainer()
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(palette.accent)
            .border(size * 0.04f, Color.White.copy(alpha = if (palette.dark) 0.3f else 0.85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (file != null) {
            AsyncImage(
                model = c.images.file(file),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Two letters ("TA") need a smaller size to stay inside the circle.
            val scale = if (letter.codePointCount(0, letter.length) > 1) 0.3f else 0.4f
            Text(letter, color = Color.White, fontSize = (size.value * scale).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The name's first character, or [fallback] ("我", "TA") while there is no name. */
fun avatarLetter(name: String, fallback: String): String = initial(name).ifEmpty { fallback }

/** The first character as a person reads it: a whole code point, so an emoji isn't cut in half. */
internal fun initial(name: String): String {
    val t = name.trim()
    return if (t.isEmpty()) "" else String(Character.toChars(t.codePointAt(0)))
}
