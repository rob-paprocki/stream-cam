package org.example.projectname.log

import android.content.Context
import java.io.File

/**
 * Keeps the debug log across a crash. When the app crashes, the log (already redacted) is written to a
 * file in the app's private storage. The next start reads it back once, so the next "Export log" includes
 * it, and deletes the file.
 */
object CrashLog {
    private const val TAG = "CrashLog"
    private const val FILE_NAME = "last-crash-log.txt"

    @Volatile
    var previousCrash: String? = null
        private set

    fun install(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        previousCrash = readAndDelete(file)
        if (previousCrash != null) AppLog.w(TAG, "The last session crashed. Its log is included in the export.")

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                AppLog.e(TAG, "Crash on thread ${thread.name}", error)
                file.writeText(AppLog.buffer.dump())
            } catch (_: Throwable) {
                // Nothing more can be done while the app is going down.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun readAndDelete(file: File): String? {
        if (!file.isFile) return null
        return try {
            file.readText().ifBlank { null }
        } catch (e: Exception) {
            AppLog.w(TAG, "Couldn't read the log saved at the last crash", e)
            null
        } finally {
            file.delete()
        }
    }
}
