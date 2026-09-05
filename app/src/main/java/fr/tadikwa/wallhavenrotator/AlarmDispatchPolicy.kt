package fr.tadikwa.wallhavenrotator

enum class AlarmDispatchAction {
    RUN_NOW,
    IGNORE_STALE,
    RESCHEDULE_CURRENT
}

data class AlarmDispatchDecision(
    val action: AlarmDispatchAction,
    val remainingMs: Long,
    val reason: String
)

/** Pure identity/timing policy. Sleeping-device deferral is handled before this. */
object AlarmDispatchPolicy {
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
        if (!invocationIsCurrent) {
            return AlarmDispatchDecision(
                AlarmDispatchAction.IGNORE_STALE,
                remaining,
                "stale_identity"
            )
        }

        if (remaining <= AutoRotationPolicy.ALARM_EARLY_TOLERANCE_MS) {
            return AlarmDispatchDecision(
                AlarmDispatchAction.RUN_NOW,
                remaining,
                if (remaining > 0L) "current_early_accepted" else "current_due"
            )
        }

        return AlarmDispatchDecision(
            AlarmDispatchAction.RESCHEDULE_CURRENT,
            remaining,
            "current_implausibly_early"
        )
    }
}
