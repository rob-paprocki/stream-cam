package org.example.projectname.session

import android.app.Application
import android.os.SystemClock
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.example.projectname.App
import org.example.projectname.capture.CaptureConfig
import org.example.projectname.capture.CaptureEngine
import org.example.projectname.core.recording.APP_FILE_PREFIX
import org.example.projectname.log.AppLog
import org.example.projectname.log.CrashLog
import org.example.projectname.log.DebugReport
import org.example.projectname.log.DeviceInfo
import org.example.projectname.log.LogExport
import org.example.projectname.ui.PreviewHost
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Holds the capture engine and the session for as long as the main screen exists, including across
 * configuration changes. Everything here runs on the main thread.
 */
class MainViewModel(application: Application) : AndroidViewModel(application), PreviewHost {

    val config: CaptureConfig = CaptureConfig.forTestPattern()

    private val _state = MutableStateFlow(ScreenState())
    val state: StateFlow<ScreenState> = _state.asStateFlow()

    private val session: MainSession

    private val engine = CaptureEngine(
        application,
        config,
        (application as App).recordings,
        object : CaptureEngine.Events {
            override fun onEngineReady() = session.onEngineReady()
            override fun onEngineFailed(reason: String) = session.onEngineFailed(reason)
            override fun onRecordingStarted() = session.onRecordingStarted()
            override fun onRecordingStopped(willSave: Boolean) = session.onRecordingStopped(willSave)
            override fun onRecordingFailed(reason: String, willSave: Boolean) = session.onRecordingFailed(reason, willSave)
            override fun onRecordingSaved(where: String) = session.onRecordingSaved(where)
            override fun onSaveFailed(reason: String) = session.onSaveFailed(reason)
        },
    )

    init {
        session = MainSession(
            recorder = engine,
            localNow = LocalDateTime::now,
            monotonicMillis = SystemClock::elapsedRealtime,
            log = { AppLog.i(TAG, it) },
        )
        session.onChange = { _state.value = it }
        _state.value = session.state
        engine.start()
    }

    fun onRecordPressed() = session.onRecordPressed()

    /** The screen is no longer visible (and isn't just being rebuilt for a configuration change). */
    fun onAppHidden() = session.onAppHidden()

    fun onBannerDismissed() = session.onBannerDismissed()

    fun onNoticeShown() = session.onNoticeShown()

    override fun onPreviewSurfaceReady(surface: Surface, width: Int, height: Int) =
        engine.attachPreview(surface, width, height)

    override fun onPreviewSurfaceResized(width: Int, height: Int) = engine.resizePreview(width, height)

    override fun onPreviewSurfaceGone() = engine.detachPreview()

    /**
     * Writes the file "Export log" shares, off the main thread, and returns it: phone and app facts, then
     * the redacted log (spec OUT-6).
     *
     * @throws IOException if the file can't be written.
     */
    suspend fun writeDebugReport(): File = withContext(Dispatchers.IO) {
        AppLog.i(TAG, "Log exported")
        val now = Instant.now()
        val report = DebugReport.compose(
            title = "$APP_FILE_PREFIX debug log, exported ${now.truncatedTo(ChronoUnit.SECONDS)}",
            header = DeviceInfo.header(getApplication(), config),
            log = AppLog.buffer.dump(),
            previousCrash = CrashLog.previousCrash,
        )
        LogExport.write(LogExport.folderIn(getApplication<Application>().cacheDir), report, now)
    }

    override fun onCleared() {
        AppLog.i(TAG, "Main screen closed")
        engine.release()
    }

    private companion object {
        const val TAG = "Session"
    }
}
