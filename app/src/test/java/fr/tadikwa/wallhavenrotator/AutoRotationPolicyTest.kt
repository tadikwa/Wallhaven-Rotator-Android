package fr.tadikwa.wallhavenrotator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoRotationPolicyTest {
    @Test
    fun minimumIntervalIsFifteenMinutes() {
        assertEquals(15L * 60_000L, AutoRotationPolicy.intervalMillis(1L))
        assertEquals(30L * 60_000L, AutoRotationPolicy.intervalMillis(30L))
    }

    @Test
    fun dueGateDoesNotReplayMissedIntervals() {
        val start = 1_000_000L
        val due = AutoRotationPolicy.nextDueAt(start, 15L)
        assertFalse(AutoRotationPolicy.isDue(due - 1L, due))
        assertTrue(AutoRotationPolicy.isDue(due, due))
        assertTrue(AutoRotationPolicy.isDue(due + 60L * 60_000L, due))
    }
}
