package fr.tadikwa.wallhavenrotator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Exact-alarm entry point.
 *
 * The receiver never calls WallpaperManager while the display is non-interactive. A
 * due wake-up in deep sleep is converted into a non-wakeup elapsed alarm so Android
 * delivers it after a natural wake. The durable gate stays due the whole time.
 */
class RotationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != RotationAlarmScheduler.ACTION_ROTATION_ALARM) return

        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext).load()
        val nowElapsed = SystemClock.elapsedRealtime()
        val gateDueAt = AutoRotationGate.nextDueAt(appContext)
        val invocation = RotationAlarmScheduler.invocation(intent)
        val currentAlarm = RotationAlarmScheduler.currentSnapshot(appContext)
        val invocationIsCurrent = RotationAlarmScheduler.isCurrent(appContext, invocation)
        val interactive = BackgroundExecutionState.isInteractive(appContext)
        val decision = AlarmDispatchPolicy.decide(nowElapsed, gateDueAt, invocationIsCurrent)

        Diagnostics.log(
            appContext,
            "alarm.rotation.received",
            fields = mapOf(
                "clock" to "elapsedRealtime",
                "enabled" to settings.enabled,
                "interactive" to interactive,
                "nowElapsedMs" to nowElapsed,
                "gateDueElapsedMs" to gateDueAt,
                "invocationScheduleId" to invocation.scheduleId,
                "invocationTriggerElapsedMs" to invocation.triggerAtMs,
                "currentScheduleId" to currentAlarm.scheduleId,
                "currentTriggerElapsedMs" to currentAlarm.triggerAtMs,
                "currentPurpose" to currentAlarm.purpose,
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

        if (!invocationIsCurrent) {
            Diagnostics.log(
                appContext,
                "alarm.rotation.stale_ignored",
                fields = mapOf(
                    "scheduleId" to invocation.scheduleId,
                    "currentScheduleId" to currentAlarm.scheduleId,
                    "reason" to "stale_identity"
                )
            )
            return
        }

        // Critical HONOR/MagicOS rule learned from the overnight trace: setBitmap can
        // block for minutes while the screen is off. Do not enter WallpaperManager at
        // all in that state, and do not consume the cadence gate.
        if (!interactive) {
            val deferred = RotationAlarmScheduler.scheduleDeferredUntilAwake(appContext)
            Diagnostics.log(
                appContext,
                "alarm.rotation.deferred_sleeping",
                fields = mapOf(
                    "gateDueElapsedMs" to gateDueAt,
                    "gateOverdueMs" to (nowElapsed - gateDueAt).coerceAtLeast(0L),
                    "deferredScheduleId" to deferred.scheduleId,
                    "deferredTriggerElapsedMs" to deferred.triggerAtMs,
                    "mode" to deferred.mode
                )
            )
            return
        }

        when (decision.action) {
            AlarmDispatchAction.IGNORE_STALE -> Unit

            AlarmDispatchAction.RESCHEDULE_CURRENT -> {
                val alarm = RotationAlarmScheduler.schedule(appContext, gateDueAt)
                Diagnostics.log(
                    appContext,
                    "alarm.rotation.current_rescheduled",
                    fields = mapOf(
                        "remainingMs" to decision.remainingMs,
                        "scheduleId" to alarm.scheduleId,
                        "triggerElapsedMs" to alarm.triggerAtMs,
                        "mode" to alarm.mode
                    )
                )
            }

            AlarmDispatchAction.RUN_NOW -> {
                // Install a watchdog BEFORE any app-owned work. If WallpaperManager or
                // the process dies, the gate remains due and this future alarm retries.
                val watchdog = RotationAlarmScheduler.scheduleWatchdog(
                    appContext,
                    settings.intervalMinutes
                )
                Diagnostics.log(
                    appContext,
                    "alarm.rotation.watchdog_armed",
                    fields = mapOf(
                        "scheduleId" to watchdog.scheduleId,
                        "triggerElapsedMs" to watchdog.triggerAtMs,
                        "mode" to watchdog.mode
                    )
                )

                runCatching {
                    RotationForegroundService.startForAlarm(
                        context = appContext,
                        wakeScheduleId = invocation.scheduleId,
                        wakeReason = decision.reason
                    )
                }.onFailure { failure ->
                    Diagnostics.log(
                        appContext,
                        "alarm.rotation.service_start_failure",
                        level = "ERROR",
                        fields = mapOf("wakeReason" to decision.reason),
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
