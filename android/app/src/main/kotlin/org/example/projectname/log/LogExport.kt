package org.example.projectname.log

import org.example.projectname.core.recording.APP_FILE_PREFIX
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes the debug report to the file that "Export log" shares. The file goes in a folder of its own in the
 * app's cache, the only folder the app's FileProvider hands out (res/xml/log_share_paths.xml), and each
 * export replaces the one before, so that folder never holds more than one log. It has no Android types,
 * so the unit tests can check it on any machine.
 */
object LogExport {
    /** The folder in the app's cache for the shared log. res/xml/log_share_paths.xml names it as well. */
    const val FOLDER = "shared-logs"

    // UTC, like the timestamps in the log. Locale.ROOT keeps the digits ASCII in any language.
    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    private const val PARTIAL = ".partial"

    /** The shared-log folder inside [cacheDir], the app's cache folder. */
    fun folderIn(cacheDir: File): File = File(cacheDir, FOLDER)

    /** `PROJECT_NAME-log-20261114T093005Z.txt`: the app and the time in UTC, and nothing about the person. */
    fun fileName(now: Instant): String = "$APP_FILE_PREFIX-log-${STAMP.format(now)}.txt"

    /**
     * Writes [report] as UTF-8 to a new file in [folder], which is made if it is missing, then deletes every
     * other file there. The text goes to a temporary name first and is renamed once it is all written, so
     * the share never sees half a log.
     *
     * @throws IOException if the file can't be written.
     */
    fun write(folder: File, report: String, now: Instant): File {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("The folder for the shared log couldn't be made.")
        val target = File(folder, fileName(now))
        val partial = File(folder, target.name + PARTIAL)
        try {
            partial.writeText(report, Charsets.UTF_8)
            // rename() replaces an existing file on Android. Elsewhere it may not, so try once more without it.
            if (!partial.renameTo(target) && !(target.delete() && partial.renameTo(target))) {
                throw IOException("The shared log couldn't be saved.")
            }
        } finally {
            partial.delete()
        }
        folder.listFiles()?.forEach { if (it != target) it.delete() }
        return target
    }
}
