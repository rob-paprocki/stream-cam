package org.example.projectname.log

import android.util.Log
import org.example.projectname.core.log.LogBuffer
import org.example.projectname.core.log.Redactor

/**
 * The app-wide debug log. Lines go into a [LogBuffer], which redacts them as they arrive (spec OUT-6), and
 * the same redacted text goes to Logcat. "Export log" shares the buffer.
 */
object AppLog {
    private const val LOGCAT_TAG = "PROJECT_NAME"

    /** How many stack frames an error line keeps. Enough to find the place, short enough to read. */
    private const val STACK_FRAMES = 12

    val buffer = LogBuffer()

    fun i(tag: String, message: String) {
        buffer.append(tag, message)
        Log.i(LOGCAT_TAG, "$tag: ${Redactor.redact(message)}")
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        val text = withError(message, error)
        buffer.append(tag, "WARNING $text")
        Log.w(LOGCAT_TAG, "$tag: ${Redactor.redact(text)}")
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        val text = withError(message, error)
        buffer.append(tag, "ERROR $text")
        Log.e(LOGCAT_TAG, "$tag: ${Redactor.redact(text)}")
    }

    /** The error's type, message and first stack frames, and the same for its causes. */
    fun describe(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4) {
            if (depth > 0) append("\nCaused by: ")
            append(current.javaClass.name)
            current.message?.let { append(": ").append(it) }
            for (frame in current.stackTrace.take(STACK_FRAMES)) append("\n  at ").append(frame)
            current = current.cause?.takeIf { it !== current }
            depth++
        }
    }

    private fun withError(message: String, error: Throwable?): String =
        if (error == null) message else "$message\n${describe(error)}"
}
