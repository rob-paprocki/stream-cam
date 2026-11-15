package org.example.projectname.capture

import java.nio.ByteBuffer

/** Builds MP4 files byte by byte for the tests: boxes are a big-endian size, a four-letter type and a body. */
object Mp4Bytes {
    fun box(type: String, vararg body: ByteArray): ByteArray {
        val content = concat(*body)
        return ByteBuffer.allocate(8 + content.size).putInt(8 + content.size).put(fourcc(type)).put(content).array()
    }

    /** A box with a 64-bit size, as MediaMuxer writes `mdat` in a large file. */
    fun largeBox(type: String, vararg body: ByteArray): ByteArray {
        val content = concat(*body)
        return ByteBuffer.allocate(16 + content.size).putInt(1).put(fourcc(type)).putLong(16L + content.size).put(content).array()
    }

    /** A box header with any size, whatever follows it. */
    fun header(size: Int, type: String): ByteArray = ByteBuffer.allocate(8).putInt(size).put(fourcc(type)).array()

    fun header64(size: Long, type: String): ByteArray = ByteBuffer.allocate(16).putInt(1).put(fourcc(type)).putLong(size).array()

    val ftyp: ByteArray = box("ftyp", fourcc("isom"), ByteArray(4), fourcc("isom"), fourcc("iso2"), fourcc("avc1"), fourcc("mp41"))

    /** A movie box as a player needs it: a movie header and one track. */
    val moov: ByteArray = box("moov", box("mvhd", ByteArray(100)), box("trak", box("tkhd", ByteArray(84)), box("mdia", ByteArray(40))))

    fun mdat(size: Int = 1000): ByteArray = box("mdat", ByteArray(size) { (it * 31).toByte() })

    /** The smallest whole recording, laid out as MediaMuxer does: `ftyp`, room for the index, the media, then `moov`. */
    fun whole(mediaBytes: Int = 1000): ByteArray = concat(ftyp, box("free", ByteArray(16)), mdat(mediaBytes), moov)

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    private fun fourcc(type: String): ByteArray = type.toByteArray(Charsets.US_ASCII).also { require(it.size == 4) }
}
