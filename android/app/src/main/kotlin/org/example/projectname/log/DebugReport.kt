package org.example.projectname.log

import org.example.projectname.core.log.Redactor

/**
 * Builds the text that "Export log" shares as a file: a short header about the phone and the app, then the
 * log. It has no Android types, so the unit tests can check it on any machine.
 */
object DebugReport {
    /**
     * The longest report, in characters. The report is shared as a file, so Android's 1 MB limit on what
     * one share passes between apps doesn't apply. This keeps the file small enough to attach to an issue
     * comment, with room for a full log buffer of ordinary lines and a crash log as long.
     */
    const val MAX_CHARS = 1_000_000

    private const val LOG_HEADING = "== Log ==\n"
    private const val CRASH_HEADING = "== Log from the session that crashed ==\n"

    /**
     * Returns the report. Each header line goes through [Redactor] whole, as `name: value`, so a value
     * that is secret only because of its name (`Wi-Fi password: ...`) is removed too, the same way the log
     * removes it (spec OUT-6); the log lines were redacted as they arrived. When the text is longer than
     * [maxChars], the oldest lines go first: the header and the newest lines of the current log always
     * stay, and a note says how many lines were cut.
     *
     * @param header name and value pairs, in order.
     * @param log the log buffer's dump, oldest line first.
     * @param previousCrash the log saved when the app last crashed, if there is one.
     */
    fun compose(
        title: String,
        header: List<Pair<String, String>>,
        log: String,
        previousCrash: String? = null,
        maxChars: Int = MAX_CHARS,
    ): String {
        val head = buildString {
            append(Redactor.redact(title)).append('\n')
            for ((name, value) in header) {
                append(Redactor.redact("$name: $value")).append('\n')
            }
            append('\n')
        }
        // The current log matters most, so it gets its share of the space first.
        val logRoom = (maxChars - head.length - LOG_HEADING.length).coerceAtLeast(0)
        val logPart = LOG_HEADING + fitTail(log, logRoom)
        val crashPart = if (previousCrash.isNullOrBlank()) {
            ""
        } else {
            val crashRoom = maxChars - head.length - logPart.length - CRASH_HEADING.length - 1
            if (crashRoom > 0) CRASH_HEADING + fitTail(Redactor.redact(previousCrash), crashRoom) + "\n" else ""
        }
        return (head + crashPart + logPart).take(maxChars)
    }

    /** Keeps the newest whole lines of [text] that fit in [maxChars], after a note about what was cut. */
    internal fun fitTail(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val lines = text.removeSuffix("\n").split('\n')
        val kept = ArrayDeque<String>()
        var used = NOTE_ALLOWANCE
        for (line in lines.asReversed()) {
            if (used + line.length + 1 > maxChars) break
            kept.addFirst(line)
            used += line.length + 1
        }
        val note = "(${lines.size - kept.size} older lines were cut to keep the file small)\n"
        return (note + kept.joinToString(separator = "") { it + "\n" }).take(maxChars)
    }

    /** Room kept for the note at the top of a cut log. */
    private const val NOTE_ALLOWANCE = 64
}
