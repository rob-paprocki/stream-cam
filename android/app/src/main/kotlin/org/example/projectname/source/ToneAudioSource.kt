package org.example.projectname.source

import android.os.Process
import com.pedro.common.TimeUtils
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import org.example.projectname.log.AppLog

/**
 * A RootEncoder audio source that plays a continuous 1 kHz sine at -20 dBFS, 16-bit, at the sample rate and
 * channel count RootEncoder asks for (48 kHz stereo here).
 *
 * Each buffer is stamped with the time of its first sample, worked out from the number of samples sent
 * since the start, on the same clock RootEncoder uses. The thread sends buffers as real time catches up
 * with them, so the tone keeps pace with the video and never drifts.
 */
class ToneAudioSource : AudioSource() {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var generator: SineToneGenerator? = null

    override fun create(sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean): Boolean {
        generator = try {
            SineToneGenerator(sampleRate = sampleRate, channels = if (isStereo) 2 else 1)
        } catch (e: IllegalArgumentException) {
            AppLog.e(TAG, "The tone can't be made at $sampleRate Hz", e)
            return false
        }
        return true
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        this.getMicrophoneData = getMicrophoneData
        if (running) return
        val generator = generator ?: throw IllegalStateException("prepareAudio must be called before start")
        running = true
        thread = Thread({ play(generator, getMicrophoneData) }, "ToneAudio").apply { start() }
        AppLog.i(TAG, "Tone started: ${generator.frequencyHz} Hz, ${generator.sampleRate} Hz, ${generator.channels} channels")
    }

    override fun stop() {
        running = false
        getMicrophoneData = null
        val thread = thread ?: return
        this.thread = null
        thread.interrupt()
        thread.join(STOP_TIMEOUT_MS)
        if (thread.isAlive) AppLog.w(TAG, "The tone thread didn't stop within $STOP_TIMEOUT_MS ms")
    }

    override fun isRunning(): Boolean = running

    override fun release() = stop()

    private fun play(generator: SineToneGenerator, sink: GetMicrophoneData) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val sampleRate = generator.sampleRate
        val startMicros = TimeUtils.getCurrentTimeMicro()
        var framesSent = 0L
        try {
            while (running) {
                val buffer = ByteArray(CHUNK_FRAMES * generator.bytesPerFrame)
                generator.fill(buffer, CHUNK_FRAMES)
                // A new buffer each time: the encoder keeps a reference until it has copied the samples.
                sink.inputPCMData(Frame(buffer, 0, buffer.size, SineToneGenerator.timestampMicros(startMicros, framesSent, sampleRate)))
                framesSent += CHUNK_FRAMES
                val waitMicros = SineToneGenerator.timestampMicros(startMicros, framesSent, sampleRate) -
                    TimeUtils.getCurrentTimeMicro()
                if (waitMicros > 0) Thread.sleep(waitMicros / 1000, ((waitMicros % 1000) * 1000).toInt())
            }
        } catch (_: InterruptedException) {
            // stop() interrupts the sleep; nothing more to do.
        }
    }

    private companion object {
        const val TAG = "ToneAudio"

        /** One AAC frame's worth of samples, about 21 ms at 48 kHz. */
        const val CHUNK_FRAMES = 1024
        const val STOP_TIMEOUT_MS = 1000L
    }
}
