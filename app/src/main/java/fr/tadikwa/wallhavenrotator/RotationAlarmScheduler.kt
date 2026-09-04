package fr.tadikwa.wallhavenrotator

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * System-owned wake-up trigger for automatic wallpaper rotation.
 *
 * A Handler or a long-running Service cannot wake a process that an OEM has killed.
 * AlarmManager keeps the deadline outside our process and can recreate it through the
 * explicit RotationAlarmReceiver. Exact alarms are preferred when the user grants
 * Android's "Alarms & reminders" special access; otherwise we fall back to an inexact
 * allow-while-idle alarm and keep WorkManager as a second fallback.
 */
object RotationAlarmScheduler {
    const val ACTION_ROTATION_ALARM =
        "fr.tadikwa.wallhavenrotator.action.AUTO_ROTATION_ALARM"

    private const val REQUEST_CODE = 2402
    private const val PREFS = "wallhaven_alarm_scheduler"
    private const val KEY_DUE_AT = "due_at_ms"
    private const val KEY_MODE = "mode"
    private const val KEY_SCHEDULED_AT = "scheduled_at_ms"

    data class ScheduleResult(
        val dueAtMs: Long,
        val mode: String,
        val exactAllowed: Boolean
    )

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.applicationContext.getSystemService(AlarmManager::class.java)
        return runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
    }

    fun schedule(context: Context, dueAtMs: Long): ScheduleResult {
        val appContext = context.applicationContext
        if (dueAtMs <= 0L) {
            cancel(appContext)
            return ScheduleResult(0L, "none", canScheduleExact(appContext))
        }

        val manager = appContext.getSystemService(AlarmManager::class.java)
        val operation = alarmPendingIntent(appContext)
        manager.cancel(operation)

        val exactAllowed = canScheduleExact(appContext)
        val triggerAt = dueAtMs.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        var mode = "inexact_allow_while_idle"
        if (exactAllowed) {
            val exactScheduled = runCatching {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    operation
                )
            }.isSuccess
            if (exactScheduled) {
                mode = "exact_allow_while_idle"
            } else {
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    operation
                )
                mode = "inexact_after_exact_failure"
            }
        } else {
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                operation
            )
        }

        prefs(appContext).edit()
            .putLong(KEY_DUE_AT, triggerAt)
            .putString(KEY_MODE, mode)
            .putLong(KEY_SCHEDULED_AT, System.currentTimeMillis())
            .apply()

        Diagnostics.log(
            appContext,
            "alarm.rotation.scheduled",
            fields = mapOf(
                "dueAtMs" to triggerAt,
                "mode" to mode,
                "exactAllowed" to exactAllowed
            )
        )
        return ScheduleResult(triggerAt, mode, exactAllowed)
    }

    fun scheduleFromGate(context: Context): ScheduleResult =
        schedule(context.applicationContext, AutoRotationGate.nextDueAt(context.applicationContext))

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(AlarmManager::class.java)
        runCatching { manager.cancel(alarmPendingIntent(appContext)) }
        prefs(appContext).edit().clear().apply()
        Diagnostics.log(appContext, "alarm.rotation.cancelled")
    }

    fun exactAlarmAccessIntent(context: Context): Intent {
        val packageUri = Uri.parse("package:${context.applicationContext.packageName}")
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun statusSummary(context: Context): String {
        val appContext = context.applicationContext
        val p = prefs(appContext)
        return buildString {
            append("exactAllowed=${canScheduleExact(appContext)}")
            append(",dueAtMs=${p.getLong(KEY_DUE_AT, 0L)}")
            append(",mode=${p.getString(KEY_MODE, "none") ?: "none"}")
            append(",scheduledAtMs=${p.getLong(KEY_SCHEDULED_AT, 0L)}")
        }
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RotationAlarmReceiver::class.java).apply {
            action = ACTION_ROTATION_ALARM
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
