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
 * Alpha.13 gives every scheduled deadline a persisted identity. A stale AlarmManager
 * delivery can therefore never be mistaken for the currently active deadline. This is
 * important on the tested HONOR/MagicOS device, where an old alarm was observed firing
 * shortly before the newer gate deadline.
 */
object RotationAlarmScheduler {
    const val ACTION_ROTATION_ALARM =
        "fr.tadikwa.wallhavenrotator.action.AUTO_ROTATION_ALARM"

    private const val REQUEST_CODE = 2402
    private const val PREFS = "wallhaven_alarm_scheduler"
    private const val KEY_DUE_AT = "due_at_ms"
    private const val KEY_MODE = "mode"
    private const val KEY_SCHEDULED_AT = "scheduled_at_ms"
    private const val KEY_SCHEDULE_ID = "schedule_id"
    private const val KEY_SEQUENCE = "sequence"

    const val EXTRA_SCHEDULE_ID = "wallhaven_alarm_schedule_id"
    const val EXTRA_SCHEDULED_DUE_AT = "wallhaven_alarm_scheduled_due_at"

    data class ScheduleResult(
        val dueAtMs: Long,
        val mode: String,
        val exactAllowed: Boolean,
        val scheduleId: Long
    )

    data class ScheduleSnapshot(
        val scheduleId: Long,
        val dueAtMs: Long,
        val mode: String,
        val scheduledAtMs: Long
    ) {
        val isPresent: Boolean get() = scheduleId > 0L && dueAtMs > 0L
    }

    data class AlarmInvocation(
        val scheduleId: Long,
        val scheduledDueAtMs: Long
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
            return ScheduleResult(0L, "none", canScheduleExact(appContext), 0L)
        }

        val manager = appContext.getSystemService(AlarmManager::class.java)
        val p = prefs(appContext)
        val now = System.currentTimeMillis()
        val triggerAt = dueAtMs.coerceAtLeast(now + 1_000L)
        val previous = currentSnapshot(appContext)

        // Remove the unversioned alpha.11 PendingIntent on every alpha.13 schedule.
        // This is cheap and guarantees a one-way migration away from stale legacy alarms.
        cancelLegacyAlarm(manager, appContext)

        val reuseIdentity = previous.isPresent && previous.dueAtMs == triggerAt
        val scheduleId = if (reuseIdentity) {
            previous.scheduleId
        } else {
            if (previous.isPresent) cancelVersionedAlarm(manager, appContext, previous)
            val lastSequence = p.getLong(KEY_SEQUENCE, 0L)
            maxOf(now, lastSequence + 1L)
        }

        val operation = alarmPendingIntent(appContext, scheduleId, triggerAt)
        // Re-registering the same identity is intentional: it repairs a missing system
        // alarm without creating a second logical deadline.
        manager.cancel(operation)

        val exactAllowed = canScheduleExact(appContext)
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

        p.edit()
            .putLong(KEY_DUE_AT, triggerAt)
            .putString(KEY_MODE, mode)
            .putLong(KEY_SCHEDULED_AT, now)
            .putLong(KEY_SCHEDULE_ID, scheduleId)
            .putLong(KEY_SEQUENCE, maxOf(scheduleId, p.getLong(KEY_SEQUENCE, 0L)))
            .apply()

        Diagnostics.log(
            appContext,
            "alarm.rotation.scheduled",
            fields = mapOf(
                "scheduleId" to scheduleId,
                "dueAtMs" to triggerAt,
                "mode" to mode,
                "exactAllowed" to exactAllowed,
                "reusedIdentity" to reuseIdentity,
                "legacyCancelled" to true
            )
        )
        return ScheduleResult(triggerAt, mode, exactAllowed, scheduleId)
    }

    fun scheduleFromGate(context: Context): ScheduleResult =
        schedule(context.applicationContext, AutoRotationGate.nextDueAt(context.applicationContext))

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(AlarmManager::class.java)
        val current = currentSnapshot(appContext)
        if (current.isPresent) cancelVersionedAlarm(manager, appContext, current)
        cancelLegacyAlarm(manager, appContext)

        // Keep the monotonic sequence so a future alarm identity cannot collide with an
        // old PendingIntent that survived vendor/system bookkeeping.
        prefs(appContext).edit()
            .remove(KEY_DUE_AT)
            .remove(KEY_MODE)
            .remove(KEY_SCHEDULED_AT)
            .remove(KEY_SCHEDULE_ID)
            .apply()
        Diagnostics.log(appContext, "alarm.rotation.cancelled")
    }

    fun exactAlarmAccessIntent(context: Context): Intent {
        val packageUri = Uri.parse("package:${context.applicationContext.packageName}")
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun currentSnapshot(context: Context): ScheduleSnapshot {
        val p = prefs(context.applicationContext)
        return ScheduleSnapshot(
            scheduleId = p.getLong(KEY_SCHEDULE_ID, 0L),
            dueAtMs = p.getLong(KEY_DUE_AT, 0L),
            mode = p.getString(KEY_MODE, "none") ?: "none",
            scheduledAtMs = p.getLong(KEY_SCHEDULED_AT, 0L)
        )
    }

    fun invocation(intent: Intent?): AlarmInvocation = AlarmInvocation(
        scheduleId = intent?.getLongExtra(EXTRA_SCHEDULE_ID, 0L) ?: 0L,
        scheduledDueAtMs = intent?.getLongExtra(EXTRA_SCHEDULED_DUE_AT, 0L) ?: 0L
    )

    fun isCurrent(context: Context, invocation: AlarmInvocation): Boolean {
        val current = currentSnapshot(context.applicationContext)
        return current.isPresent &&
            invocation.scheduleId == current.scheduleId &&
            invocation.scheduledDueAtMs == current.dueAtMs
    }

    fun statusSummary(context: Context): String {
        val appContext = context.applicationContext
        val current = currentSnapshot(appContext)
        return buildString {
            append("exactAllowed=${canScheduleExact(appContext)}")
            append(",scheduleId=${current.scheduleId}")
            append(",dueAtMs=${current.dueAtMs}")
            append(",mode=${current.mode}")
            append(",scheduledAtMs=${current.scheduledAtMs}")
        }
    }

    private fun alarmPendingIntent(
        context: Context,
        scheduleId: Long,
        dueAtMs: Long
    ): PendingIntent {
        val intent = Intent(context, RotationAlarmReceiver::class.java).apply {
            action = ACTION_ROTATION_ALARM
            data = Uri.parse("wallhaven-rotator://auto/$scheduleId/$dueAtMs")
            setPackage(context.packageName)
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_SCHEDULED_DUE_AT, dueAtMs)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun legacyAlarmPendingIntent(context: Context): PendingIntent {
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

    private fun cancelVersionedAlarm(
        manager: AlarmManager,
        context: Context,
        snapshot: ScheduleSnapshot
    ) {
        val operation = alarmPendingIntent(context, snapshot.scheduleId, snapshot.dueAtMs)
        runCatching { manager.cancel(operation) }
        runCatching { operation.cancel() }
    }

    private fun cancelLegacyAlarm(manager: AlarmManager, context: Context) {
        val legacy = legacyAlarmPendingIntent(context)
        runCatching { manager.cancel(legacy) }
        runCatching { legacy.cancel() }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
