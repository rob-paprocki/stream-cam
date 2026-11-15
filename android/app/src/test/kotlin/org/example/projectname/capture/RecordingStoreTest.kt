package org.example.projectname.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

class RecordingStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** The shared Movies folder, in memory. */
    private class FakeMovies : SharedMovies {
        class Entry(val name: String, val bytes: ByteArray, val takenAtMillis: Long, val pending: Boolean)

        val entries = ArrayList<Entry>()
        val calls = ArrayList<String>()
        var failNextPublish: IOException? = null

        override fun publish(file: File, displayName: String, takenAtMillis: Long): String {
            calls += "publish $displayName"
            failNextPublish?.let {
                failNextPublish = null
                throw it
            }
            entries += Entry(displayName, file.readBytes(), takenAtMillis, pending = false)
            return "Movies/PROJECT_NAME/$displayName"
        }

        override fun removePending(): Int {
            calls += "removePending"
            val pending = entries.count { it.pending }
            entries.removeAll { it.pending }
            return pending
        }

        override fun hasFinished(stem: String, sizeBytes: Long): Boolean =
            entries.any { !it.pending && it.name.startsWith(stem) && it.bytes.size.toLong() == sizeBytes }

        /** A copy that was cut short when the app was stopped. */
        fun addHalfCopy(name: String) {
            entries += Entry(name, ByteArray(10), 0, pending = true)
        }
    }

    private val name = "PROJECT_NAME_20261114_093005.mp4"
    private val startedAt = 1_794_648_605_000L
    private val movies = FakeMovies()
    private val logged = ArrayList<String>()
    private val now = Executor { it.run() }

    private fun folder(): File = temp.root
    private fun store(worker: Executor = now) = RecordingStore({ folder() }, movies, worker) { message, _ -> logged += message }

    /** Records [bytes] into a new working file, as the engine does, and returns it still unfinished. */
    private fun RecordingStore.record(bytes: ByteArray = Mp4Bytes.whole()): File =
        newRecordingFile(name, startedAt).apply { writeBytes(bytes) }

    /** Marks a whole recording finished, as the engine does once it has stopped. */
    private fun RecordingStore.finished(file: File): File = markFinished(file) ?: throw AssertionError("${file.name} wasn't marked finished")

    private fun saved(store: RecordingStore, file: File): Result<String> {
        var result: Result<String>? = null
        store.saveLater(file, startedAt) { result = it }
        return result!!
    }

    @Test
    fun REC4_twoRecordingsStartedInTheSameSecondGetTheirOwnWorkingFiles() {
        val store = store()
        val first = store.newRecordingFile(name, startedAt)
        val second = store.newRecordingFile(name, startedAt)
        assertNotEquals(first, second)
        assertTrue(first.isFile && second.isFile)
        // Both keep the REC-4 name for Movies.
        assertEquals(name, RecordingStore.displayNameOf(first))
        assertEquals(name, RecordingStore.displayNameOf(second))
    }

    @Test
    fun REC3_aFinishedRecordingIsSavedUnderItsRec4NameAndLeavesTheWorkingFolder() {
        val store = store()
        val bytes = Mp4Bytes.whole(mediaBytes = 2000)
        val file = store.finished(store.record(bytes))
        assertEquals(Result.success("Movies/PROJECT_NAME/$name"), saved(store, file))
        assertEquals(name, movies.entries.single().name)
        assertTrue(movies.entries.single().bytes.contentEquals(bytes))
        assertEquals(startedAt, movies.entries.single().takenAtMillis)
        assertEquals(emptyList<File>(), folder().listFiles()!!.toList())
    }

    @Test
    fun REC3_aDiscardedRecordingLeavesNothingBehind() {
        val store = store()
        store.discard(store.newRecordingFile(name, startedAt))
        assertEquals(emptyList<File>(), folder().listFiles()!!.toList())
    }

    @Test
    fun REC2_aRecordingWhoseCopyFailedIsKeptAndSavedAtTheNextStart() {
        val first = store()
        val file = first.finished(first.record())
        movies.failNextPublish = IOException("No space left on device")
        assertTrue(saved(first, file).isFailure)
        assertTrue("the finished file is kept", file.isFile)

        // The next start of the app.
        store().recoverLeftoversLater()
        assertEquals(listOf(name), movies.entries.map { it.name })
        assertFalse(file.exists())
    }

    @Test
    fun REC2_aFinishedRecordingWhoseCopyWasCutShortIsSavedAtTheNextStart() {
        // The app was stopped while the finished recording was being copied: a half copy is pending in
        // Movies and the finished file is still in the working folder.
        val before = store()
        val file = before.finished(before.record())
        movies.addHalfCopy(name)

        store().recoverLeftoversLater()
        assertEquals("the half copy is gone and a whole one is there", 1, movies.entries.size)
        val entry = movies.entries.single()
        assertFalse(entry.pending)
        assertEquals(name, entry.name)
        assertEquals(Mp4Bytes.whole().size, entry.bytes.size)
        assertEquals(startedAt, entry.takenAtMillis)
        assertFalse(file.exists())
    }

    @Test
    fun REC2_aRecordingThatWasStillBeingWrittenIsDeletedAtTheNextStartNotPublished() {
        val before = store()
        val unfinished = before.record()
        val size = unfinished.length()
        val empty = before.newRecordingFile(name, startedAt)

        store().recoverLeftoversLater()
        assertTrue(movies.entries.isEmpty())
        assertFalse("an unfinished recording has no index, so no player can open it: it goes", unfinished.exists())
        assertFalse("an empty one holds nothing, so it goes", empty.exists())
        assertTrue(logged.toString(), logged.any { it.contains("Deleted ${unfinished.name} ($size bytes)") })
        assertTrue(logged.toString(), logged.any { it.contains("Deleted ${empty.name} (0 bytes)") })
    }

    @Test
    fun REC2_aFinishedRecordingLeftFromBeforeIsStillSavedWhenUnfinishedOnesAreDeleted() {
        val before = store()
        val finished = before.finished(before.record(Mp4Bytes.whole(mediaBytes = 3000)))
        movies.failNextPublish = IOException("No space left on device")
        assertTrue(saved(before, finished).isFailure)
        val unfinished = before.record(Mp4Bytes.concat(Mp4Bytes.ftyp, Mp4Bytes.mdat()))
        val failed = before.record(Mp4Bytes.whole())
        assertNull(before.markFinished(failed, RecordingStore.Problem.Other("stopping it threw IllegalStateException")))

        // The next start of the app.
        store().recoverLeftoversLater()
        assertEquals(listOf(name), movies.entries.map { it.name })
        assertEquals(Mp4Bytes.whole(mediaBytes = 3000).size, movies.entries.single().bytes.size)
        assertFalse(finished.exists())
        assertFalse(unfinished.exists())
        assertFalse(failed.exists())
        assertEquals(emptyList<File>(), folder().listFiles()!!.toList())
    }

    @Test
    fun REC2_aRecordingAlreadyInMoviesIsNotSavedTwice() {
        // The app was stopped after the copy finished but before the working file was deleted.
        val before = store()
        val file = before.finished(before.record())
        movies.publish(file, name, startedAt)
        movies.calls.clear()

        store().recoverLeftoversLater()
        assertEquals(listOf("removePending"), movies.calls)
        assertEquals(1, movies.entries.size)
        assertFalse(file.exists())
    }

    @Test
    fun REC2_recoveryLeavesTheFilesOfThisRunAlone() {
        val store = store()
        val recording = store.record()
        val finishedButNotYetSaved = store.finished(store.record())
        val failedThisRun = store.record(Mp4Bytes.concat(Mp4Bytes.ftyp, Mp4Bytes.mdat()))
        assertNull(store.markFinished(failedThisRun))
        store.recoverLeftoversLater()
        assertTrue(movies.entries.isEmpty())
        assertTrue(recording.isFile && finishedButNotYetSaved.isFile)
        assertTrue("a recording that failed in this run waits for the next start", failedThisRun.isFile)
        assertTrue(saved(store, finishedButNotYetSaved).isSuccess)
    }

    @Test
    fun REC2_filesThatDontFollowTheWorkingNamesAreLeftAlone() {
        // For example a recording an earlier test build left behind, which may or may not be finished.
        val old = File(folder(), name).apply { writeBytes(ByteArray(100)) }
        val other = File(folder(), "notes.txt").apply { writeText("x") }
        store().recoverLeftoversLater()
        assertTrue(movies.entries.isEmpty())
        assertTrue(old.isFile && other.isFile)
    }

    @Test
    fun REC2_recoveryRunsOnTheWorkerBeforeAnyCopyQueuedAfterIt() {
        val queued = ArrayList<Runnable>()
        val store = store(Executor { queued += it })
        val file = store.finished(store.record())
        store.recoverLeftoversLater()
        store.saveLater(file, startedAt) {}
        assertTrue("nothing runs on the caller's thread", movies.calls.isEmpty())
        queued.forEach(Runnable::run)
        assertEquals(listOf("removePending", "publish $name"), movies.calls)
    }

    @Test
    fun REC2_noWorkingFolderMeansNothingToRecover() {
        val store = RecordingStore({ throw IOException("The phone's shared storage isn't available right now.") }, movies, now) { message, _ ->
            logged += message
        }
        store.recoverLeftoversLater()
        assertTrue(movies.entries.isEmpty())
        assertTrue(logged.isNotEmpty())
    }

    @Test
    fun REC3_aWholeMp4IsMarkedFinishedAndOnlyThenPublished() {
        val store = store()
        val recording = store.record()
        val finished = store.markFinished(recording)
        assertEquals(name.removeSuffix(".mp4") + ".$startedAt.mp4", finished?.name)
        assertFalse(recording.exists())
        assertTrue("a failed copy of it is tried again at the next start", store.retriesAtNextStart(finished!!))
        assertTrue(saved(store, finished).isSuccess)
    }

    @Test
    fun REC3_aRecordingWithNoMoovBoxIsNeverPublishedAndIsDeletedAtTheNextStart() {
        assertNeverPublishedAndDeletedAtTheNextStart(Mp4Bytes.concat(Mp4Bytes.ftyp, Mp4Bytes.mdat()), problem = null, logs = "moov")
    }

    @Test
    fun REC3_aTruncatedRecordingIsNeverPublishedAndIsDeletedAtTheNextStart() {
        val whole = Mp4Bytes.whole()
        assertNeverPublishedAndDeletedAtTheNextStart(whole.copyOf(whole.size - 10), problem = null, logs = "moov")
    }

    @Test
    fun REC3_aRecordingWhoseStopWentWrongIsNeverPublishedEvenIfItLooksWhole() {
        // The engine says the stop threw, so the file isn't trusted, however whole it looks.
        val problem = RecordingStore.Problem.Other("stopping it threw IllegalStateException")
        assertNeverPublishedAndDeletedAtTheNextStart(Mp4Bytes.whole(), problem, logs = "stopping it threw IllegalStateException")
    }

    @Test
    fun REC3_aRecordingWhoseWriteFailedIsDeletedAtOnceToFreeTheSpace() {
        // A full storage is the likely cause, and the file is holding the space.
        val store = store()
        val bytes = Mp4Bytes.whole()
        val recording = store.record(bytes)
        assertNull(store.markFinished(recording, RecordingStore.Problem.WriteFailed))
        assertFalse("it is deleted straight away", recording.exists())
        assertEquals(emptyList<File>(), folder().listFiles()!!.toList())
        val line = logged.single { it.contains(recording.name) }
        assertTrue(line, line.contains("${recording.name} (${bytes.size} bytes)"))
        assertTrue(line, line.contains("writing it failed"))
        assertTrue(line, line.contains("deleted"))
        assertFalse("the log gets its own words, not the banner's: $line", line.contains(".."))

        store().recoverLeftoversLater()
        assertTrue(movies.entries.isEmpty())
    }

    @Test
    fun REC3_aOneByteRecordingIsLoggedAsOneByte() {
        val store = store()
        val recording = store.record(ByteArray(1))
        assertNull(store.markFinished(recording))
        assertTrue(logged.toString(), logged.any { it.contains("${recording.name} (1 byte)") })
    }

    private fun assertNeverPublishedAndDeletedAtTheNextStart(bytes: ByteArray, problem: RecordingStore.Problem?, logs: String) {
        val store = store()
        val recording = store.record(bytes)
        assertNull(store.markFinished(recording, problem))
        assertTrue("it keeps its working name until the next start", recording.isFile)
        assertTrue(recording.name.endsWith(".part"))
        assertTrue(recording.readBytes().contentEquals(bytes))
        val line = logged.single { it.contains(recording.name) }
        assertTrue(line, line.contains("${recording.name} (${bytes.size} bytes)"))
        assertTrue(line, line.contains(logs))
        assertFalse(line, line.contains(".."))

        // The next start of the app deletes it, and still doesn't publish it.
        logged.clear()
        store().recoverLeftoversLater()
        assertTrue(movies.entries.isEmpty())
        assertFalse(recording.exists())
        assertTrue(logged.toString(), logged.any { it.contains("Deleted ${recording.name} (${bytes.size} bytes)") })
    }

    @Test
    fun REC2_aRecordingThatCouldntBeRenamedIsSavedButNotTriedAgainAtTheNextStart() {
        val store = store()
        val recording = store.record()
        // Something already has the finished name, so the rename fails.
        File(folder(), recording.name.removeSuffix(".part") + ".mp4").mkdir()
        val toSave = store.markFinished(recording)
        assertEquals(recording, toSave)
        assertFalse(store.retriesAtNextStart(recording))

        movies.failNextPublish = IOException("No space left on device")
        assertTrue(saved(store, recording).isFailure)
        store().recoverLeftoversLater()
        assertTrue("the start-up check never copies a file that isn't marked finished", movies.entries.isEmpty())
        assertFalse("it deletes it instead", recording.exists())
    }
}
