package org.example.projectname.session

import org.example.projectname.core.session.RecordState
import org.example.projectname.core.session.SessionState

/** The stable ID of the built-in test-pattern source. */
const val TEST_PATTERN_SOURCE_ID = "test-pattern"

/**
 * Everything the main screen shows. The UI reads this and nothing else (spec structure rule 3).
 *
 * @property session the Record / Stream / Both state from the core reducer.
 * @property engineReady the encoders are set up, so Record can be pressed.
 * @property recordingFileName the file name of the recording in progress, once it has started.
 * @property recordingSinceMillis the monotonic clock reading when the file got its first frame.
 * @property savesInProgress finished recordings that are still being copied to the shared Movies folder.
 * @property banner an error or warning in plain words. It stays until dismissed or until the next press.
 * @property notice a short note, such as where a recording was saved. The screen shows it once.
 */
data class ScreenState(
    val session: SessionState = SessionState(selectedSourceId = TEST_PATTERN_SOURCE_ID),
    val engineReady: Boolean = false,
    val recordingFileName: String? = null,
    val recordingSinceMillis: Long? = null,
    val savesInProgress: Int = 0,
    val banner: String? = null,
    val notice: String? = null,
) {
    /** The Record toggle (spec UI-1): it reads "Stop" while a recording is starting, running or stopping. */
    val recordButton: RecordButton
        get() {
            val stopping = session.record == RecordState.Stopping
            val label = if (session.isRecordActive || stopping) RecordButton.Label.STOP else RecordButton.Label.RECORD
            return RecordButton(label, enabled = engineReady && !stopping)
        }

    /** The recording part of the status strip, with the elapsed time worked out at [nowMillis]. */
    fun recordStatus(nowMillis: Long): RecordStatus = when (val record = session.record) {
        RecordState.Starting -> RecordStatus.Starting
        RecordState.Recording -> RecordStatus.Recording(
            elapsedMillis = recordingSinceMillis?.let { (nowMillis - it).coerceAtLeast(0) } ?: 0,
        )
        RecordState.Stopping -> RecordStatus.Stopping
        is RecordState.Failed -> RecordStatus.Failed(record.reason)
        RecordState.Idle -> if (savesInProgress > 0) RecordStatus.Saving else RecordStatus.Idle
    }
}

data class RecordButton(val label: Label, val enabled: Boolean) {
    enum class Label { RECORD, STOP }
}

sealed interface RecordStatus {
    data object Idle : RecordStatus
    data object Starting : RecordStatus
    data class Recording(val elapsedMillis: Long) : RecordStatus
    data object Stopping : RecordStatus

    /** Not recording, but a finished recording is still being copied to the Movies folder. */
    data object Saving : RecordStatus
    data class Failed(val reason: String) : RecordStatus
}

/** Formats a duration as `HH:MM:SS`, with ASCII digits in any language. Hours go past 99 if they must. */
fun formatElapsed(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds / 60) % 60
    val seconds = totalSeconds % 60
    return buildString(8) {
        if (hours < 10) append('0')
        append(hours)
        append(':')
        if (minutes < 10) append('0')
        append(minutes)
        append(':')
        if (seconds < 10) append('0')
        append(seconds)
    }
}
