package org.example.projectname.source

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource
import org.example.projectname.core.recording.APP_FILE_PREFIX
import org.example.projectname.log.AppLog
import java.time.LocalTime

/**
 * A RootEncoder video source that draws a test pattern: SMPTE colour bars, a large timecode showing the
 * local time of day (HH:MM:SS:FF), a counter of frames drawn, and a small block that moves every frame. It
 * draws at the size and frame rate RootEncoder asks for (1920x1080 at 30 fps here) onto the SurfaceTexture
 * it is given, from its own thread.
 *
 * The timecode advances one frame per frame slot of a [TimecodeClock], so it can be held up against a clock
 * to measure latency. It follows the clock, not the number of frames drawn: if a frame is late, the
 * timecode skips ahead and the frame counter falls behind it, so a dropped frame shows in the recording.
 */
class TestPatternVideoSource : VideoSource() {

    @Volatile
    private var running = false
    private var thread: HandlerThread? = null
    private var loop: RenderLoop? = null

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean =
        width > 0 && height > 0 && fps > 0

    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        if (running) return
        surfaceTexture.setDefaultBufferSize(width, height)
        val surface = Surface(surfaceTexture)
        val thread = HandlerThread("TestPattern", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        val loop = RenderLoop(Handler(thread.looper), surface, TestPatternPainter(TestPatternLayout(width, height), fps), fps)
        this.thread = thread
        this.loop = loop
        running = true
        loop.begin()
        AppLog.i(TAG, "Test pattern started: ${width}x$height at $fps fps")
    }

    override fun stop() {
        val thread = thread ?: return
        val loop = loop
        running = false
        this.thread = null
        this.loop = null
        loop?.end()
        thread.quitSafely()
        thread.join(STOP_TIMEOUT_MS)
        if (thread.isAlive) {
            AppLog.w(TAG, "The test-pattern thread didn't stop within $STOP_TIMEOUT_MS ms")
        } else {
            AppLog.i(TAG, "Test pattern stopped after ${loop?.framesDrawn ?: 0} frames")
        }
    }

    override fun release() = stop()

    override fun isRunning(): Boolean = running

    /** The pattern is a landscape picture whatever way the phone is held. */
    override fun getOrientationConfig() = OrientationConfig(forced = OrientationForced.LANDSCAPE)

    /** Draws one frame per frame slot, on the handler's thread, until [end]. */
    private class RenderLoop(
        private val handler: Handler,
        private val surface: Surface,
        private val painter: TestPatternPainter,
        private val fps: Int,
    ) : Runnable {
        @Volatile
        private var active = false
        private var released = false
        private lateinit var clock: TimecodeClock
        private var useHardwareCanvas = true
        private var failures = 0

        @Volatile
        var framesDrawn = 0L
            private set

        fun begin() {
            active = true
            handler.post {
                // Read the two clocks back to back, so the time of day and the frame slots line up.
                val timeOfDay = LocalTime.now()
                clock = TimecodeClock(timeOfDay.toNanoOfDay() / 1_000_000, SystemClock.uptimeMillis(), fps)
                run()
            }
        }

        /** Stops drawing, and releases the surface on the drawing thread so it is never freed mid-frame. */
        fun end() {
            active = false
            handler.removeCallbacksAndMessages(null)
            handler.post {
                released = true
                surface.release()
            }
        }

        override fun run() {
            if (!active || released) return
            val frame = clock.frameAt(SystemClock.uptimeMillis())
            drawFrame(frame)
            if (active) handler.postAtTime(this, clock.dueAt(frame + 1))
        }

        private fun drawFrame(frame: Long) {
            val canvas: Canvas = try {
                if (useHardwareCanvas) surface.lockHardwareCanvas() else surface.lockCanvas(null)
            } catch (e: RuntimeException) {
                if (useHardwareCanvas) {
                    useHardwareCanvas = false
                    AppLog.w(TAG, "No hardware canvas for the test pattern; drawing in software instead", e)
                } else {
                    reportFailure("Couldn't lock the test-pattern surface", e)
                }
                return
            }
            try {
                painter.draw(canvas, frame, framesDrawn)
                framesDrawn++
            } finally {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (e: RuntimeException) {
                    reportFailure("Couldn't post a test-pattern frame", e)
                }
            }
        }

        /** Logs the first few failures and then one in every [FAILURE_LOG_EVERY], so the log can't fill up. */
        private fun reportFailure(message: String, error: Throwable) {
            failures++
            if (failures <= 3 || failures % FAILURE_LOG_EVERY == 0) AppLog.w(TAG, "$message (failure $failures)", error)
        }
    }

    private companion object {
        const val TAG = "TestPattern"
        const val STOP_TIMEOUT_MS = 1000L
        const val FAILURE_LOG_EVERY = 300
    }
}

/** Draws the test pattern laid out by [layout] onto a canvas. Every paint is made once, up front. */
internal class TestPatternPainter(private val layout: TestPatternLayout, private val fps: Int) {
    private val fill = Paint().apply { style = Paint.Style.FILL }

    private val titlePaint = textPaint(layout.titleTextSize, Typeface.DEFAULT_BOLD, GRAY)
    private val timecodePaint = textPaint(layout.timecodeTextSize, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD), TestPatternLayout.WHITE)
    private val counterPaint = textPaint(layout.counterTextSize, Typeface.MONOSPACE, GRAY)

    private val title = "$APP_FILE_PREFIX test pattern  ${layout.width}x${layout.height}  $fps fps  1 kHz tone"

    fun draw(canvas: Canvas, frameIndex: Long, framesDrawn: Long) {
        for (bar in layout.bars) fillBlock(canvas, bar.left, bar.top, bar.right, bar.bottom, bar.argb)

        val box = layout.infoBox
        fillBlock(canvas, box.left, box.top, box.right, box.bottom, TestPatternLayout.BLACK)
        canvas.drawText(title, layout.centerX, layout.titleBaseline, titlePaint)
        canvas.drawText(Timecode.format(frameIndex, fps), layout.centerX, layout.timecodeBaseline, timecodePaint)
        canvas.drawText("Frame $framesDrawn", layout.centerX, layout.counterBaseline, counterPaint)

        val track = layout.track
        fillBlock(canvas, track.left, track.top, track.right, track.bottom, TestPatternLayout.TRACK)
        val block = layout.movingBlock(frameIndex, fps)
        fillBlock(canvas, block.left, block.top, block.right, block.bottom, block.argb)
    }

    private fun fillBlock(canvas: Canvas, left: Int, top: Int, right: Int, bottom: Int, argb: Int) {
        fill.color = argb
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), fill)
    }

    private fun textPaint(size: Float, face: Typeface, argb: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb
        textSize = size
        typeface = face
        textAlign = Paint.Align.CENTER
    }

    private companion object {
        val GRAY = TestPatternLayout.rgb(200, 200, 200)
    }
}
