package org.example.projectname.core.stream

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long an outbound stream waits before each reconnect attempt (spec OUT-3): 1, 2 and 5 seconds,
 * then every 10 seconds until the user stops the stream.
 */
object ReconnectBackoff {
    private val firstDelaysSeconds = intArrayOf(1, 2, 5)

    /** The wait once the first few attempts have failed. */
    val steadyDelay: Duration = 10.seconds

    /** The wait before reconnect [attempt], counting from 1 for the first attempt after a drop. */
    fun delayFor(attempt: Int): Duration {
        require(attempt >= 1) { "attempt counts from 1, got $attempt" }
        return if (attempt <= firstDelaysSeconds.size) firstDelaysSeconds[attempt - 1].seconds else steadyDelay
    }
}
