package org.example.projectname.core.session

import org.example.projectname.core.session.SessionAction.BothPressed
import org.example.projectname.core.session.SessionAction.DestinationSelected
import org.example.projectname.core.session.SessionAction.RecordPressed
import org.example.projectname.core.session.SessionAction.RecordingFailed
import org.example.projectname.core.session.SessionAction.RecordingStarted
import org.example.projectname.core.session.SessionAction.RecordingStopped
import org.example.projectname.core.session.SessionAction.SourceChangeRequested
import org.example.projectname.core.session.SessionAction.StreamConnecting
import org.example.projectname.core.session.SessionAction.StreamFailed
import org.example.projectname.core.session.SessionAction.StreamLive
import org.example.projectname.core.session.SessionAction.StreamPressed
import org.example.projectname.core.session.SessionAction.StreamReconnecting
import org.example.projectname.core.session.SessionAction.StreamReport
import org.example.projectname.core.session.SessionAction.StreamStopped

/**
 * The Record / Stream / Both state machine (spec UI-1, UI-2, UI-3, UI-6, UI-8), as a pure function.
 *
 * Rules:
 * - Record and Stream are independent. An action about one never changes the other's state (UI-2).
 * - Record toggles: idle or failed starts a recording; starting or recording stops it; while stopping,
 *   the press is ignored until the recorder reports back. A failure stays on screen until the next
 *   press, even if the recorder then reports that it stopped.
 * - Stream toggles the same way. Stopping takes effect at once (the state goes back to idle), and any
 *   late report from the transport is then ignored. With no destination profile saved, Stream opens
 *   the destination editor instead (UI-3).
 * - Every stream start is a new attempt with its own number, which [SessionEffect.StartStream] carries.
 *   A transport report counts only if it carries the current attempt's number and the stream is still
 *   running. So a transport the user stopped can't change the state of the stream that replaced it (UI-8).
 * - Both starts whichever part isn't running. When both are running, it stops both (UI-2). With no
 *   destination profile, Both opens the editor and starts nothing, so it never does half of what it says.
 *   For the same reason, while the recording is stopping, Both does nothing, as Record does.
 * - The source can't change while anything is recording, stopping or streaming. The reducer explains why
 *   instead (UI-6).
 */
object SessionReducer {

    fun reduce(state: SessionState, action: SessionAction): SessionTransition = when (action) {
        RecordPressed -> onRecordPressed(state)
        StreamPressed -> onStreamPressed(state)
        BothPressed -> onBothPressed(state)

        RecordingStarted -> when (state.record) {
            // The user already pressed Stop; the stop request is on its way.
            RecordState.Stopping -> SessionTransition(state)
            else -> SessionTransition(state.copy(record = RecordState.Recording))
        }
        RecordingStopped -> when (state.record) {
            // A recorder that fails usually reports a stop while it cleans up. Keep the reason on screen.
            is RecordState.Failed -> SessionTransition(state)
            else -> SessionTransition(state.copy(record = RecordState.Idle))
        }
        is RecordingFailed -> SessionTransition(state.copy(record = RecordState.Failed(plainReason(action.reason, RECORD_FAILED_DEFAULT))))

        is StreamConnecting -> streamReport(state, action, StreamState.Connecting)
        is StreamLive -> streamReport(state, action, StreamState.Live)
        is StreamReconnecting -> streamReport(state, action, StreamState.Reconnecting)
        is StreamFailed -> streamReport(state, action, StreamState.Failed(plainReason(action.reason, STREAM_FAILED_DEFAULT)))
        is StreamStopped -> streamReport(state, action, StreamState.Idle)

        is SourceChangeRequested -> onSourceChange(state, action.sourceId)
        is DestinationSelected -> SessionTransition(state.copy(selectedDestinationId = action.profileId))
    }

    private fun onRecordPressed(state: SessionState): SessionTransition = when (state.record) {
        RecordState.Idle, is RecordState.Failed -> startRecording(state)
        RecordState.Starting, RecordState.Recording -> stopRecording(state)
        RecordState.Stopping -> SessionTransition(state)
    }

    private fun onStreamPressed(state: SessionState): SessionTransition = when {
        state.isStreamActive -> stopStream(state)
        else -> startStream(state)
    }

    private fun onBothPressed(state: SessionState): SessionTransition {
        // The recording can't start again until the recorder reports that it stopped. Starting only the
        // stream would do half of what Both says.
        if (state.record == RecordState.Stopping) return SessionTransition(state)
        if (state.isBothActive) {
            val afterRecord = stopRecording(state)
            val afterStream = stopStream(afterRecord.state)
            return SessionTransition(afterStream.state, afterRecord.effects + afterStream.effects)
        }
        val needsStream = !state.isStreamActive
        if (needsStream && state.selectedDestinationId == null) {
            return SessionTransition(state, listOf(SessionEffect.OpenDestinationEditor))
        }
        var next = state
        val effects = ArrayList<SessionEffect>(2)
        if (state.record == RecordState.Idle || state.record is RecordState.Failed) {
            val started = startRecording(next)
            next = started.state
            effects += started.effects
        }
        if (needsStream) {
            val started = startStream(next)
            next = started.state
            effects += started.effects
        }
        return SessionTransition(next, effects)
    }

    private fun startRecording(state: SessionState) =
        SessionTransition(state.copy(record = RecordState.Starting), listOf(SessionEffect.StartRecording))

    private fun stopRecording(state: SessionState) =
        SessionTransition(state.copy(record = RecordState.Stopping), listOf(SessionEffect.StopRecording))

    private fun startStream(state: SessionState): SessionTransition {
        val profileId = state.selectedDestinationId
            ?: return SessionTransition(state, listOf(SessionEffect.OpenDestinationEditor))
        val attempt = state.streamAttempt + 1
        return SessionTransition(
            state.copy(stream = StreamState.Connecting, streamAttempt = attempt),
            listOf(SessionEffect.StartStream(profileId, attempt)),
        )
    }

    private fun stopStream(state: SessionState) =
        SessionTransition(state.copy(stream = StreamState.Idle), listOf(SessionEffect.StopStream))

    /**
     * Reports from the transport count only while a stream is running, and only from the attempt that is
     * running now. Once the user has stopped a stream, or after it failed, a late report must not bring it
     * back to life on screen, nor change the state of a newer stream.
     */
    private fun streamReport(state: SessionState, report: StreamReport, next: StreamState): SessionTransition =
        if (state.isStreamActive && report.attempt == state.streamAttempt) {
            SessionTransition(state.copy(stream = next))
        } else {
            SessionTransition(state)
        }

    private fun onSourceChange(state: SessionState, sourceId: String): SessionTransition {
        if (state.isCapturing) {
            val recording = state.isRecordActive || state.record == RecordState.Stopping
            val message = when {
                recording && state.isStreamActive -> "Stop recording and streaming before you change the source."
                recording -> "Stop recording before you change the source."
                else -> "Stop streaming before you change the source."
            }
            return SessionTransition(state, listOf(SessionEffect.ShowMessage(message)))
        }
        if (sourceId == state.selectedSourceId) return SessionTransition(state)
        return SessionTransition(state.copy(selectedSourceId = sourceId), listOf(SessionEffect.ChangeSource(sourceId)))
    }

    private fun plainReason(reason: String, fallback: String): String = reason.trim().ifEmpty { fallback }

    private const val RECORD_FAILED_DEFAULT = "The recording stopped, and no reason was given."
    private const val STREAM_FAILED_DEFAULT = "The stream stopped, and no reason was given."
}
