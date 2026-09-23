package com.cleo.cleos.ui.home

import com.cleo.cleos.ui.common.avatarLetter
import com.cleo.cleos.ui.common.initial
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class HomeMathTest {
    // A 2000×1000 photo under an 800px circle: at cover scale (0.8) its height fills the circle.
    private val w = 2000
    private val h = 1000
    private val d = 800f

    @Test
    fun theShortSideJustCoversTheCircle() {
        assertEquals(0.8f, CropMath.coverScale(w, h, d), 1e-6f)
    }

    @Test
    fun untouchedItTakesTheMiddleSquare() {
        assertEquals(CropMath.Square(500, 0, 1000), CropMath.sourceSquare(w, h, d, 0.8f, 0f, 0f))
    }

    @Test
    fun draggingThePictureRightShowsMoreOfItsLeft() {
        // 200 screen px at scale 0.8 is 250 source px.
        assertEquals(CropMath.Square(250, 0, 1000), CropMath.sourceSquare(w, h, d, 0.8f, 200f, 0f))
    }

    @Test
    fun zoomingInTakesASmallerSquare() {
        assertEquals(CropMath.Square(750, 250, 500), CropMath.sourceSquare(w, h, d, 1.6f, 0f, 0f))
    }

    @Test
    fun thePictureCannotBeDraggedOffTheCircle() {
        val (x, y) = CropMath.clamp(1000f, 50f, w, h, d, 0.8f)
        assertEquals((w * 0.8f - d) / 2, x, 1e-3f)
        assertEquals("no room to move along the side that fills the circle", 0f, y, 1e-3f)
    }

    @Test
    fun theDayTheyMetIsDayOne() {
        val today = LocalDate.of(2026, 9, 23)
        assertEquals(1L, Home.dayNumber(today, today))
        assertEquals(366L, Home.dayNumber(LocalDate.of(2025, 9, 23), today))
        assertEquals(1L, Home.dayNumber(today.plusDays(1), today))
    }

    @Test
    fun theLetterOnADefaultAvatarIsAWholeCharacter() {
        assertEquals("S", initial("Song"))
        assertEquals("沐", initial(" 沐 "))
        assertEquals("😀", initial("😀abc"))
        assertEquals("", initial("  "))
        assertEquals("TA", avatarLetter("", "TA"))
        assertEquals("S", avatarLetter("Song", "TA"))
    }
}
