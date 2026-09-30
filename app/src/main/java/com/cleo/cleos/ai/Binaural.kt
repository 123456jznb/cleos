package com.cleo.cleos.ai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// A voice close to the ear, on headphones: a mono recording through what a head does to sound
// arriving from one direction and distance, measured. How the voice moves around the head (only
// while it speaks, the random walks, the timings, the drift) is ported from binaural-voice
// (github.com/Saekisui/binaural-voice, MIT, Copyright (c) 2026 Saekisui), whose author compared it
// blind against the phone and web 3D-audio APIs: those change only the volume with distance, and
// a voice at 25 cm does not sound close to the ear that way.

/**
 * Head-related impulse responses: what reaches each eardrum from a sound at a given direction and
 * distance. Measured on a Neumann KU100 dummy head in the near field, from 25 cm: J. M. Arend,
 * A. Neidhardt, C. Pörschmann, "Spherical Near-Field (NF) HRIR Compilation of the Neumann KU100",
 * Zenodo 2020, doi:10.5281/zenodo.4297951, CC BY 4.0. The app keeps the horizontal plane, 1° apart,
 * at 0.25, 0.5 and 0.75 m, each already multiplied by the dataset's gain for its distance
 * (assets/hrir/README.txt).
 *
 * Azimuths are degrees counter-clockwise from straight ahead: 90 is the left ear, 270 the right.
 */
class Hrirs(val rate: Int, val distances: FloatArray, val taps: Int, private val data: FloatArray) {
    private fun offset(d: Int, az: Int, ear: Int) = ((d * AZIMUTHS + az) * 2 + ear) * taps

    /**
     * The responses for a sound at [azimuth] and [distance] into [left] and [right] ([taps] long):
     * the nearest whole degree, and between the two nearest distances.
     */
    fun at(azimuth: Double, distance: Double, left: FloatArray, right: FloatArray) {
        val a = Math.floorMod(azimuth.roundToInt(), AZIMUTHS)
        val d = distance.coerceIn(distances.first().toDouble(), distances.last().toDouble())
        var k = 0
        while (k < distances.size - 2 && d >= distances[k + 1]) k++
        val w = ((d - distances[k]) / (distances[k + 1] - distances[k])).toFloat().coerceIn(0f, 1f)
        val nearL = offset(k, a, 0)
        val farL = offset(k + 1, a, 0)
        val nearR = offset(k, a, 1)
        val farR = offset(k + 1, a, 1)
        for (t in 0 until taps) {
            left[t] = (1 - w) * data[nearL + t] + w * data[farL + t]
            right[t] = (1 - w) * data[nearR + t] + w * data[farR + t]
        }
    }

    /**
     * The same responses at another sample rate, to go with a recording at [to]: each one taken
     * through a windowed sinc, below the lower of the two rates' limits so nothing folds back.
     * (Their overall level changes with the rate; Binaural.render sets the level afterwards.)
     */
    fun resampled(to: Int): Hrirs {
        if (to == rate) return this
        val ratio = to.toDouble() / rate
        val cutoff = min(1.0, ratio)
        val half = ZEROS / cutoff
        val outTaps = ceil((taps + half) * ratio).toInt()
        val weights = Array(outTaps) { n ->
            val t = n / ratio
            FloatArray(taps) { k -> kernel(t - k, cutoff, half).toFloat() }
        }
        val count = distances.size * AZIMUTHS * 2
        val out = FloatArray(count * outTaps)
        for (f in 0 until count) {
            val src = f * taps
            val dst = f * outTaps
            for (n in 0 until outTaps) {
                val w = weights[n]
                var acc = 0f
                for (k in 0 until taps) acc += w[k] * data[src + k]
                out[dst + n] = acc
            }
        }
        return Hrirs(to, distances, outTaps, out)
    }

    companion object {
        const val AZIMUTHS = 360

        /** Zero crossings each side of the resampling kernel. */
        private const val ZEROS = 16.0

        private fun kernel(x: Double, cutoff: Double, half: Double): Double {
            if (abs(x) >= half) return 0.0
            val y = cutoff * x
            val sinc = if (abs(y) < 1e-9) 1.0 else sin(PI * y) / (PI * y)
            return cutoff * sinc * (0.5 + 0.5 * cos(PI * x / half))
        }

        /**
         * The app's file (assets/hrir/ku100_near.bin): "HRIR", then version, rate, distances,
         * azimuths, ears and taps as int32, a float32 scale, the distances as float32, and the
         * responses as int16 in that order ([distance][azimuth][ear][tap]), little-endian.
         */
        fun parse(bytes: ByteArray): Hrirs {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic, Charsets.US_ASCII) == "HRIR") { "not an HRIR file" }
            require(b.int == 1) { "unknown HRIR file version" }
            val rate = b.int
            val count = b.int
            val azimuths = b.int
            val ears = b.int
            val taps = b.int
            require(azimuths == AZIMUTHS && ears == 2 && count >= 2) { "unexpected HRIR layout" }
            val scale = b.float
            val distances = FloatArray(count) { b.float }
            val values = b.asShortBuffer()
            val data = FloatArray(count * azimuths * ears * taps) { values.get(it) * scale }
            return Hrirs(rate, distances, taps, data)
        }
    }
}

