package fr.tadikwa.wallhavenrotator

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/**
 * System-owned monotonic trigger for automatic wallpaper rotation.
 *
 * Primary/watchdog alarms use ELAPSED_REALTIME_WAKEUP. When a due alarm finds the
 * screen non-interactive, it is converted to a NON-WAKEUP elapsed alarm. That pending
 * alarm is delivered after the device naturally wakes, instead of repeatedly waking a
 * sleeping phone just to call WallpaperManager.
 */
object RotationAlarmScheduler {
    const val ACTION_ROTATION_ALARM =
        "fr.tadikwa.wallhavenrotator.action.AUTO_ROTATION_ALARM"

    private const val REQUEST_CODE = 2402
    private const val PREFS = "wallhaven_alarm_scheduler"
    private const val KEY_TRIGGER_AT = "trigger_elapsed_ms_v15"
    private const val KEY_MODE = "mode_v15"
    private const val KEY_PURPOSE = "purpose_v15"
    private const val KEY_SCHEDULED_AT = "scheduled_elapsed_ms_v15"
    private const val KEY_SCHEDULE_ID = "schedule_id_v15"
    private const val KEY_SEQUENCE = "sequence_v15"

    // Old alpha.13 scheduler keys, used once for explicit migration/cancellation.
    private const val LEGACY_KEY_DUE_AT = "due_at_ms"
    private const val LEGACY_KEY_SCHEDULE_ID = "schedule_id"

    const val EXTRA_SCHEDULE_ID = "wallhaven_alarm_schedule_id_v15"
    const val EXTRA_TRIGGER_AT = "wallhaven_alarm_trigger_elapsed_v15"

    const val PURPOSE_PRIMARY = "primary"
    const val PURPOSE_WATCHDOG = "watchdog"
    const val PURPOSE_DEFERRED_AWAKE = "deferred_until_awake"

    private const val DEFERRED_RECHECK_DELAY_MS = 60_000L

    data class ScheduleResult(
        val triggerAtMs: Long,
        val mode: String,
        val exactAllowed: Boolean,
        val scheduleId: Long,
        val purpose: String
    )

    data class ScheduleSnapshot(
        val scheduleId: Long,
        val triggerAtMs: Long,
        val mode: String,
        val purpose: String,
        val scheduledAtMs: Long
    ) {
        val isPresent: Boolean get() = scheduleId > 0L && triggerAtMs > 0L
    }

