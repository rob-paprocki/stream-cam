package org.example.projectname.source

/** A filled rectangle in pixels. [right] and [bottom] are exclusive. [argb] is a packed colour. */
data class ColorBlock(val left: Int, val top: Int, val right: Int, val bottom: Int, val argb: Int)

/** A rectangle in pixels. [right] and [bottom] are exclusive. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Where everything in the test pattern goes for one frame size. It has no Android types, so the unit
 * tests can check it on any machine.
 *
 * The bars follow the SMPTE layout: seven 75% bars across the top two thirds, a strip of reversed blue
 * bars under them, and a bottom row with -I, 100% white, +Q, black and a near-black step for checking
 * black level. A black box in the upper middle holds a title, the timecode (the local time of day), a frame
 * counter and a track with a small block that moves every frame.
 */
class TestPatternLayout(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "the frame must be at least 1x1, got ${width}x$height" }
    }

    val bars: List<ColorBlock> = buildBars()

    val infoBox: Box = run {
        val boxWidth = (width * 0.70).toInt()
        val left = (width - boxWidth) / 2
        val top = (height * 0.10).toInt()
        Box(left, top, left + boxWidth, (height * 0.56).toInt())
    }

    val centerX: Float = width / 2f

    val titleTextSize: Float = height * 0.034f
    val titleBaseline: Float = infoBox.top + height * 0.065f

    val timecodeTextSize: Float = height * 0.14f
    val timecodeBaseline: Float = infoBox.top + height * 0.235f

    val counterTextSize: Float = height * 0.05f
    val counterBaseline: Float = infoBox.top + height * 0.33f

    /** The thin strip the moving block runs along. */
    val track: Box = run {
        val inset = (infoBox.width * 0.06).toInt()
        val top = infoBox.top + (height * 0.375).toInt()
        Box(infoBox.left + inset, top, infoBox.right - inset, top + blockSizeFor(height))
    }

    /** The moving block's side, in pixels. */
    val blockSize: Int = track.height

    /**
     * The moving block for frame [frameIndex] at [fps] frames per second. It sweeps along the track in one
     * second, back in the next, so it reaches an end on every whole second of the timecode.
     */
    fun movingBlock(frameIndex: Long, fps: Int): ColorBlock {
        require(fps > 0) { "fps must be at least 1, got $fps" }
        val travel = (track.width - blockSize).coerceAtLeast(0)
        val step = Math.floorMod(frameIndex, 2L * fps)
        val along = if (step <= fps) step else 2L * fps - step
        val left = track.left + (travel * along / fps).toInt()
        return ColorBlock(left, track.top, left + blockSize, track.top + blockSize, WHITE)
    }

    private fun buildBars(): List<ColorBlock> {
        val topEnd = height * 2 / 3
        val middleEnd = height * 3 / 4
        val out = ArrayList<ColorBlock>(TOP_ROW.size + MIDDLE_ROW.size + BOTTOM_ROW.size)
        addRow(out, TOP_ROW, 0, topEnd)
        addRow(out, MIDDLE_ROW, topEnd, middleEnd)
        addRow(out, BOTTOM_ROW, middleEnd, height)
        return out
    }

    /** Lays out one row. Edges are rounded from shared fractions, so the bars always meet with no gap. */
    private fun addRow(out: MutableList<ColorBlock>, row: List<Pair<Int, Int>>, top: Int, bottom: Int) {
        if (bottom <= top) return
        var units = 0
        for ((span, argb) in row) {
            val left = edge(units)
            units += span
            val right = edge(units)
            if (right > left) out += ColorBlock(left, top, right, bottom, argb)
        }
        check(units == ROW_UNITS) { "a bar row must span $ROW_UNITS units, not $units" }
    }

    private fun edge(units: Int): Int = ((width.toLong() * units + ROW_UNITS / 2) / ROW_UNITS).toInt()

    companion object {
        /** Every row is cut into this many units: 84 is the smallest number that sevenths, 28ths and 21sts divide. */
        const val ROW_UNITS = 84
        private const val BAR = ROW_UNITS / 7
        private const val WIDE = ROW_UNITS * 5 / 28
        private const val PLUGE = ROW_UNITS / 21

        fun rgb(red: Int, green: Int, blue: Int): Int =
            (0xFF shl 24) or ((red and 0xFF) shl 16) or ((green and 0xFF) shl 8) or (blue and 0xFF)

        val WHITE = rgb(255, 255, 255)
        val BLACK = rgb(0, 0, 0)
        val GRAY_75 = rgb(191, 191, 191)
        val YELLOW_75 = rgb(191, 191, 0)
        val CYAN_75 = rgb(0, 191, 191)
        val GREEN_75 = rgb(0, 191, 0)
        val MAGENTA_75 = rgb(191, 0, 191)
        val RED_75 = rgb(191, 0, 0)
        val BLUE_75 = rgb(0, 0, 191)
        val MINUS_I = rgb(0, 33, 76)
        val PLUS_Q = rgb(50, 0, 106)
        val NEAR_BLACK = rgb(10, 10, 10)
        val TRACK = rgb(64, 64, 64)

        /** The seven 75% bars, left to right. */
        val TOP_ROW: List<Pair<Int, Int>> = listOf(GRAY_75, YELLOW_75, CYAN_75, GREEN_75, MAGENTA_75, RED_75, BLUE_75)
            .map { BAR to it }

        val MIDDLE_ROW: List<Pair<Int, Int>> = listOf(BLUE_75, BLACK, MAGENTA_75, BLACK, CYAN_75, BLACK, GRAY_75)
            .map { BAR to it }

        val BOTTOM_ROW: List<Pair<Int, Int>> = listOf(
            WIDE to MINUS_I,
            WIDE to WHITE,
            WIDE to PLUS_Q,
            WIDE to BLACK,
            PLUGE to BLACK,
            PLUGE to NEAR_BLACK,
            PLUGE to BLACK,
            BAR to BLACK,
        )

        private fun blockSizeFor(height: Int): Int = (height * 0.045).toInt().coerceAtLeast(2)
    }
}

