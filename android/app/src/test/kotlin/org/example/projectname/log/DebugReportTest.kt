package org.example.projectname.log

import org.example.projectname.core.log.LogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugReportTest {
    private val header = listOf("App" to "org.example.projectname 0.0.0 (1), debug build", "Device" to "Example Phone 1")

    @Test
    fun startsWithTheTitleAndHeaderThenTheLog() {
        val report = DebugReport.compose("PROJECT_NAME debug log", header, "line one\nline two\n")
        assertEquals(
            "PROJECT_NAME debug log\n" +
                "App: org.example.projectname 0.0.0 (1), debug build\n" +
                "Device: Example Phone 1\n" +
                "\n" +
                "== Log ==\n" +
                "line one\nline two\n",
            report,
        )
    }

    @Test
    fun OUT6_headerValuesAreRedactedToo() {
        val leaky = listOf(
            "Destination" to "rtmp://live.example.com/app/sk_live_1234",
            "Server" to "192.168.100.20",
            "Note" to "password=hunter2hunter2",
        )
        val report = DebugReport.compose("PROJECT_NAME debug log", leaky, "")
        assertFalse(report.contains("sk_live_1234"))
        assertFalse(report.contains("192.168.100.20"))
        assertFalse(report.contains("hunter2hunter2"))
        assertTrue(report.contains("<redacted>"))
        assertTrue(report.contains("<ip>"))
    }

    @Test
    fun OUT6_aHeaderValueThatIsSecretOnlyBecauseOfItsNameIsRedacted() {
        val named = listOf(
            "Wi-Fi password" to "hunter2hunter2",
            "Stream key" to "abc123def456",
            "Passphrase" to "correct horse battery",
        )
        val report = DebugReport.compose("T", named, "")
        for ((_, value) in named) assertFalse("$value leaked", report.contains(value))
        // The names stay, so the report still says what was there.
        assertTrue(report.contains("Wi-Fi password: <redacted>\n"))
        assertTrue(report.contains("Stream key: <redacted>\n"))
        assertTrue(report.contains("Passphrase: <redacted>\n"))
    }

    @Test
    fun OUT6_logLinesFromTheBufferArriveRedacted() {
        val buffer = LogBuffer()
        buffer.append("Stream", "connecting to srt://192.0.2.10:9000?passphrase=correcthorse12")
        val report = DebugReport.compose("PROJECT_NAME debug log", header, buffer.dump())
        assertFalse(report.contains("correcthorse12"))
        assertFalse(report.contains("192.0.2.10"))
    }

    @Test
    fun aCrashLogFromTheLastSessionComesBeforeTheCurrentLog() {
        val report = DebugReport.compose("T", emptyList(), "now\n", previousCrash = "before\ncrash\n")
        assertTrue(report.indexOf("== Log from the session that crashed ==") < report.indexOf("== Log =="))
        assertTrue(report.contains("before\ncrash\n"))
        assertTrue(report.endsWith("now\n"))
    }

    @Test
    fun aLongLogKeepsItsNewestLinesAndSaysHowManyWereCut() {
        val log = (1..10_000).joinToString(separator = "") { "line $it of the log\n" }
        val report = DebugReport.compose("T", header, log, maxChars = 5_000)
        assertTrue(report.length <= 5_000)
        assertTrue(report.startsWith("T\nApp: "))
        assertTrue(report.endsWith("line 10000 of the log\n"))
        val note = Regex("""\((\d+) older lines were cut to keep the file small\)""").find(report)
        val cut = note!!.groupValues[1].toInt()
        val kept = report.substringAfter(note.value + "\n").lines().filter { it.isNotEmpty() }
        assertEquals(10_000, cut + kept.size)
        assertEquals("line ${cut + 1} of the log", kept.first())
    }

    @Test
    fun theCurrentLogGetsRoomBeforeTheCrashLog() {
        val crash = "c".repeat(100) + "\n"
        val log = (1..100).joinToString(separator = "") { "line $it\n" }
        val report = DebugReport.compose("T", emptyList(), log, previousCrash = crash.repeat(100), maxChars = 1_200)
        assertTrue(report.length <= 1_200)
        assertTrue(report.endsWith("line 100\n"))
    }

    @Test
    fun aFullLogBufferAndACrashLogAreSharedWholeAtTheDefaultLimit() {
        // A full buffer of long lines, and a crash log just as long. The log is shared as a file, so the
        // default limit keeps all of it and cuts nothing.
        val buffer = LogBuffer()
        val longLine = "Encoders ready: H.264 1920x1080 at 30 fps, 6000 kbps, keyframe every 2 s; " +
            "AAC 48000 Hz stereo, 128 kbps; ".repeat(2)
        repeat(2000) { buffer.append("CaptureEngine", "$it $longLine") }
        val log = buffer.dump()
        val report = DebugReport.compose("PROJECT_NAME debug log", header, log, previousCrash = log)
        assertTrue("the test needs a big log, not ${log.length} characters", log.length > 300_000)
        assertFalse(report.contains("older lines were cut"))
        assertTrue(report.contains("== Log from the session that crashed ==\n$log"))
        assertTrue(report.endsWith("== Log ==\n$log"))
    }
}
