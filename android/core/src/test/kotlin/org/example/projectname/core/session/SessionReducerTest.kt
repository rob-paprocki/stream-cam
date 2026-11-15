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
import org.example.projectname.core.session.SessionAction.StreamStopped
import org.example.projectname.core.session.SessionEffect.ChangeSource
import org.example.projectname.core.session.SessionEffect.OpenDestinationEditor
import org.example.projectname.core.session.SessionEffect.ShowMessage
import org.example.projectname.core.session.SessionEffect.StartRecording
import org.example.projectname.core.session.SessionEffect.StartStream
import org.example.projectname.core.session.SessionEffect.StopRecording
import org.example.projectname.core.session.SessionEffect.StopStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionReducerTest {
    private val profileId = "studio"
    private val idle = SessionState(selectedSourceId = "camera-0", selectedDestinationId = profileId)
    private val noProfile = SessionState(selectedSourceId = "camera-0")

    private fun SessionState.after(vararg actions: SessionAction): SessionState =
        actions.fold(this) { state, action -> SessionReducer.reduce(state, action).state }

    private fun SessionState.press(action: SessionAction): SessionTransition = SessionReducer.reduce(this, action)

    // The first stream started from idle is attempt 1, and every later start counts up by one (UI-8).
    private val recording get() = idle.after(RecordPressed, RecordingStarted)
    private val live get() = idle.after(StreamPressed, StreamConnecting(1), StreamLive(1))
    private val both get() = idle.after(RecordPressed, RecordingStarted, StreamPressed, StreamLive(1))

    // UI-1: three toggles, each reading "Stop" for its part while active.

    @Test
    fun UI1_recordPressedStartsRecordingAndTheButtonReadsStop() {
        val step = idle.press(RecordPressed)
        assertEquals(RecordState.Starting, step.state.record)
        assertEquals(listOf(StartRecording), step.effects)
        assertTrue(step.state.isRecordActive)
        assertEquals(RecordState.Recording, step.state.after(RecordingStarted).record)
        assertTrue(recording.isRecordActive)
    }

    @Test
    fun UI1_recordPressedWhileRecordingStopsIt() {
        val step = recording.press(RecordPressed)
        assertEquals(RecordState.Stopping, step.state.record)
        assertEquals(listOf(StopRecording), step.effects)
        assertFalse(step.state.isRecordActive)
        assertEquals(RecordState.Idle, step.state.after(RecordingStopped).record)
    }

    @Test
    fun UI1_recordPressedWhileStartingStopsIt() {
        val starting = idle.after(RecordPressed)
        val step = starting.press(RecordPressed)
        assertEquals(RecordState.Stopping, step.state.record)
        assertEquals(listOf(StopRecording), step.effects)
    }

    @Test
    fun UI1_recordPressedWhileStoppingDoesNothing() {
        val stopping = recording.after(RecordPressed)
        val step = stopping.press(RecordPressed)
        assertEquals(stopping, step.state)
        assertEquals(emptyList(), step.effects)
    }

    @Test
    fun UI1_aStartReportAfterStopWasPressedKeepsStopping() {
        val stopping = idle.after(RecordPressed, RecordPressed)
        assertEquals(RecordState.Stopping, stopping.after(RecordingStarted).record)
    }

    @Test
    fun UI1_streamPressedStartsTheSelectedProfileAndTheButtonReadsStop() {
        val step = idle.press(StreamPressed)
        assertEquals(StreamState.Connecting, step.state.stream)
        assertEquals(listOf(StartStream(profileId, 1)), step.effects)
        assertTrue(step.state.isStreamActive)
    }

    @Test
    fun UI1_streamPressedWhileConnectingLiveOrReconnectingStopsIt() {
        val connecting = idle.after(StreamPressed)
        val reconnecting = live.after(StreamReconnecting(1))
        for (state in listOf(connecting, live, reconnecting)) {
            val step = state.press(StreamPressed)
            assertEquals(StreamState.Idle, step.state.stream, "from ${state.stream}")
            assertEquals(listOf(StopStream), step.effects, "from ${state.stream}")
        }
    }

    // UI-2: Record and Stream are independent; Both starts what isn't running and stops both.

    @Test
    fun UI2_stoppingTheStreamLeavesTheRecordingRunning() {
        val step = both.press(StreamPressed)
        assertEquals(RecordState.Recording, step.state.record)
        assertEquals(listOf(StopStream), step.effects)
    }

    @Test
    fun UI2_stoppingTheRecordingLeavesTheStreamRunning() {
        val step = both.press(RecordPressed)
        assertEquals(StreamState.Live, step.state.stream)
        assertEquals(listOf(StopRecording), step.effects)
    }

    @Test
    fun UI2_streamProblemsNeverTouchTheRecording() {
        val afterTrouble = both.after(StreamReconnecting(1), StreamConnecting(1), StreamFailed(1, "The server closed the connection."))
        assertEquals(RecordState.Recording, afterTrouble.record)
    }

    @Test
    fun UI2_recordingProblemsNeverTouchTheStream() {
        val afterTrouble = both.after(RecordingFailed("The storage is full."), RecordingStopped)
        assertEquals(StreamState.Live, afterTrouble.stream)
    }

    @Test
    fun UI2_bothStartsRecordingAndStreamWhenNeitherRuns() {
        val step = idle.press(BothPressed)
        assertEquals(RecordState.Starting, step.state.record)
        assertEquals(StreamState.Connecting, step.state.stream)
        assertEquals(listOf(StartRecording, StartStream(profileId, 1)), step.effects)
        assertTrue(step.state.isBothActive)
    }

    @Test
    fun UI2_bothStartsOnlyTheStreamWhenRecordingRuns() {
        val step = recording.press(BothPressed)
        assertEquals(RecordState.Recording, step.state.record)
        assertEquals(StreamState.Connecting, step.state.stream)
        assertEquals(listOf(StartStream(profileId, 1)), step.effects)
    }

    @Test
    fun UI2_bothStartsOnlyTheRecordingWhenTheStreamRuns() {
        val step = live.press(BothPressed)
        assertEquals(StreamState.Live, step.state.stream)
        assertEquals(RecordState.Starting, step.state.record)
        assertEquals(listOf(StartRecording), step.effects)
    }

    @Test
    fun UI2_bothStopsBothWhenBothRun() {
        assertTrue(both.isBothActive)
        val step = both.press(BothPressed)
        assertEquals(RecordState.Stopping, step.state.record)
        assertEquals(StreamState.Idle, step.state.stream)
        assertEquals(listOf(StopRecording, StopStream), step.effects)
    }

    @Test
    fun UI2_bothStopsBothWhileTheStreamIsStillConnecting() {
        val starting = idle.after(BothPressed)
        val step = starting.press(BothPressed)
        assertEquals(listOf(StopRecording, StopStream), step.effects)
    }

    @Test
    fun UI2_bothRestartsAPartThatFailed() {
        val failed = idle.after(StreamPressed, StreamFailed(1, "The server refused the connection."), RecordPressed, RecordingStarted)
        val step = failed.press(BothPressed)
        assertEquals(listOf(StartStream(profileId, 2)), step.effects)
        assertEquals(StreamState.Connecting, step.state.stream)
    }

    @Test
    fun UI2_bothDoesNothingWhileTheRecordingIsStillStopping() {
        // Like Record, Both waits until the recorder reports back, so it never starts only half of what it says.
        val stoppingStates = listOf(
            recording.after(RecordPressed),
            both.after(RecordPressed),
            noProfile.after(RecordPressed, RecordingStarted, RecordPressed),
        )
        for (stopping in stoppingStates) {
            assertEquals(RecordState.Stopping, stopping.record)
            val step = stopping.press(BothPressed)
            assertEquals(stopping, step.state)
            assertEquals(emptyList(), step.effects)
        }
        val stopped = recording.after(RecordPressed, RecordingStopped)
        assertEquals(listOf(StartRecording, StartStream(profileId, 1)), stopped.press(BothPressed).effects)
    }

    // UI-3: with no destination profile, Stream opens the editor instead of failing.

    @Test
    fun UI3_streamWithoutAProfileOpensTheEditorInsteadOfStarting() {
        val step = noProfile.press(StreamPressed)
        assertEquals(noProfile, step.state)
        assertEquals(listOf(OpenDestinationEditor), step.effects)
    }

    @Test
    fun UI3_bothWithoutAProfileOpensTheEditorAndStartsNothing() {
        val step = noProfile.press(BothPressed)
        assertEquals(noProfile, step.state)
        assertEquals(listOf(OpenDestinationEditor), step.effects)
    }

    @Test
    fun UI3_bothWithoutAProfileKeepsARunningRecordingAndOpensTheEditor() {
        val recordingOnly = noProfile.after(RecordPressed, RecordingStarted)
        val step = recordingOnly.press(BothPressed)
        assertEquals(RecordState.Recording, step.state.record)
        assertEquals(listOf(OpenDestinationEditor), step.effects)
    }

    @Test
    fun UI3_streamStartsOnceAProfileIsSelected() {
        val selected = noProfile.after(DestinationSelected("new-profile"))
        assertEquals(listOf(StartStream("new-profile", 1)), selected.press(StreamPressed).effects)
        assertEquals(listOf(OpenDestinationEditor), selected.after(DestinationSelected(null)).press(StreamPressed).effects)
    }

    // UI-6: the source can't change while recording or streaming, and the user is told why.

    @Test
    fun UI6_sourceChangeIsRefusedWhileRecording() {
        val step = recording.press(SourceChangeRequested("usb-1"))
        assertEquals(recording, step.state)
        assertEquals(listOf(ShowMessage("Stop recording before you change the source.")), step.effects)
    }

    @Test
    fun UI6_sourceChangeIsRefusedWhileStreaming() {
        for (state in listOf(idle.after(StreamPressed), live, live.after(StreamReconnecting(1)))) {
            val step = state.press(SourceChangeRequested("usb-1"))
            assertEquals(state, step.state)
            assertEquals(listOf(ShowMessage("Stop streaming before you change the source.")), step.effects)
        }
    }

    @Test
    fun UI6_sourceChangeIsRefusedWhileBothRun() {
        val step = both.press(SourceChangeRequested("usb-1"))
        assertEquals(listOf(ShowMessage("Stop recording and streaming before you change the source.")), step.effects)
    }

    @Test
    fun UI6_sourceChangeIsRefusedWhileARecordingIsStillStarting() {
        val step = idle.after(RecordPressed).press(SourceChangeRequested("usb-1"))
        assertIs<ShowMessage>(step.effects.single())
    }

    @Test
    fun UI6_sourceChangeIsRefusedUntilTheRecordingHasStopped() {
        val stopping = recording.after(RecordPressed)
        assertIs<ShowMessage>(stopping.press(SourceChangeRequested("usb-1")).effects.single())
        val stopped = stopping.after(RecordingStopped)
        assertEquals(listOf(ChangeSource("usb-1")), stopped.press(SourceChangeRequested("usb-1")).effects)
    }

    @Test
    fun UI6_sourceChangeIsAllowedWhenIdleOrAfterAFailure() {
        val step = idle.press(SourceChangeRequested("usb-1"))
        assertEquals("usb-1", step.state.selectedSourceId)
        assertEquals(listOf(ChangeSource("usb-1")), step.effects)

        val failed = idle.after(StreamPressed, StreamFailed(1, "No network."), RecordPressed, RecordingFailed("No space."))
        assertEquals(listOf(ChangeSource("usb-1")), failed.press(SourceChangeRequested("usb-1")).effects)
    }

    @Test
    fun UI6_choosingTheCurrentSourceAgainDoesNothing() {
        val step = idle.press(SourceChangeRequested("camera-0"))
        assertEquals(idle, step.state)
        assertEquals(emptyList(), step.effects)
    }

    // UI-8: the stream is always idle, connecting, live, reconnecting or failed, with a plain reason.

    @Test
    fun UI8_streamMovesThroughConnectingLiveAndReconnecting() {
        var state = idle.after(StreamPressed)
        assertEquals(StreamState.Connecting, state.stream)
        state = state.after(StreamLive(1))
        assertEquals(StreamState.Live, state.stream)
        state = state.after(StreamReconnecting(1))
        assertEquals(StreamState.Reconnecting, state.stream)
        state = state.after(StreamConnecting(1), StreamLive(1))
        assertEquals(StreamState.Live, state.stream)
    }

    @Test
    fun UI8_aFailureKeepsItsPlainWordsReason() {
        val failed = live.after(StreamFailed(1, "  The server refused the passphrase.  "))
        assertEquals(StreamState.Failed("The server refused the passphrase."), failed.stream)
        assertFalse(failed.isStreamActive)
    }

    @Test
    fun UI8_aBlankReasonIsReplacedWithPlainWords() {
        assertEquals(StreamState.Failed("The stream stopped, and no reason was given."), live.after(StreamFailed(1, " ")).stream)
        assertEquals(RecordState.Failed("The recording stopped, and no reason was given."), recording.after(RecordingFailed("")).record)
    }

    /** Every report the transport can send, for one attempt. */
    private fun reportsFor(attempt: Int): List<SessionAction> = listOf(
        StreamConnecting(attempt),
        StreamLive(attempt),
        StreamReconnecting(attempt),
        StreamFailed(attempt, "Connection reset."),
        StreamStopped(attempt),
    )

    @Test
    fun UI8_lateTransportReportsAfterStopAreIgnored() {
        val stopped = live.after(StreamPressed)
        for (late in reportsFor(1)) {
            assertEquals(StreamState.Idle, stopped.after(late).stream, "after $late")
        }
    }

    @Test
    fun UI8_everyStreamStartIsANewAttempt() {
        assertEquals(0, idle.streamAttempt)
        val first = idle.press(StreamPressed)
        assertEquals(1, first.state.streamAttempt)
        assertEquals(listOf(StartStream(profileId, 1)), first.effects)

        val second = first.state.after(StreamLive(1), StreamPressed).press(StreamPressed)
        assertEquals(2, second.state.streamAttempt)
        assertEquals(listOf(StartStream(profileId, 2)), second.effects)

        // Both starts a new attempt too, here after the second one failed.
        val third = second.state.after(StreamFailed(2, "No network.")).press(BothPressed)
        assertEquals(listOf(StartRecording, StartStream(profileId, 3)), third.effects)

        // Opening the editor starts nothing, so it uses up no attempt.
        assertEquals(0, noProfile.press(StreamPressed).state.streamAttempt)
        assertEquals(0, noProfile.press(BothPressed).state.streamAttempt)
    }

    @Test
    fun UI8_lateReportsFromAnEarlierAttemptAreIgnored() {
        // Stop a live stream and start it again. Then the first transport's reports arrive, late.
        val restarted = live.after(StreamPressed, StreamPressed)
        assertEquals(StreamState.Connecting, restarted.stream)
        assertEquals(2, restarted.streamAttempt)
        for (late in reportsFor(1) + reportsFor(3)) {
            val step = restarted.press(late)
            assertEquals(restarted, step.state, "after $late")
            assertEquals(emptyList(), step.effects, "after $late")
        }

        // Reports from the attempt that is running still count, and the old ones still can't stop it.
        val liveAgain = restarted.after(StreamStopped(1), StreamLive(2))
        assertEquals(StreamState.Live, liveAgain.stream)
        assertEquals(StreamState.Live, liveAgain.after(StreamFailed(1, "Connection reset."), StreamStopped(1)).stream)
        assertEquals(StreamState.Idle, liveAgain.after(StreamStopped(2)).stream)
    }

    @Test
    fun UI8_aFailureStaysOnScreenAfterTheTransportReportsItStopped() {
        val failed = live.after(StreamFailed(1, "The network went away."), StreamStopped(1), StreamLive(1))
        assertEquals(StreamState.Failed("The network went away."), failed.stream)
    }

    @Test
    fun UI8_aStreamTheTransportEndsByItselfGoesBackToIdle() {
        assertEquals(StreamState.Idle, live.after(StreamStopped(1)).stream)
    }

    @Test
    fun recordingFailureStaysOnScreenAfterTheRecorderReportsItStopped() {
        val failed = recording.after(RecordingFailed("The USB camera was unplugged."), RecordingStopped)
        assertEquals(RecordState.Failed("The USB camera was unplugged."), failed.record)
        assertEquals(listOf(StartRecording), failed.press(RecordPressed).effects)
    }

    @Test
    fun UI2_recordActionsNeverTouchTheStreamAndStreamActionsNeverTouchTheRecording() {
        val states = listOf(
            idle, noProfile, recording, live, both,
            idle.after(RecordPressed), recording.after(RecordPressed), live.after(StreamReconnecting(1)),
            both.after(RecordingFailed("The storage is full.")), both.after(StreamFailed(1, "No network.")),
            live.after(StreamPressed, StreamPressed), noProfile.after(RecordPressed, RecordingStarted),
        )
        val recordActions = listOf(RecordPressed, RecordingStarted, RecordingStopped, RecordingFailed("The storage is full."))
        val recordEffects = setOf(StartRecording, StopRecording)
        for (state in states) {
            for (action in recordActions) {
                val step = state.press(action)
                assertEquals(state.copy(record = step.state.record), step.state, "$action from $state")
                assertTrue(step.effects.all { it in recordEffects }, "$action from $state gave ${step.effects}")
            }
            val streamActions = listOf(StreamPressed) + reportsFor(state.streamAttempt) + reportsFor(state.streamAttempt + 1)
            for (action in streamActions) {
                val step = state.press(action)
                assertEquals(state.record, step.state.record, "$action from $state")
                assertTrue(step.effects.none { it in recordEffects }, "$action from $state gave ${step.effects}")
            }
        }
    }
}
