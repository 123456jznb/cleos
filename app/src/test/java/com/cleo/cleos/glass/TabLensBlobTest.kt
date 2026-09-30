package com.cleo.cleos.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lump the tab lens leaves behind on the tab it is leaving.
 *
 * What is worth pinning down here is not the shape but the two ends: the reach has to
 * come back to zero at both of them, for two quite different reasons, and neither
 * failure is obvious from reading the formula.
 */
class TabLensBlobTest {

    private val span = 200f
    private val restHeight = 56f

    /** The lens sitting still on its own tab, `distance` px along from the anchor. */
    private fun blobAt(distance: Float) = trailingBlob(
        anchorCx = 500f,
        lensCx = 500f + distance,
        lensLeft = 500f + distance - span / 2f,
        lensHeight = restHeight,
        span = span,
        restHeight = restHeight,
    )

    @Test
    fun `at rest there is no second shape`() {
        // Not a nicety. A soft minimum never quite returns the nearer of two distances,
        // so a lump parked inside the resting lens would inflate its outline — the lens
        // would sit there looking swollen for no reason anyone could see.
        assertNull(blobAt(0f))
    }

    @Test
    fun `a tab's width away the thread has snapped`() {
        assertNull(blobAt(span))
        assertNull(blobAt(span * 1.5f))
    }

    @Test
    fun `the thread is thickest around halfway`() {
        val quarter = blobAt(span * 0.25f)!!.merge
        val half = blobAt(span * 0.5f)!!.merge
        val threeQuarters = blobAt(span * 0.75f)!!.merge
        assertTrue("$quarter should be less than $half", quarter < half)
        assertTrue("$threeQuarters should be less than $half", threeQuarters < half)
    }

    @Test
    fun `the lump shrinks the further the lens gets`() {
        val near = blobAt(span * 0.3f)!!
        val far = blobAt(span * 0.7f)!!
        assertTrue("${far.width} should be less than ${near.width}", far.width < near.width)
        assertTrue("${far.height} should be less than ${near.height}", far.height < near.height)
    }

    @Test
    fun `the lump stays on the tab, however far the lens has gone`() {
        // centerX is relative to the lens, which is moving; on screen it must not budge.
        for (d in listOf(0.3f, 0.5f, 0.8f)) {
            val lensLeft = 500f + span * d - span / 2f
            val blob = blobAt(span * d)!!
            assertEquals("at $d", 500f, lensLeft + blob.centerX, 0.01f)
        }
    }

    @Test
    fun `it works the same pulling left`() {
        val right = blobAt(span * 0.6f)!!
        val left = trailingBlob(
            anchorCx = 500f,
            lensCx = 500f - span * 0.6f,
            lensLeft = 500f - span * 0.6f - span / 2f,
            lensHeight = restHeight,
            span = span,
            restHeight = restHeight,
        )!!
        assertEquals(right.merge, left.merge, 0.01f)
        assertEquals(right.width, left.width, 0.01f)
    }

    @Test
    fun `a bar that has not been measured yet has no blob`() {
        assertNull(
            trailingBlob(
                anchorCx = 0f,
                lensCx = 0f,
                lensLeft = 0f,
                lensHeight = 0f,
                span = 0f,
                restHeight = restHeight,
            ),
        )
    }
}
