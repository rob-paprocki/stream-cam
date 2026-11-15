package org.example.projectname.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class LogExportTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val now: Instant = Instant.parse("2026-11-14T09:30:05.678Z")

    @Test
    fun OUT6_theFileIsNamedAfterTheAppAndTheTimeOnly() {
        assertEquals("PROJECT_NAME-log-20261114T093005Z.txt", LogExport.fileName(now))
    }

    @Test
    fun theFileHoldsTheWholeReportAsUtf8() {
        val report = "PROJECT_NAME debug log\nDevice: Exämple Phone 1 – test\n\n== Log ==\n" + "line\n".repeat(50_000)
        val file = LogExport.write(LogExport.folderIn(temp.root), report, now)
        assertEquals("PROJECT_NAME-log-20261114T093005Z.txt", file.name)
        assertEquals(report, file.readText(Charsets.UTF_8))
    }

    @Test
    fun theFileGoesInTheSharedLogFolderWhichIsMadeWhenMissing() {
        val folder = LogExport.folderIn(temp.root)
        assertFalse(folder.exists())
        val file = LogExport.write(folder, "log\n", now)
        assertEquals(File(temp.root, "shared-logs"), file.parentFile)
    }

    @Test
    fun eachExportReplacesTheOneBefore() {
        val folder = LogExport.folderIn(temp.root)
        val first = LogExport.write(folder, "first\n", now)
        val second = LogExport.write(folder, "second\n", now.plusSeconds(60))
        assertFalse(first.exists())
        assertEquals(listOf(second), folder.listFiles()!!.toList())
        assertEquals("second\n", second.readText())
    }

    @Test
    fun anExportInTheSameSecondAsTheLastOneStillWritesTheNewReport() {
        val folder = LogExport.folderIn(temp.root)
        LogExport.write(folder, "first\n", now)
        val again = LogExport.write(folder, "second\n", now)
        assertEquals("second\n", again.readText())
        assertEquals(1, folder.listFiles()!!.size)
    }

    @Test
    fun nothingHalfWrittenIsLeftBehind() {
        val folder = LogExport.folderIn(temp.root)
        LogExport.write(folder, "log\n", now)
        assertTrue(folder.listFiles()!!.all { it.name.endsWith(".txt") })
    }
}
