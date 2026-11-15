package org.example.projectname.capture

import org.example.projectname.capture.Mp4Bytes.box
import org.example.projectname.capture.Mp4Bytes.concat
import org.example.projectname.capture.Mp4Bytes.ftyp
import org.example.projectname.capture.Mp4Bytes.header
import org.example.projectname.capture.Mp4Bytes.header64
import org.example.projectname.capture.Mp4Bytes.largeBox
import org.example.projectname.capture.Mp4Bytes.mdat
import org.example.projectname.capture.Mp4Bytes.moov
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Mp4CheckTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun file(bytes: ByteArray): File = temp.newFile().apply { writeBytes(bytes) }

    private fun problem(bytes: ByteArray): String? = Mp4Check.problemWith(file(bytes))

    private fun assertFails(bytes: ByteArray, mentions: String) {
        val problem = problem(bytes)
        assertNotNull("should fail the check", problem)
        assertTrue("\"$problem\" should mention \"$mentions\"", problem!!.contains(mentions))
    }

    @Test
    fun REC1_aWholeMinimalMp4Passes() {
        assertNull(problem(Mp4Bytes.whole()))
    }

    @Test
    fun REC1_theIndexMayComeBeforeTheMediaAndTheMediaMayHaveA64BitSize() {
        assertNull(problem(concat(ftyp, moov, mdat())))
        assertNull(problem(concat(ftyp, largeBox("mdat", ByteArray(500)), moov)))
    }

    @Test
    fun REC1_aFileWithNoMoovBoxFails() {
        // What a recording looks like when MediaMuxer couldn't write the index: the media is all there.
        assertFails(concat(ftyp, box("free", ByteArray(16)), mdat()), "moov")
    }

    @Test
    fun REC1_aTruncatedBoxFails() {
        val whole = Mp4Bytes.whole()
        assertFails(whole.copyOf(whole.size - 10), "moov")
        assertFails(whole.copyOf(whole.size - 10), "bytes")
        // Cut in the middle of the media: the media box says it is longer than what is left.
        assertFails(whole.copyOf(ftyp.size + 24 + 500), "mdat")
    }

    @Test
    fun REC1_aFileThatEndsPartWayThroughABoxHeaderFails() {
        assertFails(concat(Mp4Bytes.whole(), ByteArray(3)), "3 bytes")
        assertFails(concat(ftyp, moov, header64(16, "mdat").copyOf(12)), "mdat")
    }

    @Test
    fun REC1_aCountOfOneIsWrittenAsOneByte() {
        assertFails(ByteArray(1), "ends with 1 byte,")
        assertFails(concat(Mp4Bytes.whole(), ByteArray(1)), "ends with 1 byte,")
        assertFails(concat(ftyp, mdat(), moov, header64(1, "free")), "as 1 byte,")
    }

    @Test
    fun REC1_theMediaBoxMediaMuxerLeavesUnfinishedFails() {
        // MediaMuxer writes "????" as the media box's size and fills it in only when the recording stops.
        val unfinished = concat("????mdat".toByteArray(Charsets.US_ASCII), ByteArray(1000))
        assertFails(concat(ftyp, box("free", ByteArray(16)), unfinished), "mdat")
        val unfinished64 = concat(header64(0x3F3F3F3F3F3F3F3FL, "mdat"), ByteArray(1000))
        assertFails(concat(ftyp, unfinished64), "mdat")
    }

    @Test
    fun REC1_aBoxWhoseSizeWasNeverFilledInFails() {
        // A size of 0 means "to the end of the file" in the format, but a writer leaves it in a box it never finished.
        assertFails(concat(ftyp, mdat(), header(0, "moov"), ByteArray(200)), "moov")
    }

    @Test
    fun REC1_aBoxShorterThanItsOwnHeaderFails() {
        assertFails(concat(ftyp, header(4, "free"), mdat(), moov), "free")
        assertFails(concat(ftyp, header64(12, "mdat"), moov), "mdat")
    }

    @Test
    fun REC1_aSizeTooBigToBeRealFails() {
        assertFails(concat(ftyp, header64(-16, "mdat"), ByteArray(100), moov), "mdat")
    }

    @Test
    fun REC1_aFileThatDoesntStartWithFtypFails() {
        assertFails(concat(mdat(), moov), "ftyp")
        assertFails(concat(moov, ftyp, mdat()), "ftyp")
    }

    @Test
    fun REC1_anEmptyOrBrokenIndexFails() {
        assertFails(concat(ftyp, mdat(), box("moov")), "moov")
        // The room MediaMuxer keeps for the index, still blank.
        assertFails(concat(ftyp, box("moov", ByteArray(200)), mdat()), "moov")
        // An index with no track, or no movie header, holds nothing a player can use.
        assertFails(concat(ftyp, mdat(), box("moov", box("mvhd", ByteArray(100)))), "trak")
        assertFails(concat(ftyp, mdat(), box("moov", box("trak", ByteArray(100)))), "mvhd")
        // A box inside the index that runs past its end.
        assertFails(concat(ftyp, mdat(), box("moov", box("mvhd", ByteArray(100)), header(500, "trak"))), "trak")
    }

    @Test
    fun REC1_anEmptyFileFails() {
        assertFails(ByteArray(0), "empty")
    }

    @Test
    fun REC1_aFileThatCantBeReadFails() {
        assertNotNull(Mp4Check.problemWith(File(temp.root, "missing.mp4")))
        assertNotNull(Mp4Check.problemWith(temp.newFolder()))
    }

    @Test
    fun REC1_strangeBoxTypesAreLoggedAsPlainText() {
        val problem = problem(concat(byteArrayOf(0, 0, 0, 8, 0, 10, 13, 27), moov))
        assertNotNull(problem)
        assertTrue("\"$problem\"", problem!!.all { it in ' '..'~' })
    }
}
