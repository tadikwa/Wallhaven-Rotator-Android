package fr.tadikwa.wallhavenrotator

enum class AlarmDispatchAction {
    RUN_NOW,
    WAIT_THEN_RUN,
    IGNORE_STALE,
    RESCHEDULE_CURRENT
}

data class AlarmDispatchDecision(
    val action: AlarmDispatchAction,
    val remainingMs: Long,
    val reason: String
)

/**
 * Pure decision policy for an AlarmManager broadcast.
 *
 * Android limits allow-while-idle alarm dispatch frequency. If an OEM/stale alarm
 * wakes us shortly before the real due time, re-arming another allow-while-idle alarm
 * can therefore miss the actual deadline. Alpha.13 reuses that wake-up for a short,
 * bounded foreground wait instead.
 */
object AlarmDispatchPolicy {
    const val MAX_EARLY_WAIT_MS = 4L * 60L * 1_000L

    fun decide(
        nowMs: Long,
        gateDueAtMs: Long,
        invocationIsCurrent: Boolean
    ): AlarmDispatchDecision {
        if (gateDueAtMs <= 0L) {
            return AlarmDispatchDecision(
                AlarmDispatchAction.IGNORE_STALE,
                0L,
                "no_gate_due"
            )
        }

        val remaining = gateDueAtMs - nowMs
        if (remaining <= 0L) {
            return AlarmDispatchDecision(
                AlarmDispatchAction.RUN_NOW,
                remaining,
                if (invocationIsCurrent) "current_due" else "stale_but_gate_due"
            )
        }

        if (remaining <= MAX_EARLY_WAIT_MS) {
            return AlarmDispatchDecision(
                AlarmDispatchAction.WAIT_THEN_RUN,
                remaining,
                if (invocationIsCurrent) "current_early_near_due" else "stale_early_near_due"
            )
        }

        return if (invocationIsCurrent) {
            AlarmDispatchDecision(
                AlarmDispatchAction.RESCHEDULE_CURRENT,
                remaining,
                "current_too_early"
            )
        } else {
            AlarmDispatchDecision(
                AlarmDispatchAction.IGNORE_STALE,
                remaining,
                "stale_far_from_due"
            )
        }
    }
}
