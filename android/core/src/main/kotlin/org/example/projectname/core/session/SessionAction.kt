package org.example.projectname.core.session

/** Everything that can change a [SessionState]: button presses from the UI, and results from the engine. */
sealed interface SessionAction {
    // The three buttons (spec UI-1).
    data object RecordPressed : SessionAction
    data object StreamPressed : SessionAction
    data object BothPressed : SessionAction

    // What the recorder reports.
    data object RecordingStarted : SessionAction
    data object RecordingStopped : SessionAction

    /** [reason] is shown to the user as it is, so it must be plain words. */
    data class RecordingFailed(val reason: String) : SessionAction

    /**
     * What the stream transport reports (spec UI-8). [attempt] is the number from the
     * [SessionEffect.StartStream] that started the transport. A report from any other attempt is ignored,
     * so a transport that reports late, after the user stopped it and started a new stream, can't change
     * the new stream's state.
     */
    sealed interface StreamReport : SessionAction {
        val attempt: Int
    }

    data class StreamConnecting(override val attempt: Int) : StreamReport
    data class StreamLive(override val attempt: Int) : StreamReport
    data class StreamReconnecting(override val attempt: Int) : StreamReport

    /** [reason] is shown to the user as it is, so it must be plain words (spec UI-8). */
    data class StreamFailed(override val attempt: Int, val reason: String) : StreamReport
    data class StreamStopped(override val attempt: Int) : StreamReport

    /** The user picked another camera (spec UI-6). */
    data class SourceChangeRequested(val sourceId: String) : SessionAction

    /** The destination profile Stream uses changed. Null means no profile is saved (spec UI-3). */
    data class DestinationSelected(val profileId: String?) : SessionAction
}

/** Work the reducer asks the app to do. The reducer itself never does any. */
sealed interface SessionEffect {
    data object StartRecording : SessionEffect
    data object StopRecording : SessionEffect

    /**
     * Start streaming to the destination profile with this ID. [attempt] names this start: the transport
     * must put it in every [SessionAction.StreamReport] it sends (spec UI-8).
     */
    data class StartStream(val profileId: String, val attempt: Int) : SessionEffect
    data object StopStream : SessionEffect

    /** No destination profile is saved, so open the editor instead of streaming (spec UI-3). */
    data object OpenDestinationEditor : SessionEffect

    /** Switch the preview to this camera. */
    data class ChangeSource(val sourceId: String) : SessionEffect

    /** Show the user a short message, for example why the source can't change now (spec UI-6). */
    data class ShowMessage(val message: String) : SessionEffect
}

/** A reducer step: the next state, and the work to do, in order. */
data class SessionTransition(
    val state: SessionState,
    val effects: List<SessionEffect> = emptyList(),
)
