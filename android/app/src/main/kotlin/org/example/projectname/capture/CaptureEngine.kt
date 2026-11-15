package org.example.projectname.capture

import android.content.Context
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.pedro.common.ConnectChecker
import com.pedro.encoder.CodecErrorCallback
import com.pedro.encoder.utils.CodecUtil
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericStream
import com.pedro.library.view.RenderErrorCallback
import org.example.projectname.log.AppLog
import org.example.projectname.session.Recorder
import org.example.projectname.source.TestPatternVideoSource
import org.example.projectname.source.ToneAudioSource
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the RootEncoder stream: the test-pattern and tone sources, one H.264 and one AAC encoder, the
 * preview and the MP4 recorder. Nothing else in the app calls RootEncoder (spec structure rule 3).
 *
 * RootEncoder's stream object isn't thread-safe, so every call to it runs on one engine thread. Finished
 * recordings go to [store], which copies them to the shared Movies folder on its own thread, so a long copy
 * never holds up the preview or the next recording. [Events] are delivered on the main thread.
 */
class CaptureEngine(
    context: Context,
    private val config: CaptureConfig,
    private val store: RecordingStore,
    private val events: Events,
) : Recorder {

    /** What the engine reports. Every call arrives on the main thread. */
    interface Events {
        fun onEngineReady()
        fun onEngineFailed(reason: String)
        fun onRecordingStarted()
        fun onRecordingStopped(willSave: Boolean)
        fun onRecordingFailed(reason: String, willSave: Boolean)
        fun onRecordingSaved(where: String)
        fun onSaveFailed(reason: String)
    }

    private val appContext = context.applicationContext
    private val engineThread = HandlerThread("CaptureEngine").apply { start() }
    private val engine = Handler(engineThread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val renderErrors = AtomicInteger()

    // Touched on the engine thread only.
    private var stream: GenericStream? = null
    private var preview: PreviewTarget? = null
    private var recording: ActiveRecording? = null
    private var released = false

    private class PreviewTarget(val surface: Surface, var width: Int, var height: Int)

    /** [fileName] is the name it gets in Movies (spec REC-4); [file] is the working file, whose name is unique. */
    private class ActiveRecording(val fileName: String, val file: File, val startedAtMillis: Long) {
        @Volatile
        var firstFrameWritten = false
        val errorReported = AtomicBoolean(false)
    }

    /** Sets up the encoders, then starts the preview as soon as there is a surface for it. */
    fun start() {
        engine.post { prepare() }
    }

    /** The preview surface is ready, at [width] x [height] pixels. */
    fun attachPreview(surface: Surface, width: Int, height: Int) {
        engine.post {
            preview = PreviewTarget(surface, width, height)
            startPreviewIfPossible()
        }
    }

    fun resizePreview(width: Int, height: Int) {
        engine.post {
            preview?.let {
                it.width = width
                it.height = height
            }
            stream?.takeIf { it.isOnPreview }?.getGlInterface()?.setPreviewResolution(width, height)
        }
    }

    /**
     * The preview surface is going away. This waits (briefly) until the engine has stopped drawing to it,
     * because the surface is gone once the caller returns. A recording carries on without the preview.
     */
    fun detachPreview() {
        val done = CountDownLatch(1)
        val posted = engine.postAtFrontOfQueue {
            try {
                preview = null
                stream?.takeIf { it.isOnPreview }?.let {
                    it.stopPreview()
                    AppLog.i(TAG, "Preview stopped")
                }
            } catch (e: RuntimeException) {
                AppLog.e(TAG, "Stopping the preview failed", e)
            } finally {
                done.countDown()
            }
        }
        if (posted && !done.await(DETACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            AppLog.w(TAG, "The preview didn't stop within $DETACH_TIMEOUT_MS ms")
        }
    }

    override fun startRecording(fileName: String) {
        engine.post { startRecordingNow(fileName) }
    }

    override fun stopRecording() {
        engine.post { stopRecordingNow() }
    }

    /** Stops and saves any recording, then frees the encoders. The engine can't be used afterwards. */
    fun release() {
        engine.post { releaseNow() }
    }

    private fun prepare() {
        if (stream != null || released) return
        val newStream = try {
            GenericStream(appContext, connectChecker, TestPatternVideoSource(), ToneAudioSource())
        } catch (e: RuntimeException) {
            failEngine("The video engine couldn't start. Export the log for details.", e)
            return
        }
        newStream.setEncoderErrorCallback(encoderErrors)
        newStream.getGlInterface().setRenderErrorCallback(renderErrorCallback)
        if (!prepareEncoders(newStream)) {
            runCatching { newStream.release() }
            return
        }
        stream = newStream
        AppLog.i(
            TAG,
            "Encoders ready: H.264 ${config.width}x${config.height} at ${config.fps} fps, " +
                "${config.videoBitrateBps / 1000} kbps, keyframe every ${config.keyframeIntervalSeconds} s; " +
                "AAC ${config.sampleRateHz} Hz ${if (config.stereo) "stereo" else "mono"}, ${config.audioBitrateBps / 1000} kbps",
        )
        main.post { events.onEngineReady() }
        startPreviewIfPossible()
    }

    /** Sets up both encoders on [target]. Reports a failure to the user and returns false if they refuse. */
    private fun prepareEncoders(target: GenericStream): Boolean {
        val prepared = try {
            target.prepareVideo(
                width = config.width,
                height = config.height,
                bitrate = config.videoBitrateBps,
                fps = config.fps,
                iFrameInterval = config.keyframeIntervalSeconds,
                rotation = 0,
            ) && target.prepareAudio(config.sampleRateHz, config.stereo, config.audioBitrateBps)
        } catch (e: RuntimeException) {
            AppLog.e(TAG, "Preparing the encoders threw", e)
            false
        }
        if (!prepared) {
            failEngine(
                "This phone's encoders didn't accept H.264 at ${config.width}x${config.height}, ${config.fps} fps " +
                    "with AAC audio. Export the log for details.",
                null,
            )
        }
        return prepared
    }

    private fun startPreviewIfPossible() {
        val stream = stream ?: return
        val target = preview ?: return
        if (!target.surface.isValid) {
            AppLog.w(TAG, "The preview surface isn't valid; preview not started")
            return
        }
        try {
            if (stream.isOnPreview) stream.stopPreview()
            stream.startPreview(target.surface, target.width, target.height)
            AppLog.i(TAG, "Preview started on a ${target.width}x${target.height} surface")
        } catch (e: RuntimeException) {
            AppLog.e(TAG, "Starting the preview failed", e)
        }
    }

    private fun startRecordingNow(fileName: String) {
        val stream = stream
        if (stream == null) {
            main.post { events.onRecordingFailed("The video engine isn't ready yet.", willSave = false) }
            return
        }
        if (recording != null || stream.isRecording) {
            AppLog.w(TAG, "Asked to start $fileName while a recording is running")
            main.post { events.onRecordingFailed("A recording is already running.", willSave = false) }
            return
        }
        val startedAt = System.currentTimeMillis()
        val file = try {
            store.newRecordingFile(fileName, startedAt)
        } catch (e: IOException) {
            AppLog.e(TAG, "No folder to record into", e)
            main.post { events.onRecordingFailed(e.message ?: "There's nowhere to save the recording.", willSave = false) }
            return
        }
        val active = ActiveRecording(fileName, file, startedAt)
        try {
            stream.startRecord(file.absolutePath, listener = recordListener(active))
            recording = active
            AppLog.i(TAG, "Recording started: $fileName, in ${file.name}")
        } catch (e: Exception) {
            AppLog.e(TAG, "Starting the recording $fileName failed", e)
            store.discard(file)
            main.post {
                events.onRecordingFailed("The recording couldn't start. Export the log for details.", willSave = false)
            }
        }
    }

    private fun stopRecordingNow() {
        val active = recording
        if (active == null) {
            // It already ended, for example after a write error. Tell the session so it leaves "stopping".
            main.post { events.onRecordingStopped(willSave = false) }
            return
        }
        finishRecording(active, writeFailed = false)
    }

    /**
     * Ends [active] and reports the result. [writeFailed] is true when it ended because writing the file
     * failed.
     *
     * A recording with at least one frame goes to the store, which copies it to Movies only if nothing went
     * wrong and the file passes its MP4 check. Otherwise the store deletes the file, at once after a write
     * error and at the next start of the app otherwise, and the user is told the recording couldn't be saved.
     */
    private fun finishRecording(active: ActiveRecording, writeFailed: Boolean) {
        if (recording !== active) return
        recording = null
        val stopProblem = stopRecorder()
        val bytes = active.file.length()
        val recorded = active.firstFrameWritten && bytes > 0
        if (writeFailed) {
            AppLog.w(TAG, "Recording ended early: ${active.fileName}, ${byteCount(bytes)}: writing it failed")
        } else {
            AppLog.i(TAG, "Recording stopped: ${active.fileName}, ${byteCount(bytes)}")
        }
        val problem = when {
            writeFailed -> RecordingStore.Problem.WriteFailed
            stopProblem != null -> RecordingStore.Problem.Other(stopProblem)
            else -> null
        }
        // Checked and marked as finished before the copy is queued, so that if the app is stopped during the
        // copy, the next start knows the file is whole and saves it.
        val toSave = if (recorded) store.markFinished(active.file, problem) else null
        if (!recorded) store.discard(active.file)
        val reason = when {
            // If the store couldn't delete the file after the write error, the next start does, so say so.
            writeFailed && active.file.exists() -> WRITE_FAILED_REMOVED_LATER
            writeFailed -> WRITE_FAILED
            recorded && toSave == null -> NOT_FINISHED
            else -> null
        }
        val willSave = toSave != null
        main.post {
            if (reason == null) events.onRecordingStopped(willSave) else events.onRecordingFailed(reason, willSave)
        }
        // Queued after the report above, so the session hears that the recording stopped before it hears
        // that the copy is done.
        toSave?.let { file -> store.saveLater(file, active.startedAtMillis) { result -> reportSave(active, file, result) } }
    }

    /**
     * Stops RootEncoder's recorder, which closes the MP4, and returns what went wrong, in words for the log,
     * or null. RootEncoder catches an error from `MediaMuxer.stop()` itself, so null doesn't prove the file
     * is whole; the store checks that.
     */
    private fun stopRecorder(): String? {
        val stream = stream ?: return "the recorder was never stopped"
        val encodersReady = try {
            stream.stopRecord()
        } catch (e: Exception) {
            AppLog.e(TAG, "Stopping the recording threw", e)
            return "stopping it threw ${e.javaClass.simpleName}"
        }
        // stopRecord closes the file first, then sets the encoders up again, and returns false if that failed.
        if (!encodersReady) {
            AppLog.w(TAG, "The encoders didn't reset after recording; preparing them again")
            try {
                prepareAgain(stream)
            } catch (e: RuntimeException) {
                AppLog.e(TAG, "Preparing the encoders again threw", e)
            }
        }
        return null
    }

    /** Runs on the store's thread, once the copy of [file] to Movies has finished or failed. */
    private fun reportSave(active: ActiveRecording, file: File, result: Result<String>) {
        result.fold(
            onSuccess = { where ->
                AppLog.i(TAG, "Saved ${active.fileName} to $where")
                main.post { events.onRecordingSaved(where) }
            },
            onFailure = { error ->
                val retried = store.retriesAtNextStart(file)
                val next = if (retried) "is tried again" else "is deleted"
                AppLog.e(
                    TAG,
                    "Couldn't copy ${active.fileName} to the shared Movies folder; ${file.name} $next at the next start of the app",
                    error,
                )
                main.post { events.onSaveFailed(if (retried) SAVE_FAILED_TRIED_AGAIN else SAVE_FAILED) }
            },
        )
    }

    /** RootEncoder can't be set up again while the preview runs, so this stops and restarts the preview. */
    private fun prepareAgain(target: GenericStream) {
        if (target.isOnPreview) target.stopPreview()
        if (prepareEncoders(target)) startPreviewIfPossible()
    }

    private fun releaseNow() {
        if (released) return
        recording?.let { finishRecording(it, writeFailed = false) }
        released = true
        try {
            stream?.release()
        } catch (e: RuntimeException) {
            AppLog.e(TAG, "Releasing the encoders threw", e)
        }
        stream = null
        preview = null
        // A copy the store has queued still finishes, and still reports back.
        engineThread.quitSafely()
        AppLog.i(TAG, "Engine released")
    }

    private fun failEngine(reason: String, error: Throwable?) {
        AppLog.e(TAG, reason, error)
        main.post { events.onEngineFailed(reason) }
    }

    private fun recordListener(active: ActiveRecording) = object : RecordController.Listener {
        override fun onStatusChange(status: RecordController.Status) {
            if (status == RecordController.Status.RECORDING && !active.firstFrameWritten) {
                active.firstFrameWritten = true
                AppLog.i(TAG, "First frame written to ${active.fileName}")
                main.post { events.onRecordingStarted() }
            }
        }

        override fun onError(e: Exception?) {
            if (!active.errorReported.compareAndSet(false, true)) return
            AppLog.e(TAG, "Writing ${active.fileName} failed", e)
            engine.post {
                finishRecording(active, writeFailed = true)
            }
        }
    }

    private val encoderErrors = object : CodecErrorCallback {
        override fun onCodecError(type: CodecUtil.CodecTypeError, e: MediaCodec.CodecException) {
            AppLog.e(TAG, "$type codec error (transient ${e.isTransient}, recoverable ${e.isRecoverable})", e)
        }

        override fun onEncodeError(type: CodecUtil.CodecTypeError, e: IllegalStateException): Boolean {
            AppLog.e(TAG, "$type encode error; RootEncoder resets the encoder", e)
            return true
        }
    }

    private val renderErrorCallback = object : RenderErrorCallback {
        override fun onRenderError(error: RuntimeException) {
            val count = renderErrors.incrementAndGet()
            if (count <= 3 || count % RENDER_ERROR_LOG_EVERY == 0) AppLog.e(TAG, "Render error $count", error)
        }
    }

    /** Streaming arrives in milestone M2. Until then RootEncoder never connects anywhere. */
    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) = AppLog.i(TAG, "Connection started")
        override fun onConnectionSuccess() = AppLog.i(TAG, "Connected")
        override fun onConnectionFailed(reason: String) = AppLog.w(TAG, "Connection failed: $reason")
        override fun onDisconnect() = AppLog.i(TAG, "Disconnected")
        override fun onAuthError() = AppLog.w(TAG, "Authentication failed")
        override fun onAuthSuccess() = AppLog.i(TAG, "Authenticated")
    }

    private companion object {
        const val TAG = "CaptureEngine"
        const val DETACH_TIMEOUT_MS = 2000L
        const val RENDER_ERROR_LOG_EVERY = 300

        // A recording that went wrong is never saved, and its unfinished file is deleted (see RecordingStore),
        // so these messages say so. The app's own folder can't be opened from the Files app, so they don't
        // send the user there.
        const val WRITE_FAILED =
            "Writing the recording failed, so it stopped and couldn't be saved. The unfinished file has been " +
                "removed. Export the log for details."
        const val WRITE_FAILED_REMOVED_LATER =
            "Writing the recording failed, so it stopped and couldn't be saved. The unfinished file is removed " +
                "the next time the app starts. Export the log for details."
        const val NOT_FINISHED =
            "The recording couldn't be finished properly, so it couldn't be saved. The unfinished file is " +
                "removed the next time the app starts. Export the log for details."

        // The store tries a failed copy again only when the app's process next starts (App.onCreate), and only
        // for a file it marked finished; reopening the screen isn't enough. A file it couldn't mark finished
        // is deleted at that start instead.
        const val SAVE_FAILED_TRIED_AGAIN =
            "The recording couldn't be copied to Movies. The app keeps it and tries again the next time the app " +
                "starts from scratch, for example after the phone restarts."
        const val SAVE_FAILED = "The recording couldn't be copied to Movies, so it wasn't saved. Export the log for details."
    }
}