/** Timecode text and frame timing for the test pattern. */
object Timecode {
    /**
     * Formats [frameIndex], counted from local midnight, as `HH:MM:SS:FF`, a time of day. Hours wrap at
     * 24, so a pattern that runs past midnight carries on from 00:00:00:00.
     */
    fun format(frameIndex: Long, fps: Int): String {
        require(fps > 0) { "fps must be at least 1, got $fps" }
        require(frameIndex >= 0) { "frameIndex must not be negative, got $frameIndex" }
        val frames = frameIndex % fps
        val totalSeconds = frameIndex / fps
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = (totalSeconds / 3600) % 24
        return buildString(11) {
            twoDigits(hours)
            append(':')
            twoDigits(minutes)
            append(':')
            twoDigits(seconds)
            append(':')
            twoDigits(frames)
        }
    }

    /** The frame slot that [elapsedMillis] after the start of the count falls in. */
    fun frameAt(elapsedMillis: Long, fps: Int): Long = if (elapsedMillis <= 0) 0 else elapsedMillis * fps / 1000

    /** The first whole millisecond, counted from the start of the count, that falls inside frame slot [frameIndex]. */
    fun dueAt(frameIndex: Long, fps: Int): Long = (frameIndex * 1000 + fps - 1) / fps

    private fun StringBuilder.twoDigits(value: Long) {
        if (value < 10) append('0')
        append(value)
    }
}

/**
 * Ties the test pattern's frame slots to the local time of day, so the timecode on screen can be compared
 * with a clock, for example to measure latency.
 *
 * Both clocks are read once, at the start: [millisOfDayAtStart] is the local time of day, in milliseconds
 * since midnight, and [uptimeAtStart] is the monotonic clock at the same moment. After that only the
 * monotonic clock counts, so the timecode advances exactly one frame per frame slot and never jumps when
 * the phone's clock is set or adjusted, or at a daylight-saving change. The slots line up with the wall
 * clock's seconds as they were at the start, so frame 00 of each second begins on the second.
 */
class TimecodeClock(millisOfDayAtStart: Long, uptimeAtStart: Long, private val fps: Int) {
    init {
        require(fps > 0) { "fps must be at least 1, got $fps" }
        require(millisOfDayAtStart >= 0) { "millisOfDayAtStart must not be negative, got $millisOfDayAtStart" }
    }

    /** The monotonic clock's reading at the local midnight before the start. It can be negative. */
    private val midnightUptime = uptimeAtStart - millisOfDayAtStart

    /** The frame slot, counted from local midnight, that the monotonic clock reading [uptimeMillis] falls in. */
    fun frameAt(uptimeMillis: Long): Long = Timecode.frameAt(uptimeMillis - midnightUptime, fps)

    /** The monotonic clock reading at which frame slot [frame] begins. */
    fun dueAt(frame: Long): Long = midnightUptime + Timecode.dueAt(frame, fps)
}
