package org.example.projectname.capture

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Where recordings live. A recording is written to a working file in the app's own folder first, then
 * copied into the shared Movies folder ([movies]), where the user can reach it without the app (spec
 * REC-3).
 *
 * A working file is called `<name>.<start>.part` while it is being written. `<name>` is the recording's
 * REC-4 name without `.mp4`, and `<start>` is its start time in milliseconds, with a number added if
 * needed, so two recordings started in the same second never share a file. When the recording has stopped
 * without an error and the file passes [Mp4Check], [markFinished] renames it to `<name>.<start>.mp4`, and
 * only a file named that way is ever copied. So when the app starts, a `.mp4` left in the folder is a whole
 * recording whose copy was cut short or failed, and [recoverLeftoversLater] copies it. A `.part` left there
 * was still being written when the app stopped, or went wrong. It may not play: a file MediaMuxer never
 * finished has no `moov` box, the index a player needs. So it is never copied, and the start-up check
 * deletes it, so that it doesn't hold space in a folder the user can't reach.
 *
 * Copies and the start-up check run one at a time on [worker], in the order they are asked for. The app has
 * one store (App.recordings) and queues the check first, so the check never meets a copy in progress. Files
 * that this run of the app is still writing or saving are left out of the check. It has no Android types,
 * so the unit tests can check it on any machine.
 *
 * TODO(REC-2): a recording that was still being written when the app stopped is lost: the next start
 *  deletes it. Milestone M1b replaces the single MP4 with crash-safe fragmented MP4 written straight to the
 *  shared folder.
 *
 * @param workingFolder the app's own folder for recordings. It throws [IOException] if there is none.
 * @param log where to write debug-log lines, with the error if there was one.
 */
