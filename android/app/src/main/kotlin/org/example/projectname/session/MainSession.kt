package org.example.projectname.session

import org.example.projectname.core.recording.RecordingNames
import org.example.projectname.core.session.RecordState
import org.example.projectname.core.session.SessionAction
import org.example.projectname.core.session.SessionEffect
import org.example.projectname.core.session.SessionReducer
import org.example.projectname.core.session.SessionState
import java.time.LocalDateTime

/** The part of the capture engine the session drives. */
interface Recorder {
    /** Starts recording into a file called [fileName]. The engine reports back through [MainSession]. */
    fun startRecording(fileName: String)

    /** Stops the recording in progress and saves it. The engine reports back through [MainSession]. */
    fun stopRecording()
}

/**
 * Runs the main screen: button presses go through the core [SessionReducer] (spec UI-1), and the effects
 * it asks for are passed to the [Recorder]. Reports from the engine come back in through the `on...`
 * methods. It has no Android types, so the unit tests drive it with a fake recorder.
 *
 * Call it from one thread only (the main thread in the app).
 *
 * @param localNow the local date and time, for the recording's file name (spec REC-4).
 * @param monotonicMillis a clock that never jumps, for the elapsed time on screen.
 * @param log where to write debug-log lines.
 */
class MainSession(
    private val recorder: Recorder,
    private val localNow: () -> LocalDateTime,
    private val monotonicMillis: () -> Long,
    private val log: (String) -> Unit = {},
) {
    var state: ScreenState = ScreenState()
        private set

    /** Why the recording in progress is being stopped, when it wasn't the user. */
    private var stopReason: String? = null

    /**
     * One entry for each finished recording still being copied to Movies, oldest first: why it stopped, if
     * it wasn't the user. Copies finish in the order the recordings stopped.
     */
    private val saveReasons = ArrayDeque<String?>()

    /** Called after every change, with the new state. */
    var onChange: (ScreenState) -> Unit = {}

    // From the screen.

    fun onRecordPressed() {
        if (!state.recordButton.enabled) {
            log("Record pressed while the button is disabled; ignored")
            return
        }
        update { it.copy(banner = null) }
        dispatch(SessionAction.RecordPressed)
    }

    /**
     * The app went off screen. With no foreground service yet, a recording is stopped and saved. The notice
     * that ends the recording (saved, or nothing recorded) says why it stopped, because the engine replies
     * while the app is out of sight and the user only sees the last notice.
     */
    fun onAppHidden() {
        if (!state.session.isRecordActive) return
        log("App left the screen while recording; stopping the recording")
        stopReason = LEFT_THE_SCREEN
        dispatch(SessionAction.RecordPressed)
        update { it.copy(notice = LEFT_THE_SCREEN) }
    }

    fun onBannerDismissed() = update { it.copy(banner = null) }

    fun onNoticeShown() = update { it.copy(notice = null) }

    // From the engine.

    fun onEngineReady() = update { it.copy(engineReady = true) }

    fun onEngineFailed(reason: String) {
        log("Engine failed: $reason")
        update { it.copy(engineReady = false, banner = reason) }
    }

    /** The first frame reached the file. The elapsed time counts from here. */
    fun onRecordingStarted() {
        dispatch(SessionAction.RecordingStarted)
        update { it.copy(recordingSinceMillis = monotonicMillis()) }
    }

    /**
     * The recording stopped as asked. [willSave] says whether there is a file to copy to the Movies folder;
     * a recording stopped before its first frame has none.
     */
    fun onRecordingStopped(willSave: Boolean) {
        // After a failure the engine still confirms the stop; the failure's banner says enough then.
        val stoppedByUser = state.session.record == RecordState.Stopping
        val reason = takeStopReason(willSave)
        dispatch(SessionAction.RecordingStopped)
        update {
            it.copy(
                recordingFileName = null,
                recordingSinceMillis = null,
                savesInProgress = saveReasons.size,
                notice = if (stoppedByUser && !willSave) withReason(reason, NOTHING_RECORDED) else it.notice,
            )
        }
    }

    /** The recording failed. [reason] is shown in plain words. [willSave] as for [onRecordingStopped]. */
    fun onRecordingFailed(reason: String, willSave: Boolean) {
        takeStopReason(willSave)
        dispatch(SessionAction.RecordingFailed(reason))
        update {
            it.copy(
                recordingFileName = null,
                recordingSinceMillis = null,
                savesInProgress = saveReasons.size,
                banner = reason,
            )
        }
    }

    /** The oldest finished recording still being copied is now in the shared Movies folder at [where]. */
    fun onRecordingSaved(where: String) {
        val reason = saveReasons.removeFirstOrNull()
        update {
            it.copy(
                savesInProgress = saveReasons.size,
                notice = if (reason == null) "Saved to $where" else "$reason Saved to $where.",
            )
        }
    }

    /** The oldest finished recording still being copied couldn't be copied to Movies. [reason] says what now. */
    fun onSaveFailed(reason: String) {
        val stopped = saveReasons.removeFirstOrNull()
        update { it.copy(savesInProgress = saveReasons.size, banner = withReason(stopped, reason)) }
    }

    /**
     * The recording has stopped: returns why, if it wasn't the user, and clears it. When [willSave], the
     * reason waits with the recording's copy, for the notice that ends it.
     */
    private fun takeStopReason(willSave: Boolean): String? {
        val reason = stopReason
        stopReason = null
        if (willSave) saveReasons.addLast(reason)
        return reason
    }

    private fun withReason(reason: String?, message: String): String = if (reason == null) message else "$reason $message"

    private fun dispatch(action: SessionAction) {
        val before = state.session
        val transition = SessionReducer.reduce(before, action)
        update { it.copy(session = transition.state) }
        log("$action: ${describe(before)} -> ${describe(transition.state)}")
        transition.effects.forEach(::perform)
    }

    private fun perform(effect: SessionEffect) {
        when (effect) {
            SessionEffect.StartRecording -> {
                val name = RecordingNames.fileName(localNow())
                update { it.copy(recordingFileName = name, recordingSinceMillis = null) }
                recorder.startRecording(name)
            }
            SessionEffect.StopRecording -> recorder.stopRecording()
            is SessionEffect.ShowMessage -> update { it.copy(notice = effect.message) }
            // Streaming and source changes arrive in later milestones; this build has no buttons for them.
            is SessionEffect.StartStream, SessionEffect.StopStream, SessionEffect.OpenDestinationEditor,
            is SessionEffect.ChangeSource,
            -> log("$effect isn't available in this build; ignored")
        }
    }

    private inline fun update(change: (ScreenState) -> ScreenState) {
        val next = change(state)
        if (next != state) {
            state = next
            onChange(next)
        }
    }

    private fun describe(session: SessionState): String = "record=${session.record}, stream=${session.stream}"

    private companion object {
        const val LEFT_THE_SCREEN = "Recording stopped because the app left the screen."
        const val NOTHING_RECORDED = "Nothing was recorded, so there is no file to save."
    }
}
