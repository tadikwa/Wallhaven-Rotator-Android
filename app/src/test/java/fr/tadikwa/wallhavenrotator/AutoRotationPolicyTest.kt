package fr.tadikwa.wallhavenrotator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRotationPolicyTest {
    @Test
    fun minimumIntervalIsFifteenMinutes() {
        assertEquals(15L * 60_000L, AutoRotationPolicy.intervalMillis(1L))
        assertEquals(30L * 60_000L, AutoRotationPolicy.intervalMillis(30L))
    }

    @Test
    fun regularDueGateDoesNotRunEarly() {
        val start = 1_000_000L
        val due = AutoRotationPolicy.nextDueAt(start, 15L)
        assertFalse(AutoRotationPolicy.isDue(due - 1L, due))
        assertTrue(AutoRotationPolicy.isDue(due, due))
    }

    @Test
    fun alarmGateAcceptsObservedTwoMinuteHonorEarlyDelivery() {
        val due = 2_000_000L
        assertTrue(AutoRotationPolicy.isDueForAlarm(due - 120_000L, due))
        assertFalse(AutoRotationPolicy.isDueForAlarm(due - 4L * 60_000L, due))
    }

    @Test
    fun sameBootDeadlineSanityDetectsOldElapsedRealtimeAfterReboot() {
        val nowAfterBoot = 120_000L
        assertFalse(
            AutoRotationPolicy.looksLikeSameBootDeadline(
                nowMs = nowAfterBoot,
                dueAtMs = 9_000_000L,
                intervalMinutes = 15L
            )
        )
    }
}