class RecordingStore(
    private val workingFolder: () -> File,
    private val movies: SharedMovies,
    private val worker: Executor,
    private val log: (message: String, error: Throwable?) -> Unit = { _, _ -> },
) {
    /**
     * Names of the working files this run of the app is writing or saving, or has given up on but leaves for
     * the next start to delete. The start-up check leaves them alone.
     */
    private val inUse: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Makes a new, empty working file for a recording called [displayName] (its REC-4 name) that starts at
     * [startedAtMillis], and returns it.
     *
     * @throws IOException if there's no working folder or the file can't be made.
     */
    fun newRecordingFile(displayName: String, startedAtMillis: Long): File {
        val folder = workingFolder()
        val stem = displayName.removeSuffix(FINISHED)
        for (n in 0..MAX_TRIES) {
            val token = if (n == 0) "$startedAtMillis" else "$startedAtMillis-$n"
            val file = File(folder, "$stem.$token$WRITING")
            // Claimed before it exists, so the start-up check never sees it unclaimed. createNewFile is atomic.
            if (!inUse.add(file.name)) continue
            if (file.createNewFile()) return file
            inUse.remove(file.name)
        }
        throw IOException("The app couldn't make a new recording file.")
    }

    /**
     * The engine has stopped writing [file], which holds at least one frame. If [problem] is null and the
     * file passes [Mp4Check], this renames it so that a later start of the app knows it is whole, and
     * returns the file to pass to [saveLater]: the renamed file, or [file] itself if the rename fails.
     *
     * Otherwise the recording can't be trusted to play, so it is never copied to Movies, the reason is
     * logged with the file's name and size, and this returns null. After a write error the file is deleted
     * at once: a full storage is the likely cause, and the file is holding the space. Any other file keeps
     * its `.part` name until the next start of the app deletes it.
     *
     * @param problem what the engine saw go wrong, or null if nothing did.
     */
    fun markFinished(file: File, problem: Problem? = null): File? {
        val notWhole = problem?.forLog ?: Mp4Check.problemWith(file)
        if (notWhole != null) {
            val notCopied = "${file.name} (${byteCount(file.length())}) isn't a whole recording: $notWhole. " +
                "It isn't copied to Movies"
            when {
                problem !is Problem.WriteFailed -> log("$notCopied, and the next start of the app deletes it.", null)
                file.delete() -> log("$notCopied, and it was deleted at once to free the space it held.", null)
                else -> log("$notCopied. It couldn't be deleted now, so the next start of the app tries again.", null)
            }
            // A file kept for the next start stays claimed, so this run's start-up check leaves it alone.
            if (!file.exists()) inUse.remove(file.name)
            return null
        }
        val name = parse(file.name)
        if (name == null || name.finished) return file
        val target = File(file.parentFile, "${name.stem}.${name.token}$FINISHED")
        inUse.add(target.name)
        if (file.renameTo(target)) {
            inUse.remove(file.name)
            return target
        }
        inUse.remove(target.name)
        log(
            "Couldn't mark ${file.name} as finished. It is copied to Movies all the same, but if that fails, it " +
                "isn't tried again and the next start of the app deletes it.",
            null,
        )
        return file
    }

    /**
     * Whether the start-up check copies [file] if it is still in the working folder then, for example
     * because its copy failed: true once [markFinished] has renamed it. The check runs when the app's
     * process starts, not each time its screen opens.
     */
    fun retriesAtNextStart(file: File): Boolean = parse(file.name)?.finished == true

    /** Deletes a working file that won't be saved, such as a recording stopped before its first frame. */
    fun discard(file: File) {
        if (file.exists() && !file.delete()) log("Couldn't delete ${file.name}", null)
        inUse.remove(file.name)
    }

    /**
     * Copies [file], a finished working file, to Movies under its REC-4 name, then deletes it. This runs on
     * the worker, after everything queued before it, and then calls [done] on the worker with where the
     * recording went, or the error. When the copy fails, the file stays, and the next start of the app tries
     * again if [retriesAtNextStart] says so.
     */
    fun saveLater(file: File, takenAtMillis: Long, done: (Result<String>) -> Unit) {
        inUse.add(file.name)
        worker.execute {
            val result = try {
                Result.success(save(file, displayNameOf(file), takenAtMillis))
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                inUse.remove(file.name)
            }
            done(result)
        }
    }

    /**
     * Queues the check for recordings left by an earlier run of the app. Call it once, when the app starts,
     * before anything is recorded. It removes this app's half-made copies from Movies, then copies every
     * finished recording still in the working folder, and deletes every unfinished one.
     */
    fun recoverLeftoversLater() {
        worker.execute { recoverLeftovers() }
    }

    private fun save(file: File, displayName: String, takenAtMillis: Long): String {
        val where = movies.publish(file, displayName, takenAtMillis)
        if (!file.delete()) log("Copied ${file.name} to $where, but couldn't delete the working copy", null)
        return where
    }

    private fun recoverLeftovers() {
        val files = try {
            workingFolder().listFiles()?.toList().orEmpty()
        } catch (e: IOException) {
            log("Couldn't look for recordings left from before", e)
            return
        }
        // A half-made copy's recording is still in the working folder, so it is copied again below.
        try {
            val removed = movies.removePending()
            if (removed > 0) log("Removed $removed half-made copies from Movies", null)
        } catch (e: Exception) {
            log("Couldn't look for half-made copies in Movies", e)
        }
        for (file in files) {
            if (!file.isFile || file.name in inUse) continue
            val name = parse(file.name) ?: continue
            if (name.finished) recover(file, name) else deleteUnfinished(file)
        }
    }

    /** Deletes [file], a working file an earlier run of the app never marked finished, and logs its name and size. */
    private fun deleteUnfinished(file: File) {
        val what = "${file.name} (${byteCount(file.length())}), a recording left from before that was never marked " +
            "finished: it was still being written when the app stopped, or it went wrong"
        if (file.delete()) {
            log("Deleted $what. It may not play, so it isn't copied to Movies.", null)
        } else {
            log("Couldn't delete $what. The next start of the app tries again.", null)
        }
    }

    private fun recover(file: File, name: WorkingName) {
        try {
            // The app may have stopped after the copy finished but before the working file was deleted.
            if (movies.hasFinished(name.stem, file.length())) {
                log("${file.name} is already in Movies; deleting the working copy", null)
                discard(file)
                return
            }
            val where = save(file, name.stem + FINISHED, name.startedAtMillis)
            log("Copied ${file.name}, a recording left from before, to $where", null)
        } catch (e: Exception) {
            log("Couldn't copy ${file.name}, a recording left from before, to Movies; it's kept for the next start", e)
        }
    }

    /** What the engine saw go wrong with a recording. The store doesn't trust such a file, however whole it looks. */
    sealed class Problem(val forLog: String) {
        /**
         * Writing the file failed. A full storage is the likely cause, and the file is holding the space, so
         * [markFinished] deletes it at once.
         */
        data object WriteFailed : Problem("writing it failed")

        /** Anything else, such as stopping the recorder threw. [forLog] says what, in a few words. */
        class Other(forLog: String) : Problem(forLog)
    }

    /** A working file's name, taken apart. [token] is the start time with any number added. */
    private class WorkingName(val stem: String, val token: String, val startedAtMillis: Long, val finished: Boolean)

    companion object {
        private const val WRITING = ".part"
        private const val FINISHED = ".mp4"
        private const val MAX_TRIES = 1000
        private val WORKING_NAME = Regex("""(.+)\.((\d{1,18})(?:-\d{1,4})?)(\.part|\.mp4)""")

        private fun parse(fileName: String): WorkingName? {
            val match = WORKING_NAME.matchEntire(fileName) ?: return null
            val (stem, token, millis, suffix) = match.destructured
            return WorkingName(stem, token, millis.toLong(), finished = suffix == FINISHED)
        }

        /** The name a working file gets in Movies: its REC-4 name, such as `PROJECT_NAME_20261114_093005.mp4`. */
        fun displayNameOf(file: File): String = parse(file.name)?.let { it.stem + FINISHED } ?: file.name
    }
}
