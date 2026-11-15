package org.example.projectname.source

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Makes a continuous sine tone as 16-bit little-endian PCM, with the same sample on every channel.
 *
 * One period of the wave is worked out once and then repeated sample by sample, so the tone never drifts
 * and never clicks between buffers, however long it runs. At 48 kHz a 1 kHz tone repeats every 48 samples.
 * It has no Android types, so the unit tests can check it on any machine.
 */
class SineToneGenerator(
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
    val frequencyHz: Int = 1_000,
    amplitude: Double = REFERENCE_AMPLITUDE,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive, got $sampleRate" }
        require(channels in 1..8) { "channels must be from 1 to 8, got $channels" }
        require(frequencyHz > 0 && frequencyHz * 2 < sampleRate) {
            "frequencyHz must be above 0 and below half the sample rate, got $frequencyHz"
        }
        require(amplitude > 0.0 && amplitude <= 1.0) { "amplitude must be above 0 and at most 1, got $amplitude" }
    }

    /** One full period of the wave, as sample values. */
    private val period: ShortArray = run {
        val length = sampleRate / gcd(sampleRate, frequencyHz)
        ShortArray(length) { i ->
            (amplitude * Short.MAX_VALUE * sin(2.0 * PI * frequencyHz * i / sampleRate)).roundToInt().toShort()
        }
    }

    private var position = 0

    /** How many sample frames (one sample per channel) this generator has made so far. */
    var framesGenerated: Long = 0
        private set

    /** Bytes in one sample frame: two bytes per channel. */
    val bytesPerFrame: Int get() = channels * 2

    /** Fills the first [frames] sample frames of [buffer], continuing the wave from the last call. */
    fun fill(buffer: ByteArray, frames: Int = buffer.size / bytesPerFrame) {
        require(frames >= 0 && frames * bytesPerFrame <= buffer.size) {
            "the buffer holds ${buffer.size / bytesPerFrame} frames, not $frames"
        }
        var offset = 0
        repeat(frames) {
            val sample = period[position].toInt()
            val low = (sample and 0xFF).toByte()
            val high = ((sample shr 8) and 0xFF).toByte()
            repeat(channels) {
                buffer[offset++] = low
                buffer[offset++] = high
            }
            position++
            if (position == period.size) position = 0
        }
        framesGenerated += frames
    }

    companion object {
        /** -20 dBFS, the SMPTE reference level for a line-up tone. */
        const val REFERENCE_AMPLITUDE = 0.1

        /**
         * The presentation time, in microseconds, of the sample frame that comes [framesBefore] frames
         * after the one at [startMicros]. It is worked out from the total each time, so rounding never
         * adds up over a long recording.
         */
        fun timestampMicros(startMicros: Long, framesBefore: Long, sampleRate: Int): Long =
            startMicros + framesBefore * 1_000_000L / sampleRate

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
