package org.example.projectname.core.recording

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The start of every recording's file name. It changes when the app gets its real name. */
const val APP_FILE_PREFIX = "PROJECT_NAME"

/** Names for recording files (spec REC-4). */
object RecordingNames {
    // Locale.ROOT keeps the digits ASCII whatever language the phone uses.
    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss", Locale.ROOT)

    /** Returns `PROJECT_NAME_YYYYMMDD_HHMMSS.mp4` for a recording that starts at [localTime] (local time). */
    fun fileName(localTime: LocalDateTime): String = "${APP_FILE_PREFIX}_${stamp.format(localTime)}.mp4"
}
