package org.example.projectname.core.log

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogBufferTest {

    /** A clock that moves on by [stepMillis] each time it is read. */
    private class SteppingClock(start: Instant, private val stepMillis: Long = 0) : Clock() {
        private var now = start
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now.also { now = now.plusMillis(stepMillis) }
    }

    private val start = Instant.parse("2026-11-14T10:15:30.123Z")

    @Test
    fun linesStartWithIsoUtcTimestampsAndTheTag() {
        val buffer = LogBuffer(clock = SteppingClock(start, stepMillis = 877))
        buffer.append("Session", "Record pressed")
        buffer.append("Encoder", "started")
        assertEquals(
            "2026-11-14T10:15:30.123Z Session: Record pressed\n" +
                "2026-11-14T10:15:31.000Z Encoder: started\n",
            buffer.dump(),
        )
    }

    @Test
    fun timestampsAreUtcWhateverTheClockZone() {
        val tokyo = Clock.fixed(start, ZoneId.of("Asia/Tokyo"))
        val buffer = LogBuffer(clock = tokyo)
        buffer.append("Tag", "message")
        assertTrue(buffer.dump().startsWith("2026-11-14T10:15:30.123Z "), buffer.dump())
    }

    @Test
    fun OUT6_theDumpIsAlreadyRedacted() {
        val buffer = LogBuffer(clock = SteppingClock(start))
        buffer.append("Stream", "publish rtmp://live.example.com/app/examplekey to 192.168.100.20")
        buffer.append("Srt", "srt://[2001:db8::20]:9000?passphrase=example-passphrase")
        buffer.append("Usb serial=EXAMPLESERIAL", "iSerialNumber 3 EXAMPLESERIAL\nAuthorization: Bearer exampletoken")
        val dump = buffer.dump()
        for (secret in listOf("examplekey", "192.168.100.20", "2001:db8", "example-passphrase", "EXAMPLESERIAL", "exampletoken")) {
            assertFalse(secret in dump, "\"$secret\" reached the dump:\n$dump")
        }
        assertTrue("rtmp://live.example.com/app/<redacted> to <ip>" in dump, dump)
    }

    @Test
    fun keepsTheNewestLinesAndSaysHowManyWereDropped() {
        val buffer = LogBuffer(capacity = 3, clock = SteppingClock(start))
        for (n in 1..5) buffer.append("T", "line $n")
        val lines = buffer.dump().lines().filter { it.isNotEmpty() }
        assertEquals("(2 earlier lines were dropped)", lines.first())
        assertEquals(listOf("line 3", "line 4", "line 5"), lines.drop(1).map { it.substringAfter("T: ") })
    }

    @Test
    fun aMultiLineMessageStaysOneEntryWithIndentedLines() {
        val buffer = LogBuffer(capacity = 2, clock = SteppingClock(start))
        buffer.append("Crash", "first\r\nsecond\nthird")
        buffer.append("Tag\nwith break", "next")
        assertEquals(
            "2026-11-14T10:15:30.123Z Crash: first\n    second\n    third\n" +
                "2026-11-14T10:15:30.123Z Tag with break: next\n",
            buffer.dump(),
        )
    }

    @Test
    fun OUT6_aVeryLongMessageIsCutWithANote() {
        val buffer = LogBuffer(clock = SteppingClock(start))
        buffer.append("Srt", "short line\npassword=" + "x".repeat(100_000))
        assertEquals(
            "2026-11-14T10:15:30.123Z Srt: short line\n    password=<redacted> [line cut: 84009 characters]\n",
            buffer.dump(),
        )
    }

    @Test
    fun anEmptyBufferDumpsNothing() {
        assertEquals("", LogBuffer().dump())
    }

    @Test
    fun capacityMustBePositive() {
        assertFailsWith<IllegalArgumentException> { LogBuffer(capacity = 0) }
    }

    @Test
    fun manyThreadsCanAppendAndDumpAtOnce() {
        val threads = 8
        val perThread = 2_000
        val buffer = LogBuffer(capacity = 5_000)
        val pool = Executors.newFixedThreadPool(threads + 1)
        val ready = CountDownLatch(1)
        try {
            val writers = (1..threads).map { t ->
                pool.submit {
                    ready.await()
                    repeat(perThread) { i -> buffer.append("T$t", "message $i") }
                }
            }
            val reader = pool.submit {
                ready.await()
                repeat(50) { buffer.dump() }
            }
            ready.countDown()
            writers.forEach { it.get(30, TimeUnit.SECONDS) }
            reader.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        val lines = buffer.dump().lines().filter { it.isNotEmpty() }
        assertEquals("(${threads * perThread - 5_000} earlier lines were dropped)", lines.first())
        assertEquals(5_000, lines.size - 1)
    }
}
