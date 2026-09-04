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
        val dueAt = AutoRotationGate.nextDueAt(appContext)

        Diagnostics.log(
            appContext,
            "alarm.rotation.received",
            fields = mapOf(
                "enabled" to settings.enabled,
                "nowMs" to now,
                "dueAtMs" to dueAt,
                "exactAllowed" to RotationAlarmScheduler.canScheduleExact(appContext)
            )
        )

        if (!settings.enabled) {
            RotationAlarmScheduler.cancel(appContext)
            return
        }

        runCatching {
            RotationForegroundService.startForAlarm(appContext)
        }.onFailure { failure ->
            // Exact alarms are exempt from background FGS start restrictions, but keep
            // a durable WorkManager escape hatch for vendor-specific failures.
            Diagnostics.log(
                appContext,
                "alarm.rotation.service_start_failure",
                level = "ERROR",
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
