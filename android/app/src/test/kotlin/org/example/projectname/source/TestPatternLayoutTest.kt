package org.example.projectname.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TestPatternLayoutTest {
    private val hd = TestPatternLayout(1920, 1080)

    private fun rowAt(layout: TestPatternLayout, top: Int) = layout.bars.filter { it.top == top }.sortedBy { it.left }

    @Test
    fun barsTileTheWholeFrameWithNoGapOrOverlap() {
        for ((width, height) in listOf(1920 to 1080, 1280 to 720, 641 to 359, 7 to 4)) {
            val layout = TestPatternLayout(width, height)
            val covered = Array(height) { IntArray(width) }
            for (bar in layout.bars) {
                for (y in bar.top until bar.bottom) for (x in bar.left until bar.right) covered[y][x]++
            }
            for (y in 0 until height) for (x in 0 until width) {
                assertEquals("pixel $x,$y in ${width}x$height", 1, covered[y][x])
            }
        }
    }

    @Test
    fun topRowHasTheSevenSeventyFivePercentBarsInSmpteOrder() {
        val top = rowAt(hd, 0)
        assertEquals(
            listOf(
                TestPatternLayout.GRAY_75, TestPatternLayout.YELLOW_75, TestPatternLayout.CYAN_75, TestPatternLayout.GREEN_75,
                TestPatternLayout.MAGENTA_75, TestPatternLayout.RED_75, TestPatternLayout.BLUE_75,
            ),
            top.map { it.argb },
        )
        assertEquals(720, top.first().bottom)
        assertTrue("bars are within a pixel of a seventh", top.all { kotlin.math.abs(it.right - it.left - 1920 / 7) <= 1 })
    }

    @Test
    fun bottomRowStartsWithMinusIWhiteAndPlusQ() {
        val bottom = rowAt(hd, 810)
        assertEquals(TestPatternLayout.MINUS_I, bottom[0].argb)
        assertEquals(TestPatternLayout.WHITE, bottom[1].argb)
        assertEquals(TestPatternLayout.PLUS_Q, bottom[2].argb)
        assertEquals(1080, bottom.last().bottom)
        assertTrue(bottom.any { it.argb == TestPatternLayout.NEAR_BLACK })
    }

    @Test
    fun rgbPacksAnOpaqueColour() {
        assertEquals(0xFFBFBF00.toInt(), TestPatternLayout.rgb(191, 191, 0))
        assertEquals(0xFF000000.toInt(), TestPatternLayout.BLACK)
    }

    @Test
    fun infoBoxAndTrackSitInsideTheTopBars() {
        val box = hd.infoBox
        assertTrue(box.left > 0 && box.right < 1920 && box.top > 0 && box.bottom < 720)
        assertEquals(1920 - box.right, box.left)
        val track = hd.track
        assertTrue(track.left >= box.left && track.right <= box.right && track.top > box.top && track.bottom < box.bottom)
        assertTrue("the timecode sits below the title", hd.timecodeBaseline > hd.titleBaseline + hd.timecodeTextSize * 0.7f)
        assertTrue("the counter sits below the timecode", hd.counterBaseline > hd.timecodeBaseline + hd.counterTextSize)
    }

    @Test
    fun movingBlockMovesEveryFrameAndStaysOnTheTrack() {
        val fps = 30
        val positions = (0L until 4L * fps).map { hd.movingBlock(it, fps) }
        for (block in positions) {
            assertTrue(block.left >= hd.track.left && block.right <= hd.track.right)
            assertEquals(hd.blockSize, block.right - block.left)
            assertEquals(hd.blockSize, block.bottom - block.top)
        }
        for (i in 1 until positions.size) {
            assertNotEquals("frame $i moved", positions[i - 1].left, positions[i].left)
        }
    }

    @Test
    fun movingBlockReachesAnEndOnEveryWholeSecond() {
        val fps = 30
        assertEquals(hd.track.left, hd.movingBlock(0, fps).left)
        assertEquals(hd.track.right, hd.movingBlock(30, fps).right)
        assertEquals(hd.track.left, hd.movingBlock(60, fps).left)
        assertEquals(hd.track.right, hd.movingBlock(90, fps).right)
    }
}
