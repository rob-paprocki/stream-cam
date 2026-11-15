package org.example.projectname.capture

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Checks that a recording's file is a whole MP4 before the app publishes it. RootEncoder ignores an
 * error from `MediaMuxer.stop()`, so a file whose index was never written gives no other sign.
 *
 * An MP4 is a run of boxes (ISO/IEC 14496-12, section 4.2). Each box starts with a 32-bit big-endian size
 * that counts the whole box, then a four-letter type. A size of 1 means a 64-bit size follows the type. A
 * file passes when:
 * - the boxes fill the file exactly, and each one is at least as long as its own header;
 * - the first box is `ftyp`;
 * - there is a `moov` box, the index a player needs, whose own boxes fill it exactly and include a movie
 *   header (`mvhd`) and at least one track (`trak`).
 *
 * The format lets a last box give its size as 0, meaning "to the end of the file". MediaMuxer never does
 * that in a finished file, but it is what a writer leaves in a box it started and never closed, so a size
 * of 0 fails. So does the `????` that MediaMuxer writes as the media box's size until the recording stops.
 *
 * It reads only the box headers, so it takes a few milliseconds however long the recording is. It has no
 * Android types, so the unit tests can check it on any machine.
 */
object Mp4Check {

    /** Returns null if [file] passes, or why it doesn't, in words for the debug log. */
    fun problemWith(file: File): String? = try {
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            if (length == 0L) throw NotWhole("the file is empty")
            val top = boxesIn(input, 0, length, inside = null)
            if (top.first().type != "ftyp") throw NotWhole("it starts with a '${top.first().type}' box, not 'ftyp'")
            val moov = top.firstOrNull { it.type == "moov" }
                ?: throw NotWhole("it has no 'moov' box, the index a player needs")
            if (moov.bodyStart == moov.end) throw NotWhole("its 'moov' box is empty")
            val index = boxesIn(input, moov.bodyStart, moov.end, inside = "moov")
            for (needed in listOf("mvhd", "trak")) {
                if (index.none { it.type == needed }) throw NotWhole("its 'moov' box has no '$needed' box")
            }
            null
        }
    } catch (e: NotWhole) {
        e.message
    } catch (e: IOException) {
        "it couldn't be read (${e.javaClass.simpleName}: ${e.message})"
    }

    private class Box(val type: String, val bodyStart: Long, val end: Long)

    private class NotWhole(reason: String) : Exception(reason)

    /** Reads the boxes that fill the bytes from [start] to [end] exactly. [inside] names the box they're in. */
    private fun boxesIn(input: RandomAccessFile, start: Long, end: Long, inside: String?): List<Box> {
        val boxes = ArrayList<Box>()
        val header = ByteArray(HEADER)
        val where = if (inside == null) "" else " in the '$inside' box"
        var offset = start
        while (offset < end) {
            val left = end - offset
            if (left < HEADER) throw NotWhole("it ends$where with ${byteCount(left)}, too few for a box header")
            input.seek(offset)
            input.readFully(header)
            val type = fourcc(header)
            val box = "the '$type' box at byte $offset$where"
            var size = uint32(header)
            var headerSize = HEADER.toLong()
            if (size == 1L) {
                if (left < LARGE_HEADER) throw NotWhole("$box ends part-way through its 64-bit size")
                size = input.readLong()
                headerSize = LARGE_HEADER.toLong()
                if (size < 0) throw NotWhole("$box gives a size too large to be real")
            }
            when {
                size == 0L -> throw NotWhole("$box was never finished: its size is 0")
                size < headerSize -> throw NotWhole("$box gives its size as ${byteCount(size)}, less than its own header")
                size > left -> throw NotWhole("$box gives its size as $size bytes, but only $left bytes are left")
            }
            boxes += Box(type, offset + headerSize, offset + size)
            offset += size
        }
        return boxes
    }

    private fun uint32(bytes: ByteArray): Long =
        (0 until 4).fold(0L) { value, i -> (value shl 8) or (bytes[i].toLong() and 0xFF) }

    /** The box type in [header], with anything that isn't printable ASCII shown as `?`, so the log stays readable. */
    private fun fourcc(header: ByteArray): String =
        String(CharArray(4) { i -> (header[4 + i].toInt() and 0xFF).let { if (it in 0x20..0x7E) it.toChar() else '?' } })

    private const val HEADER = 8
    private const val LARGE_HEADER = 16
}