/**
 * Seconds of speech so far, at each moment of a recording: the clock the voice's movement runs on.
 * It stands still in a pause, so the voice doesn't move while nothing is heard (the next words
 * would seem to jump from one ear to the other), and eases in and out of the stops.
 */
class SpeechClock private constructor(private val tau: DoubleArray) {
    /** Speech seconds up to [t] seconds into the recording. */
    fun at(t: Double): Double {
        if (tau.size < 2) return 0.0
        val x = t / STEP
        if (x <= 0) return tau[0]
        if (x >= tau.size - 1) return tau.last()
        val i = x.toInt()
        return tau[i] + (tau[i + 1] - tau[i]) * (x - i)
    }

    /** All the speech in it, in seconds. */
    val total: Double get() = tau.last()

    companion object {
        const val STEP = 0.01

        /** Loudness below this share of the loudest 10 ms is silence. */
        private const val QUIET = 0.05

        /** A silence between words shorter than this (in steps) is still speech. */
        private const val GAP = 40

        /** The clock's speed is smoothed over this many steps. */
        private const val SMOOTH = 20

        fun of(x: FloatArray, rate: Int): SpeechClock {
            val hop = max(1, rate / 100)
            val steps = if (x.size > hop) (x.size - hop + hop - 1) / hop else 0
            val rms = DoubleArray(steps) { n ->
                var sum = 0.0
                for (i in n * hop until n * hop + hop) sum += x[i].toDouble() * x[i]
                sqrt(sum / hop)
            }
            val loudest = rms.maxOrNull() ?: 0.0
            val voiced = BooleanArray(steps) { rms[it] > loudest * QUIET }
            // Short gaps between words count as speech; a silence at either end doesn't.
            var i = 0
            while (i < steps) {
                var j = i
                while (j < steps && !voiced[j]) j++
                if (i > 0 && j < steps && j - i < GAP) for (k in i until j) voiced[k] = true
                i = max(j, i + 1)
            }
            val tau = DoubleArray(steps + 1)
            for (k in 0 until steps) {
                var on = 0
                for (j in k - SMOOTH / 2 until k + SMOOTH / 2) if (j in 0 until steps && voiced[j]) on++
                tau[k + 1] = tau[k] + on.toDouble() / SMOOTH * STEP
            }
            return SpeechClock(tau)
        }
    }
}

/**
 * Where the voice is while it speaks. One of a few short walks, picked at random: staying at one
 * ear; going round from one ear to the other behind the head (with a stop behind it when there is
 * time); coming from behind to an ear; coming in from the front. Never a steady swing from side to
 * side, which sounds mechanical, and from ear to ear always round the back, never across the face.
 */
class EarPath(private val clock: SpeechClock, random: Random) {
    private enum class Place(val azimuth: Double, val distance: Double) {
        LeftEar(90.0, 0.25),
        RightEar(270.0, 0.25),
        Behind(180.0, 0.31),
        Front(0.0, 0.42),
    }

    private class Leg(val from: Double, val to: Double, val start: Double, val change: Double)

    private val azimuthLegs = ArrayList<Leg>()
    private val distanceLegs = ArrayList<Leg>()
    private val start: Place
    private val phase: Double

    init {
        val total = clock.total
        val ear = if (random.nextBoolean()) Place.LeftEar else Place.RightEar
        val other = if (ear == Place.LeftEar) Place.RightEar else Place.LeftEar
        // By weight: at one ear 1, round to the other 2, from behind 1, from the front 1.
        val cues = when (random.nextInt(5)) {
            0 -> listOf(0.0 to ear)
            1, 2 -> if (total > 6) listOf(0.0 to ear, 0.2 * total to Place.Behind, 0.6 * total to other) else listOf(0.0 to ear, 0.3 * total to other)
            3 -> listOf(0.0 to Place.Behind, 0.35 * total to ear)
            else -> listOf(0.0 to Place.Front, 0.35 * total to ear)
        }
        start = cues.first().second
        for ((s, place) in cues.drop(1)) {
            val azimuth = along(azimuthLegs, s, start.azimuth)
            val distance = along(distanceLegs, s, start.distance)
            val turn = turn(azimuth, place.azimuth)
            val usual = if (abs(turn) > 1) abs(turn) / 180 * HALF_TURN else DISTANCE_MOVE
            // A short message still gets there, at most four tenths faster than usual.
            val takes = max(min(usual, 0.9 * (total - s)), 0.6 * usual)
            if (abs(turn) > 1) azimuthLegs += Leg(s, s + takes, azimuth, turn)
            if (abs(place.distance - distance) > 0.01) distanceLegs += Leg(s, s + takes, distance, place.distance - distance)
        }
        phase = random.nextDouble(0.0, 2 * PI)
    }

