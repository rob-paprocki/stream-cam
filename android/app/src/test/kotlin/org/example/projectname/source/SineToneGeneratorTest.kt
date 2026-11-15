package org.example.projectname.source

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class SineToneGeneratorTest {
    private fun samples(bytes: ByteArray, channels: Int, channel: Int): IntArray {
        val frames = bytes.size / (2 * channels)
        return IntArray(frames) { i ->
            val at = (i * channels + channel) * 2
            (bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)
        }
    }

    private fun oneSecond(generator: SineToneGenerator): ByteArray =
        ByteArray(generator.sampleRate * generator.bytesPerFrame).also { generator.fill(it) }

    @Test
    fun makesOneThousandCyclesPerSecondAtFortyEightKilohertz() {
        val generator = SineToneGenerator()
        val left = samples(oneSecond(generator), channels = 2, channel = 0)
        var risingZeroCrossings = 0
        for (i in 1 until left.size) if (left[i - 1] < 0 && left[i] >= 0) risingZeroCrossings++
        // The wave starts at zero on the way up, so the first cycle's start isn't counted as a crossing.
        assertEquals(999, risingZeroCrossings)
        assertEquals(48_000, left.size)
    }

    @Test
    fun repeatsExactlyEveryFortyEightSamples() {
        val left = samples(oneSecond(SineToneGenerator()), channels = 2, channel = 0)
        for (i in 48 until left.size) assertEquals("sample $i", left[i - 48], left[i])
    }

    @Test
    fun peaksAtMinusTwentyDbfs() {
        val left = samples(oneSecond(SineToneGenerator()), channels = 2, channel = 0)
        val peak = (0.1 * Short.MAX_VALUE).roundToInt()
        assertEquals(peak, left.max())
        assertEquals(-peak, left.min())
        assertEquals(0, left[0])
        assertEquals("a quarter period in, the wave is at its peak", peak, left[12])
    }

    @Test
    fun everyChannelCarriesTheSameSample() {
        val bytes = oneSecond(SineToneGenerator(channels = 2))
        assertArrayEquals(samples(bytes, 2, 0), samples(bytes, 2, 1))
    }

    @Test
    fun writesSixteenBitLittleEndian() {
        val generator = SineToneGenerator(channels = 1)
        val bytes = ByteArray(13 * 2)
        generator.fill(bytes)
        val value = samples(bytes, channels = 1, channel = 0)[12]
        assertEquals(value and 0xFF, bytes[24].toInt() and 0xFF)
        assertEquals((value shr 8) and 0xFF, bytes[25].toInt() and 0xFF)
    }

    @Test
    fun continuesSmoothlyFromOneBufferToTheNext() {
        val whole = ByteArray(2000 * 4).also { SineToneGenerator().fill(it) }
        val pieces = SineToneGenerator()
        val first = ByteArray(1000 * 4).also { pieces.fill(it) }
        val second = ByteArray(1000 * 4).also { pieces.fill(it) }
        assertArrayEquals(whole, first + second)
        assertEquals(2000L, pieces.framesGenerated)
    }

    @Test
    fun fillsOnlyTheFramesAskedFor() {
        val bytes = ByteArray(100) { 7 }
        SineToneGenerator(channels = 2).fill(bytes, frames = 10)
        assertTrue(bytes.copyOfRange(40, 100).all { it.toInt() == 7 })
    }

    @Test
    fun timestampsFollowTheSampleCountWithoutDrift() {
        val start = 5_000_000L
        assertEquals(start, SineToneGenerator.timestampMicros(start, 0, 48_000))
        assertEquals(start + 21_333, SineToneGenerator.timestampMicros(start, 1024, 48_000))
        assertEquals(start + 1_000_000, SineToneGenerator.timestampMicros(start, 48_000, 48_000))
        // Ten hours of 1024-sample buffers land exactly on ten hours.
        val buffers = 48_000L * 36_000 / 1024
        assertEquals(start + 36_000_000_000L, SineToneGenerator.timestampMicros(start, buffers * 1024, 48_000))
        // Each buffer starts no more than a microsecond off its ideal time, and never earlier than the last.
        var previous = Long.MIN_VALUE
        for (n in 0L until 2000L) {
            val ts = SineToneGenerator.timestampMicros(0, n * 1024, 48_000)
            val ideal = n * 1024 * 1_000_000.0 / 48_000
            assertTrue(abs(ts - ideal) < 1.0)
            assertTrue(ts > previous)
            previous = ts
        }
    }
}
