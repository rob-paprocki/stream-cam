package org.example.projectname.core.log

import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The in-memory log behind the debug-log export. It keeps the newest [capacity] lines and is safe to use
 * from any thread.
 *
 * Every line is passed through [Redactor] as it arrives, so secrets and addresses never sit in the buffer
 * and never reach an export (spec OUT-6). Each line starts with an ISO-8601 UTC timestamp.
 */
class LogBuffer(
    private val capacity: Int = 2000,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(capacity > 0) { "capacity must be at least 1, got $capacity" }
    }

    private val lock = Any()
    private val lines = ArrayDeque<String>(minOf(capacity, 4096))
    private var dropped = 0L

    /** Adds one entry. A message with line breaks stays one entry, with its later lines indented. */
    fun append(tag: String, message: String) {
        val safeTag = Redactor.redact(tag).replace(LINE_BREAK, " ")
        val safeMessage = Redactor.redact(message).replace(LINE_BREAK, "\n    ")
        synchronized(lock) {
            val line = "${TIMESTAMP.format(clock.instant())} $safeTag: $safeMessage"
            if (lines.size == capacity) {
                lines.removeFirst()
                dropped++
            }
            lines.addLast(line)
        }
    }

    /** Returns the buffer as text, oldest line first, with a note at the top if older lines were dropped. */
    fun dump(): String {
        val (snapshot, droppedCount) = synchronized(lock) { lines.toList() to dropped }
        val builder = StringBuilder()
        if (droppedCount > 0) builder.append("($droppedCount earlier lines were dropped)\n")
        for (line in snapshot) builder.append(line).append('\n')
        return builder.toString()
    }

    private companion object {
        val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
        val LINE_BREAK = Regex("\r\n|\r|\n")
    }
}
