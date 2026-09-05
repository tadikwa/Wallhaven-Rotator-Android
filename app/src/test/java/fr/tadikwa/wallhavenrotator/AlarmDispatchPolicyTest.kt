package fr.tadikwa.wallhavenrotator

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmDispatchPolicyTest {
    @Test
    fun dueCurrentAlarmRunsImmediately() {
        val decision = AlarmDispatchPolicy.decide(
            nowMs = 1_000_000L,
            gateDueAtMs = 999_000L,
            invocationIsCurrent = true
        )
        assertEquals(AlarmDispatchAction.RUN_NOW, decision.action)
    }

    @Test
    fun currentAlarmTwoMinutesEarlyRunsImmediately() {
        val decision = AlarmDispatchPolicy.decide(
            nowMs = 1_000_000L,
            gateDueAtMs = 1_120_000L,
            invocationIsCurrent = true
        )
        assertEquals(AlarmDispatchAction.RUN_NOW, decision.action)
        assertEquals("current_early_accepted", decision.reason)
    }

    @Test
    fun staleAlarmIsAlwaysIgnoredEvenNearDue() {
        val decision = AlarmDispatchPolicy.decide(
            nowMs = 1_000_000L,
            gateDueAtMs = 1_108_000L,
            invocationIsCurrent = false
        )
        assertEquals(AlarmDispatchAction.IGNORE_STALE, decision.action)
    }

    @Test
    fun implausiblyEarlyCurrentAlarmIsRescheduled() {
        val decision = AlarmDispatchPolicy.decide(
            nowMs = 1_000_000L,
            gateDueAtMs = 1_600_000L,
            invocationIsCurrent = true
        )
        assertEquals(AlarmDispatchAction.RESCHEDULE_CURRENT, decision.action)
    }
}
