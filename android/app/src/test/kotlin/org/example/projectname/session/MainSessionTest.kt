package org.example.projectname.session

import org.example.projectname.core.session.RecordState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class MainSessionTest {
    private class FakeRecorder : Recorder {
        val started = ArrayList<String>()
        var stops = 0

        override fun startRecording(fileName: String) {
            started += fileName
        }

        override fun stopRecording() {
            stops++
        }
    }

    private val recorder = FakeRecorder()
    private var clock = 10_000L
    private val logged = ArrayList<String>()
    private val session = MainSession(
        recorder = recorder,
        localNow = { LocalDateTime.of(2026, 11, 14, 9, 30, 5) },
        monotonicMillis = { clock },
        log = { logged += it },
    )

    private fun readyAndRecording(): MainSession = session.apply {
        onEngineReady()
        onRecordPressed()
        onRecordingStarted()
    }

    @Test
    fun recordIsDisabledUntilTheEngineIsReady() {
        assertFalse(session.state.recordButton.enabled)
        session.onRecordPressed()
        assertTrue(recorder.started.isEmpty())
        session.onEngineReady()
        assertTrue(session.state.recordButton.enabled)
        assertEquals(RecordButton.Label.RECORD, session.state.recordButton.label)
    }

    @Test
    fun UI1_REC4_recordPressStartsANamedRecordingAndTheButtonReadsStop() {
        session.onEngineReady()
        session.onRecordPressed()
        assertEquals(listOf("PROJECT_NAME_20261114_093005.mp4"), recorder.started)
        assertEquals(RecordState.Starting, session.state.session.record)
        assertEquals(RecordButton(RecordButton.Label.STOP, enabled = true), session.state.recordButton)
        assertEquals(RecordStatus.Starting, session.state.recordStatus(clock))
    }

    @Test
    fun UI1_secondPressStopsAndTheButtonReadsRecordOnceStopped() {
        readyAndRecording()
        session.onRecordPressed()
        assertEquals(1, recorder.stops)
        assertEquals(RecordState.Stopping, session.state.session.record)
        assertEquals(RecordButton(RecordButton.Label.STOP, enabled = false), session.state.recordButton)

        session.onRecordPressed()
        assertEquals("a press while stopping does nothing", 1, recorder.stops)

        session.onRecordingStopped(willSave = true)
        assertEquals(RecordState.Idle, session.state.session.record)
        assertEquals(RecordButton(RecordButton.Label.RECORD, enabled = true), session.state.recordButton)
        assertEquals(RecordStatus.Saving, session.state.recordStatus(clock))
        assertNull(session.state.recordingFileName)
    }

    @Test
    fun elapsedTimeCountsFromTheFirstFrame() {
        session.onEngineReady()
        session.onRecordPressed()
        clock = 12_000L
        session.onRecordingStarted()
        assertEquals(RecordStatus.Recording(elapsedMillis = 0), session.state.recordStatus(12_000L))
        assertEquals(RecordStatus.Recording(elapsedMillis = 83_000), session.state.recordStatus(95_000L))
        assertEquals("00:01:23", formatElapsed(83_000))
    }

    @Test
    fun savedRecordingLeavesANoticeAndClearsTheSavingState() {
        readyAndRecording()
        session.onRecordPressed()
        session.onRecordingStopped(willSave = true)
        session.onRecordingSaved("Movies/PROJECT_NAME/PROJECT_NAME_20261114_093005.mp4")
        assertEquals(RecordStatus.Idle, session.state.recordStatus(clock))
        assertEquals("Saved to Movies/PROJECT_NAME/PROJECT_NAME_20261114_093005.mp4", session.state.notice)
        session.onNoticeShown()
        assertNull(session.state.notice)
    }

    @Test
    fun stopBeforeTheFirstFrameSaysNothingWasRecorded() {
        session.onEngineReady()
        session.onRecordPressed()
        session.onRecordPressed()
        session.onRecordingStopped(willSave = false)
        assertEquals(RecordStatus.Idle, session.state.recordStatus(clock))
        assertEquals("Nothing was recorded, so there is no file to save.", session.state.notice)
    }

    @Test
    fun UI8_failureShowsItsReasonUntilTheNextPress() {
        readyAndRecording()
        // A recording that failed is never saved.
        session.onRecordingFailed("Writing the recording failed, so it stopped and wasn't saved.", willSave = false)
        assertEquals(RecordStatus.Failed("Writing the recording failed, so it stopped and wasn't saved."), session.state.recordStatus(clock))
        assertEquals("Writing the recording failed, so it stopped and wasn't saved.", session.state.banner)
        assertEquals(0, session.state.savesInProgress)
        assertEquals(RecordButton(RecordButton.Label.RECORD, enabled = true), session.state.recordButton)

        // The engine still confirms the stop; that mustn't hide the failure or add a second message.
        session.onRecordingStopped(willSave = false)
        assertTrue(session.state.session.record is RecordState.Failed)
        assertNull(session.state.notice)

        session.onRecordPressed()
        assertNull(session.state.banner)
        assertEquals(2, recorder.started.size)
        assertEquals(RecordState.Starting, session.state.session.record)
    }

    @Test
    fun UI8_aRecordingThatCouldntBeFinishedSaysSoOnceTheUserStopsIt() {
        // The engine checks the file after the stop and reports a failure instead of a save.
        readyAndRecording()
        session.onRecordPressed()
        val reason = "The recording couldn't be finished properly, so it wasn't saved."
        session.onRecordingFailed(reason, willSave = false)
        assertEquals(RecordStatus.Failed(reason), session.state.recordStatus(clock))
        assertEquals(reason, session.state.banner)
        assertEquals(0, session.state.savesInProgress)
        assertNull("no second message about it", session.state.notice)
        assertEquals(RecordButton(RecordButton.Label.RECORD, enabled = true), session.state.recordButton)
    }

    @Test
    fun saveFailureIsShownAsABanner() {
        readyAndRecording()
        session.onRecordPressed()
        session.onRecordingStopped(willSave = true)
        session.onSaveFailed("The recording couldn't be copied to Movies.")
        assertEquals("The recording couldn't be copied to Movies.", session.state.banner)
        assertEquals(0, session.state.savesInProgress)
        session.onBannerDismissed()
        assertNull(session.state.banner)
    }

    @Test
    fun engineFailureDisablesRecord() {
        session.onEngineReady()
        session.onEngineFailed("This phone's encoders didn't accept H.264 at 1920x1080.")
        assertFalse(session.state.recordButton.enabled)
        assertEquals("This phone's encoders didn't accept H.264 at 1920x1080.", session.state.banner)
    }

    @Test
    fun leavingTheScreenStopsARecordingAndTheSavedNoticeSaysWhy() {
        readyAndRecording()
        session.onAppHidden()
        assertEquals(1, recorder.stops)
        assertEquals(RecordState.Stopping, session.state.session.record)
        assertEquals("Recording stopped because the app left the screen.", session.state.notice)

        // The engine replies while the app is out of sight, so the user only sees the last notice.
        session.onRecordingStopped(willSave = true)
        session.onRecordingSaved("Movies/PROJECT_NAME/PROJECT_NAME_20261114_093005.mp4")
        assertEquals(
            "Recording stopped because the app left the screen. Saved to Movies/PROJECT_NAME/PROJECT_NAME_20261114_093005.mp4.",
            session.state.notice,
        )
    }

    @Test
    fun leavingTheScreenBeforeTheFirstFrameSaysWhyNothingWasRecorded() {
        session.onEngineReady()
        session.onRecordPressed()
        session.onAppHidden()
        session.onRecordingStopped(willSave = false)
        assertEquals(
            "Recording stopped because the app left the screen. Nothing was recorded, so there is no file to save.",
            session.state.notice,
        )
    }

    @Test
    fun aSaveThatFailsAfterLeavingTheScreenSaysWhyTheRecordingStopped() {
        readyAndRecording()
        session.onAppHidden()
        session.onRecordingStopped(willSave = true)
        session.onSaveFailed("The recording couldn't be copied to Movies.")
        assertEquals(
            "Recording stopped because the app left the screen. The recording couldn't be copied to Movies.",
            session.state.banner,
        )
    }

    @Test
    fun theReasonGoesWithItsOwnRecordingOnly() {
        // The first recording is stopped by leaving the screen; the second by the user, while the first is
        // still being copied. Copies finish in the order the recordings stopped.
        readyAndRecording()
        session.onAppHidden()
        session.onRecordingStopped(willSave = true)
        session.onRecordPressed()
        session.onRecordingStarted()
        session.onRecordPressed()
        session.onRecordingStopped(willSave = true)
        assertEquals(2, session.state.savesInProgress)

        session.onRecordingSaved("Movies/PROJECT_NAME/first.mp4")
        assertEquals("Recording stopped because the app left the screen. Saved to Movies/PROJECT_NAME/first.mp4.", session.state.notice)
        session.onRecordingSaved("Movies/PROJECT_NAME/second.mp4")
        assertEquals("Saved to Movies/PROJECT_NAME/second.mp4", session.state.notice)
        assertEquals(0, session.state.savesInProgress)
    }

    @Test
    fun aStopByTheUserAfterAnEarlierHiddenStopHasNoReason() {
        session.onEngineReady()
        session.onRecordPressed()
        session.onAppHidden()
        session.onRecordingStopped(willSave = false)
        session.onRecordPressed()
        session.onRecordPressed()
        session.onRecordingStopped(willSave = false)
        assertEquals("Nothing was recorded, so there is no file to save.", session.state.notice)
    }

    @Test
    fun leavingTheScreenWhileIdleDoesNothing() {
        session.onEngineReady()
        session.onAppHidden()
        assertEquals(0, recorder.stops)
        assertNull(session.state.notice)
    }

    @Test
    fun everyChangeIsReportedAndLogged() {
        val seen = ArrayList<ScreenState>()
        session.onChange = { seen += it }
        readyAndRecording()
        assertEquals(session.state, seen.last())
        assertTrue(logged.any { it.contains("RecordPressed") && it.contains("Starting") })
    }

    @Test
    fun formatElapsedPadsAndKeepsCountingPastADay() {
        assertEquals("00:00:00", formatElapsed(0))
        assertEquals("00:00:00", formatElapsed(-5))
        assertEquals("00:00:59", formatElapsed(59_999))
        assertEquals("01:00:00", formatElapsed(3_600_000))
        assertEquals("25:00:01", formatElapsed(90_001_000))
    }
}
