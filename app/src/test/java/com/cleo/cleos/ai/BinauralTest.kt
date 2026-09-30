package com.cleo.cleos.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

class BinauralTest {
    /** The file the app ships (unit tests run in the module's folder). */
    private val shipped by lazy { Hrirs.parse(File("src/main/assets/hrir/ku100_near.bin").readBytes()) }

    private fun tone(seconds: Double, rate: Int, hz: Double = 440.0) =
        FloatArray((seconds * rate).toInt()) { (0.3 * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun silence(seconds: Double, rate: Int) = FloatArray((seconds * rate).toInt())

    /** Left and right power in dB, from interleaved stereo. */
    private fun ears(stereo: FloatArray): Pair<Double, Double> {
        var l = 0.0
        var r = 0.0
        for (i in stereo.indices step 2) {
            l += stereo[i].toDouble() * stereo[i]
            r += stereo[i + 1].toDouble() * stereo[i + 1]
        }
        return 10 * log10(l) to 10 * log10(r)
    }

    @Test
    fun theShippedFileHasTheLeftEarWhereItShouldBe() {
        val h = shipped
        assertEquals(48_000, h.rate)
        assertEquals(listOf(0.25f, 0.5f, 0.75f), h.distances.toList())
        val left = FloatArray(h.taps)
        val right = FloatArray(h.taps)
        fun db(a: FloatArray) = 10 * log10(a.sumOf { it.toDouble() * it })
        h.at(90.0, 0.25, left, right)
        assertTrue("90° is the left: ${db(left) - db(right)} dB", db(left) - db(right) > 15)
        h.at(-90.0, 0.25, left, right)
        assertTrue("-90° is 270°, the right", db(right) - db(left) > 15)
        h.at(0.0, 0.25, left, right)
        assertTrue("straight ahead both ears alike", abs(db(left) - db(right)) < 2)
    }

    @Test
    fun betweenTwoDistancesIsBetweenTheirResponses() {
        // One tap, the same at every azimuth: 1 and -1 at 0.25 m, 3 and -3 at 0.5 m, 5 and -5 at 0.75 m.
        val data = FloatArray(3 * 360 * 2) { i ->
            val d = i / (360 * 2)
            val ear = i % 2
            (2 * d + 1f) * (if (ear == 0) 1 else -1)
        }
        val h = Hrirs(48_000, floatArrayOf(0.25f, 0.5f, 0.75f), 1, data)
        val l = FloatArray(1)
        val r = FloatArray(1)
        h.at(450.0, 0.375, l, r)
        assertEquals(2f, l[0], 1e-5f)
        assertEquals(-2f, r[0], 1e-5f)
        h.at(0.0, 0.1, l, r)
        assertEquals("nearer than measured is the nearest there is", 1f, l[0], 1e-5f)
        h.at(0.0, 2.0, l, r)
        assertEquals(5f, l[0], 1e-5f)
    }

    @Test
    fun theFileReadsBackWhatWasWritten() {
        val taps = 2
        val values = ShortArray(2 * 360 * 2 * taps) { (it % 1000).toShort() }
        val b = ByteBuffer.allocate(4 + 6 * 4 + 4 + 2 * 4 + values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("HRIR".toByteArray())
        for (v in intArrayOf(1, 44_100, 2, 360, 2, taps)) b.putInt(v)
        b.putFloat(0.5f)
        b.putFloat(0.25f).putFloat(0.5f)
        for (v in values) b.putShort(v)
        val h = Hrirs.parse(b.array())
        assertEquals(44_100, h.rate)
        val l = FloatArray(taps)
        val r = FloatArray(taps)
        h.at(1.0, 0.25, l, r)
        // Azimuth 1 at the first distance: values 4, 5 (left) and 6, 7 (right), times the scale.
        assertEquals(listOf(2f, 2.5f), l.toList())
        assertEquals(listOf(3f, 3.5f), r.toList())
    }

    @Test
    fun resamplingKeepsWhenThingsArrive() {
        val taps = 128
        val data = FloatArray(3 * 360 * 2 * taps).also { d -> for (f in 0 until 3 * 360 * 2) d[f * taps + 40] = 1f }
        val h = Hrirs(48_000, floatArrayOf(0.25f, 0.5f, 0.75f), taps, data)
        for ((to, at) in listOf(24_000 to 20, 16_000 to 13, 44_100 to 37)) {
            val r = h.resampled(to)
            val l = FloatArray(r.taps)
            val rr = FloatArray(r.taps)
            r.at(0.0, 0.25, l, rr)
            val peak = l.indices.maxBy { abs(l[it]) }
            assertTrue("$to Hz: the impulse at ${40.0 * to / 48_000} lands at $peak", abs(peak - at) <= 1)
        }
        assertTrue("same rate, same thing", h.resampled(48_000) === h)
    }

    @Test
    fun theSpeechClockStandsStillInSilenceAndNotInAShortGap() {
        val rate = 16_000
        val x = silence(1.0, rate) + tone(1.0, rate) + silence(0.2, rate) + tone(1.0, rate) + silence(1.0, rate)
        val clock = SpeechClock.of(x, rate)
        assertTrue("two seconds of speech and the gap between: ${clock.total}", clock.total in 2.0..2.4)
        assertEquals("nothing before the first word", 0.0, clock.at(0.5), 1e-9)
        assertEquals("nothing after the last", clock.at(3.7), clock.at(4.1), 1e-9)
        assertTrue("the short gap still moves it", clock.at(2.2) - clock.at(2.0) > 0.15)
    }

    @Test
    fun earToEarGoesRoundTheBackNeverAcrossTheFace() {
        assertEquals(180.0, EarPath.turn(90.0, 270.0), 1e-9)
        assertEquals(-180.0, EarPath.turn(270.0, 90.0), 1e-9)
        assertEquals(90.0, EarPath.turn(90.0, 180.0), 1e-9)
        assertEquals(20.0, EarPath.turn(350.0, 10.0), 1e-9)
        assertEquals(90.0, EarPath.turn(0.0, 90.0), 1e-9)
    }

    @Test
    fun whateverWalkItTakesItStaysNearTheHead() {
        val rate = 16_000
        val x = tone(8.0, rate)
        for (seed in 0 until 40) {
            val path = EarPath(SpeechClock.of(x, rate), Random(seed))
            for (i in 0..80) {
                val (_, d) = path.at(i * 0.1)
                assertTrue("seed $seed at ${i * 0.1}s: $d m", d in 0.23..0.45)
            }
        }
    }

    @Test
    fun aVoiceAtTheLeftEarIsLoudOnTheLeft() {
        val rate = 24_000
        val h = shipped.resampled(rate)
        // Noise, all pitches at once: a head shades high sounds far more than low ones.
        val random = Random(1)
        val x = FloatArray(rate) { (random.nextFloat() - 0.5f) * 0.4f }
        val (atLeftL, atLeftR) = ears(Binaural.render(x, h) { 90.0 to 0.25 })
        assertTrue("left ear: ${atLeftL - atLeftR} dB", atLeftL - atLeftR > 10)
        val (atRightL, atRightR) = ears(Binaural.render(x, h) { 270.0 to 0.25 })
        assertTrue("right ear: ${atRightR - atRightL} dB", atRightR - atRightL > 10)
        val (frontL, frontR) = ears(Binaural.render(x, h) { 0.0 to 0.42 })
        assertTrue("in front: ${frontL - frontR} dB", abs(frontL - frontR) < 3)
    }

    @Test
    fun itComesOutAsLongAsLoudAndNeverClipping() {
        val rate = 24_000
        val h = shipped.resampled(rate)
        val x = silence(0.3, rate) + tone(3.0, rate) + silence(0.3, rate)
        val started = System.nanoTime()
        val out = Binaural.render(x, h, Random(7))
        println("rendered ${x.size / rate.toDouble()} s at $rate Hz in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertEquals(2 * (x.size + h.taps - 1), out.size)
        assertTrue(out.maxOf { abs(it) } <= 0.8901f)
        val inPower = x.sumOf { it.toDouble() * it } / x.size
        val outPower = out.sumOf { it.toDouble() * it } / out.size
        // Twice the recording's power over the two ears, unless that would have clipped.
        assertTrue("out/in ${outPower / inPower}", outPower / inPower in 0.3..2.05)
    }
}
