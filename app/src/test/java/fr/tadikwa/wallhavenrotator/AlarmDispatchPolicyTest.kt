package fr.tadikwa.wallhavenrotator

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmDispatchPolicyTest {
    @Test
    fun dueAlarmRunsImmediately() {
        val decision = AlarmDispatchPolicy.decide(
            nowMs = 1_000_000L,
            gateDueAtMs = 999_000L,
            invocationIsCurrent = true
        )
        assertEquals(AlarmDispatchAction.RUN_NOW, decision.action)
    }

    @Test
    fun staleAlarmShortlyBeforeDueIsReusedInsteadOfRearmed() {
        val decision = AlarmDispatchPolicy.decide(
            nowMs = 1_000_000L,
            gateDueAtMs = 1_108_000L,
            invocationIsCurrent = false
        )
        assertEquals(AlarmDispatchAction.WAIT_THEN_RUN, decision.action)
        assertEquals(108_000L, decision.remainingMs)
    }

    @Test
    fun farStaleAlarmIsIgnored() {
        val decision = AlarmDispatchPolicy.decide(
            nowMs = 1_000_000L,
            gateDueAtMs = 1_600_000L,
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