    data class AlarmInvocation(
        val scheduleId: Long,
        val triggerAtMs: Long
    )

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.applicationContext.getSystemService(AlarmManager::class.java)
        return runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
    }

    /** Authoritative wake-up at the durable gate deadline. */
    fun schedule(context: Context, triggerAtMs: Long): ScheduleResult =
        scheduleInternal(
            context = context.applicationContext,
            triggerAtMs = triggerAtMs,
            wakeup = true,
            purpose = PURPOSE_PRIMARY
        )

    /** Safety retry if a WallpaperManager call stalls or the process dies mid-apply. */
    fun scheduleWatchdog(context: Context, intervalMinutes: Long): ScheduleResult =
        scheduleInternal(
            context = context.applicationContext,
            triggerAtMs = SystemClock.elapsedRealtime() + AutoRotationPolicy.intervalMillis(intervalMinutes),
            wakeup = true,
            purpose = PURPOSE_WATCHDOG
        )

    /**
     * Do not wake the sleeping phone again. A past-due non-wakeup alarm will be
     * delivered once the device naturally becomes awake enough to process alarms.
     */
    fun scheduleDeferredUntilAwake(context: Context): ScheduleResult =
        scheduleInternal(
            context = context.applicationContext,
            triggerAtMs = SystemClock.elapsedRealtime() + DEFERRED_RECHECK_DELAY_MS,
            wakeup = false,
            purpose = PURPOSE_DEFERRED_AWAKE
        )

    fun scheduleFromGate(context: Context): ScheduleResult =
        schedule(context.applicationContext, AutoRotationGate.nextDueAt(context.applicationContext))

    private fun scheduleInternal(
        context: Context,
        triggerAtMs: Long,
        wakeup: Boolean,
        purpose: String
    ): ScheduleResult {
        val appContext = context.applicationContext
        if (triggerAtMs <= 0L) {
            cancel(appContext)
            return ScheduleResult(0L, "none", canScheduleExact(appContext), 0L, purpose)
        }

        val manager = appContext.getSystemService(AlarmManager::class.java)
        val p = prefs(appContext)
        val nowElapsed = SystemClock.elapsedRealtime()
        val triggerAt = triggerAtMs.coerceAtLeast(nowElapsed + 1_000L)
        val previous = currentSnapshot(appContext)

        // Explicit one-way migration from alpha.11/13 identities.
        cancelLegacyStoredAlpha13(manager, appContext)
        cancelLegacyUnversioned(manager, appContext)

        val reuseIdentity = previous.isPresent &&
            previous.triggerAtMs == triggerAt &&
            previous.purpose == purpose
        val scheduleId = if (reuseIdentity) {
            previous.scheduleId
        } else {
            if (previous.isPresent) cancelVersionedAlarm(manager, appContext, previous)
            val lastSequence = p.getLong(KEY_SEQUENCE, 0L)
            maxOf(System.currentTimeMillis(), lastSequence + 1L)
        }

        val operation = alarmPendingIntent(appContext, scheduleId, triggerAt)
        manager.cancel(operation)

        val exactAllowed = canScheduleExact(appContext)
        val alarmType = if (wakeup) AlarmManager.ELAPSED_REALTIME_WAKEUP else AlarmManager.ELAPSED_REALTIME
        var mode: String

        if (wakeup) {
            mode = "inexact_elapsed_wakeup"
            if (exactAllowed) {
                val exactScheduled = runCatching {
                    manager.setExactAndAllowWhileIdle(alarmType, triggerAt, operation)
                }.isSuccess
                if (exactScheduled) {
                    mode = "exact_elapsed_wakeup_allow_while_idle"
                } else {
                    manager.setAndAllowWhileIdle(alarmType, triggerAt, operation)
                    mode = "inexact_elapsed_wakeup_after_exact_failure"
                }
            } else {
                manager.setAndAllowWhileIdle(alarmType, triggerAt, operation)
            }
        } else {
            // Intentionally NOT allow-while-idle and NOT wakeup. If the device sleeps,
            // delivery waits for its natural wake-up. That is the desired behavior.
            if (exactAllowed) {
                runCatching { manager.setExact(alarmType, triggerAt, operation) }
                    .onSuccess { }
                    .onFailure { manager.set(alarmType, triggerAt, operation) }
                mode = "non_wakeup_elapsed_deferred"
            } else {
                manager.set(alarmType, triggerAt, operation)
                mode = "non_wakeup_elapsed_deferred_inexact"
            }
        }

        p.edit()
            .putLong(KEY_TRIGGER_AT, triggerAt)
            .putString(KEY_MODE, mode)
            .putString(KEY_PURPOSE, purpose)
            .putLong(KEY_SCHEDULED_AT, nowElapsed)
            .putLong(KEY_SCHEDULE_ID, scheduleId)
            .putLong(KEY_SEQUENCE, maxOf(scheduleId, p.getLong(KEY_SEQUENCE, 0L)))
            .remove(LEGACY_KEY_DUE_AT)
            .remove(LEGACY_KEY_SCHEDULE_ID)
            .apply()

        Diagnostics.log(
            appContext,
            "alarm.rotation.scheduled",
            fields = mapOf(
                "clock" to "elapsedRealtime",
                "scheduleId" to scheduleId,
                "triggerElapsedMs" to triggerAt,
                "remainingMs" to (triggerAt - nowElapsed),
                "approxTriggerWallMs" to (System.currentTimeMillis() + (triggerAt - nowElapsed)),
                "purpose" to purpose,
                "mode" to mode,
                "wakeup" to wakeup,
                "exactAllowed" to exactAllowed,
                "reusedIdentity" to reuseIdentity
            )
        )
        return ScheduleResult(triggerAt, mode, exactAllowed, scheduleId, purpose)
    }

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(AlarmManager::class.java)
        val current = currentSnapshot(appContext)
        if (current.isPresent) cancelVersionedAlarm(manager, appContext, current)
        cancelLegacyStoredAlpha13(manager, appContext)
        cancelLegacyUnversioned(manager, appContext)

        val sequence = prefs(appContext).getLong(KEY_SEQUENCE, 0L)
        prefs(appContext).edit().clear().putLong(KEY_SEQUENCE, sequence).apply()
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
            triggerAtMs = p.getLong(KEY_TRIGGER_AT, 0L),
            mode = p.getString(KEY_MODE, "none") ?: "none",
            purpose = p.getString(KEY_PURPOSE, "none") ?: "none",
            scheduledAtMs = p.getLong(KEY_SCHEDULED_AT, 0L)
        )
    }

    fun invocation(intent: Intent?): AlarmInvocation = AlarmInvocation(
        scheduleId = intent?.getLongExtra(EXTRA_SCHEDULE_ID, 0L) ?: 0L,
        triggerAtMs = intent?.getLongExtra(EXTRA_TRIGGER_AT, 0L) ?: 0L
    )

    fun isCurrent(context: Context, invocation: AlarmInvocation): Boolean {
        val current = currentSnapshot(context.applicationContext)
        return current.isPresent &&
            invocation.scheduleId == current.scheduleId &&
            invocation.triggerAtMs == current.triggerAtMs
    }

    fun statusSummary(context: Context): String {
        val appContext = context.applicationContext
        val current = currentSnapshot(appContext)
        val nowElapsed = SystemClock.elapsedRealtime()
        return buildString {
            append("exactAllowed=${canScheduleExact(appContext)}")
            append(",clock=elapsedRealtime")
            append(",scheduleId=${current.scheduleId}")
            append(",triggerElapsedMs=${current.triggerAtMs}")
            append(",remainingMs=${if (current.triggerAtMs > 0L) current.triggerAtMs - nowElapsed else 0L}")
            append(",purpose=${current.purpose}")
            append(",mode=${current.mode}")
            append(",scheduledElapsedMs=${current.scheduledAtMs}")
        }
    }

    private fun alarmPendingIntent(
        context: Context,
        scheduleId: Long,
        triggerAtMs: Long
    ): PendingIntent {
        val intent = Intent(context, RotationAlarmReceiver::class.java).apply {
            action = ACTION_ROTATION_ALARM
            data = Uri.parse("wallhaven-rotator://auto-v15/$scheduleId/$triggerAtMs")
            setPackage(context.packageName)
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_TRIGGER_AT, triggerAtMs)
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
        val operation = alarmPendingIntent(context, snapshot.scheduleId, snapshot.triggerAtMs)
        runCatching { manager.cancel(operation) }
        runCatching { operation.cancel() }
    }

    private fun cancelLegacyStoredAlpha13(manager: AlarmManager, context: Context) {
        val p = prefs(context)
        val scheduleId = p.getLong(LEGACY_KEY_SCHEDULE_ID, 0L)
        val dueAt = p.getLong(LEGACY_KEY_DUE_AT, 0L)
        if (scheduleId <= 0L || dueAt <= 0L) return

        val intent = Intent(context, RotationAlarmReceiver::class.java).apply {
            action = ACTION_ROTATION_ALARM
            data = Uri.parse("wallhaven-rotator://auto/$scheduleId/$dueAt")
            setPackage(context.packageName)
            putExtra("wallhaven_alarm_schedule_id", scheduleId)
            putExtra("wallhaven_alarm_scheduled_due_at", dueAt)
        }
        val operation = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { manager.cancel(operation) }
        runCatching { operation.cancel() }
    }

    private fun cancelLegacyUnversioned(manager: AlarmManager, context: Context) {
        val intent = Intent(context, RotationAlarmReceiver::class.java).apply {
            action = ACTION_ROTATION_ALARM
            setPackage(context.packageName)
        }
        val operation = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { manager.cancel(operation) }
        runCatching { operation.cancel() }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
