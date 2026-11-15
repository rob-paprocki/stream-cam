package org.example.projectname.capture

import java.io.File
import java.io.IOException

/**
 * The app's folder in the phone's shared Movies folder, where the user finds recordings without the app
 * (spec REC-3). [MediaStoreMovies] is the real one; the unit tests use a fake.
 */
interface SharedMovies {
    /**
     * Copies [file] into a new entry called [displayName]. The entry stays pending, hidden from other apps,
     * until the copy is complete. Returns where the entry is, for example
     * `Movies/PROJECT_NAME/PROJECT_NAME_20261114_093000.mp4`, with any number the phone added to the name.
     *
     * @throws IOException if the copy fails. Any half-made entry is removed.
     */
    fun publish(file: File, displayName: String, takenAtMillis: Long): String

    /**
     * Removes this app's entries in its Movies folder that are still pending: copies that were cut short
     * when the app was stopped. Returns how many there were.
     */
    fun removePending(): Int

    /** Whether this app's Movies folder has a finished entry of exactly [sizeBytes] whose name starts with [stem]. */
    fun hasFinished(stem: String, sizeBytes: Long): Boolean
}
