package org.example.projectname.core.session

/** The local recording's state. */
sealed interface RecordState {
    data object Idle : RecordState
    data object Starting : RecordState
    data object Recording : RecordState
    data object Stopping : RecordState

    /** The last recording failed. [reason] is in plain words, ready to show. */
    data class Failed(val reason: String) : RecordState
}

/** The live stream's state: always one of these five (spec UI-8). */
sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data object Live : StreamState
    data object Reconnecting : StreamState

    /** The stream failed. [reason] is in plain words, ready to show (spec UI-8). */
    data class Failed(val reason: String) : StreamState
}

/**
 * Everything the main screen needs to draw its Record, Stream and Both buttons (spec UI-1). The UI reads
 * this object and never calls a streaming library directly.
 *
 * Record and Stream are independent (spec UI-2): each has its own state, and nothing that happens to one
 * changes the other.
 *
 * @property selectedSourceId the stable ID of the chosen camera, or null before one is chosen.
 * @property selectedDestinationId the ID of the destination profile Stream uses, or null when no profile
 *   is saved (spec UI-3).
 * @property streamAttempt the number of the latest stream start: 0 before the first, then one more for
 *   each [SessionEffect.StartStream]. Only reports that carry this number change [stream] (spec UI-8).
 */
data class SessionState(
    val record: RecordState = RecordState.Idle,
    val stream: StreamState = StreamState.Idle,
    val selectedSourceId: String? = null,
    val selectedDestinationId: String? = null,
    val streamAttempt: Int = 0,
) {
    /** A recording is starting or running. The Record button reads "Stop" (spec UI-1). */
    val isRecordActive: Boolean
        get() = record == RecordState.Starting || record == RecordState.Recording

    /** A stream is connecting, live or reconnecting. The Stream button reads "Stop" (spec UI-1). */
    val isStreamActive: Boolean
        get() = stream == StreamState.Connecting || stream == StreamState.Live || stream == StreamState.Reconnecting

    /** Both parts are running, so the Both button reads "Stop" and pressing it stops both (spec UI-2). */
    val isBothActive: Boolean
        get() = isRecordActive && isStreamActive

    /** Something is running or winding down, so the source can't be changed (spec UI-6). */
    val isCapturing: Boolean
        get() = isRecordActive || record == RecordState.Stopping || isStreamActive
}
