package fr.tadikwa.wallhavenrotator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Explicit AlarmManager entry point. The system can instantiate this receiver even
 * after HONOR/MagicOS has killed the app process.
 */
class RotationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != RotationAlarmScheduler.ACTION_ROTATION_ALARM) return

        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext).load()
        val now = System.currentTimeMillis()
        val gateDueAt = AutoRotationGate.nextDueAt(appContext)
        val invocation = RotationAlarmScheduler.invocation(intent)
        val currentAlarm = RotationAlarmScheduler.currentSnapshot(appContext)
        val invocationIsCurrent = RotationAlarmScheduler.isCurrent(appContext, invocation)
        val decision = AlarmDispatchPolicy.decide(now, gateDueAt, invocationIsCurrent)

        Diagnostics.log(
            appContext,
            "alarm.rotation.received",
            fields = mapOf(
                "enabled" to settings.enabled,
                "nowMs" to now,
                "gateDueAtMs" to gateDueAt,
                "invocationScheduleId" to invocation.scheduleId,
                "invocationDueAtMs" to invocation.scheduledDueAtMs,
                "currentScheduleId" to currentAlarm.scheduleId,
                "currentAlarmDueAtMs" to currentAlarm.dueAtMs,
                "invocationIsCurrent" to invocationIsCurrent,
                "dispatchAction" to decision.action.name,
                "dispatchReason" to decision.reason,
                "remainingMs" to decision.remainingMs,
                "exactAllowed" to RotationAlarmScheduler.canScheduleExact(appContext)
            )
        )

        if (!settings.enabled) {
            RotationAlarmScheduler.cancel(appContext)
            return
        }

        when (decision.action) {
            AlarmDispatchAction.IGNORE_STALE -> {
                Diagnostics.log(
                    appContext,
                    "alarm.rotation.stale_ignored",
                    fields = mapOf(
                        "scheduleId" to invocation.scheduleId,
                        "currentScheduleId" to currentAlarm.scheduleId,
                        "remainingMs" to decision.remainingMs,
                        "reason" to decision.reason
                    )
                )
                return
            }

            AlarmDispatchAction.RESCHEDULE_CURRENT -> {
                val alarm = RotationAlarmScheduler.schedule(appContext, gateDueAt)
                Diagnostics.log(
                    appContext,
                    "alarm.rotation.current_rescheduled",
                    fields = mapOf(
                        "remainingMs" to decision.remainingMs,
                        "scheduleId" to alarm.scheduleId,
                        "dueAtMs" to alarm.dueAtMs,
                        "mode" to alarm.mode
                    )
                )
                return
            }

            AlarmDispatchAction.RUN_NOW,
            AlarmDispatchAction.WAIT_THEN_RUN -> {
                runCatching {
                    RotationForegroundService.startForAlarm(
                        context = appContext,
                        targetDueAtMs = gateDueAt,
                        wakeScheduleId = currentAlarm.scheduleId,
                        wakeReason = decision.reason
                    )
                }.onFailure { failure ->
                    Diagnostics.log(
                        appContext,
                        "alarm.rotation.service_start_failure",
                        level = "ERROR",
                        fields = mapOf(
                            "targetDueAtMs" to gateDueAt,
                            "wakeReason" to decision.reason
                        ),
                        throwable = failure
                    )
                    WorkManager.getInstance(appContext).enqueueUniqueWork(
                        "wallhaven_alarm_fallback",
                        ExistingWorkPolicy.REPLACE,
                        OneTimeWorkRequestBuilder<RotationWorker>().build()
                    )
                }
            }
        }
    }
}