    /** Azimuth and distance at [t] seconds into the recording, with a little drift so it never sits dead still. */
    fun at(t: Double): Pair<Double, Double> {
        val s = clock.at(t)
        val azimuth = along(azimuthLegs, s, start.azimuth) +
            DRIFT_DEGREES * sin(2 * PI * t / 4.3 + phase) + 2 * sin(2 * PI * t / 1.9 + 2 * phase)
        val distance = along(distanceLegs, s, start.distance) + DRIFT_METRES * sin(2 * PI * t / 3.7 + 3 * phase)
        return azimuth to distance
    }

    private fun along(legs: List<Leg>, s: Double, from: Double): Double {
        val leg = legs.lastOrNull { it.from <= s } ?: return from
        val done = min(1.0, (s - leg.from) / (leg.to - leg.from))
        return leg.start + leg.change * (1 - cos(PI * done)) / 2
    }

    companion object {
        /** Speech seconds for half a turn round the head. */
        private const val HALF_TURN = 4.5

        /** Speech seconds for moving nearer or further only. */
        private const val DISTANCE_MOVE = 1.5
        private const val DRIFT_DEGREES = 5.0
        private const val DRIFT_METRES = 0.015

        /** From [from] to [to] degrees: the short way, unless that is across the face (ear to ear), then behind. */
        fun turn(from: Double, to: Double): Double {
            val a = degrees(from)
            val short = degrees(to - a + 180) - 180
            return if (abs(short) <= 150) short else to - a
        }

        private fun degrees(x: Double) = ((x % 360) + 360) % 360
    }
}

object Binaural {
    /** Frames of this many samples, half overlapping, each through the responses for where the voice is at its middle. */
    private const val FRAME = 1024
    private const val HOP = FRAME / 2

    /** The loudest a sample may end up, below full scale. */
    private const val PEAK = 0.89f

    /**
     * [mono] (at [hrirs]' rate) as it sounds from beside the head, moving: stereo, left and right
     * interleaved, as loud as the recording was (a little louder at the near ear, as close speech is).
     */
    fun render(mono: FloatArray, hrirs: Hrirs, random: Random): FloatArray =
        render(mono, hrirs, EarPath(SpeechClock.of(mono, hrirs.rate), random)::at)

    /** [render], with the voice wherever [where] (seconds into the recording to azimuth and distance) puts it. */
    fun render(mono: FloatArray, hrirs: Hrirs, where: (Double) -> Pair<Double, Double>): FloatArray {
        val rate = hrirs.rate
        val taps = hrirs.taps
        val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / FRAME)).toFloat() }
        val length = mono.size + taps - 1
        val stereo = FloatArray(2 * length)
        val frame = FloatArray(FRAME)
        val left = FloatArray(taps)
        val right = FloatArray(taps)
        var s = -HOP
        while (s < mono.size) {
            var any = false
            for (i in 0 until FRAME) {
                val j = s + i
                frame[i] = if (j in mono.indices) mono[j] * window[i] else 0f
                if (frame[i] != 0f) any = true
            }
            if (any) {
                val (azimuth, distance) = where((s + FRAME / 2.0) / rate)
                hrirs.at(azimuth, distance, left, right)
                for (i in 0 until FRAME) {
                    val v = frame[i]
                    if (v == 0f) continue
                    val o = s + i
                    for (k in 0 until taps) {
                        val at = o + k
                        if (at < 0 || at >= length) continue
                        stereo[2 * at] += v * left[k]
                        stereo[2 * at + 1] += v * right[k]
                    }
                }
            }
            s += HOP
        }
        // As loud as the recording (its power, doubled over the two ears), and never clipping.
        val power = mono.fold(0.0) { a, v -> a + v.toDouble() * v } / max(1, mono.size)
        val got = stereo.fold(0.0) { a, v -> a + v.toDouble() * v } / max(1, stereo.size)
        var gain = if (got > 0) sqrt(power * 2 / got).toFloat() else 1f
        val peak = stereo.maxOfOrNull { abs(it) } ?: 0f
        if (peak * gain > PEAK) gain = PEAK / peak
        for (i in stereo.indices) stereo[i] *= gain
        return stereo
    }
}
