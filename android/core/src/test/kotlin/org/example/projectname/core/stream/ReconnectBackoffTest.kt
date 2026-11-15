package org.example.projectname.core.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class ReconnectBackoffTest {

    @Test
    fun OUT3_backoffFollowsOneTwoFiveThenTen() {
        assertEquals(listOf(1, 2, 5, 10).map { it.seconds }, (1..4).map(ReconnectBackoff::delayFor))
    }

    @Test
    fun OUT3_backoffStaysAtTenSecondsForever() {
        for (attempt in listOf(5, 6, 10, 100, 10_000, Int.MAX_VALUE)) {
            assertEquals(10.seconds, ReconnectBackoff.delayFor(attempt), "attempt $attempt")
        }
        assertEquals(10.seconds, ReconnectBackoff.steadyDelay)
    }

    @Test
    fun OUT3_attemptsCountFromOne() {
        assertFailsWith<IllegalArgumentException> { ReconnectBackoff.delayFor(0) }
        assertFailsWith<IllegalArgumentException> { ReconnectBackoff.delayFor(-1) }
    }
}
