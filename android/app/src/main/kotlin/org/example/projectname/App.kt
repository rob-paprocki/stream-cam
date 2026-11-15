package org.example.projectname

import android.app.Application
import android.os.Environment
import org.example.projectname.capture.CaptureConfig
import org.example.projectname.capture.MediaStoreMovies
import org.example.projectname.capture.RecordingStore
import org.example.projectname.log.AppLog
import org.example.projectname.log.CrashLog
import org.example.projectname.log.DeviceInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class App : Application() {
    /**
     * The one recording store for the whole app. Every capture engine hands its finished recordings to it,
     * and its single thread copies them to Movies one at a time, after the check for recordings left from
     * before that [onCreate] queues first.
     */
    val recordings: RecordingStore by lazy {
        RecordingStore(
            workingFolder = ::recordingFolder,
            movies = MediaStoreMovies(this),
            worker = Executors.newSingleThreadExecutor { Thread(it, "RecordingSaver") },
            log = { message, error -> if (error == null) AppLog.i(STORE_TAG, message) else AppLog.w(STORE_TAG, message, error) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        AppLog.i(TAG, "App started")
        recordings.recoverLeftoversLater()
        val context = this
        thread(name = "StartupInfo") {
            DeviceInfo.logOnce(context, CaptureConfig.forTestPattern())
        }
    }

    /** The app-specific folder recordings are written to first. It needs no permission. */
    private fun recordingFolder(): File {
        val folder = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: throw IOException("The phone's shared storage isn't available right now.")
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("The app's recording folder couldn't be created.")
        return folder
    }

    private companion object {
        const val TAG = "App"
        const val STORE_TAG = "RecordingStore"
    }
}
