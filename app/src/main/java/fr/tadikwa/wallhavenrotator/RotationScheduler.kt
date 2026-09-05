package fr.tadikwa.wallhavenrotator

import android.content.Context
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

internal object PreloadControl {
    private val generation = AtomicLong(0L)

    fun snapshot(): Long = generation.get()
    fun interrupt(): Long = generation.incrementAndGet()
    fun shouldStop(token: Long): Boolean = generation.get() != token
}

object RotationScheduler {
    private const val PERIODIC_NAME = "wallhaven_rotation"
    private const val PRELOAD_NAME = "wallhaven_preload"

    fun configure(context: Context, settings: AppSettings, reason: String = "settings_save") {
        val appContext = context.applicationContext
        val manager = WorkManager.getInstance(appContext)

        if (!settings.enabled) {
            PreloadControl.interrupt()
            manager.cancelUniqueWork(PERIODIC_NAME)
            manager.cancelUniqueWork(PRELOAD_NAME)
            RotationAlarmScheduler.cancel(appContext)
            AutoRotationGate.clear(appContext)
            RotationForegroundService.stop(appContext)
            Diagnostics.log(appContext, "scheduler.disabled", fields = mapOf("reason" to reason))
            return
        }

        val minutes = AutoRotationPolicy.normalizeIntervalMinutes(settings.intervalMinutes)
        val nextDueAt = AutoRotationGate.reset(appContext, minutes, reason)
        val request = periodicRequest(minutes, AutoRotationPolicy.intervalMillis(minutes))

        manager.enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request
        )
        AutoRotationGate.markConfigVersion(appContext)
        val alarm = RotationAlarmScheduler.schedule(appContext, nextDueAt)

        Diagnostics.log(
            appContext,
            "scheduler.periodic.configured",
            fields = mapOf(
                "clock" to "elapsedRealtime",
                "intervalMinutes" to minutes,
                "nextDueElapsedMs" to nextDueAt,
                "workPolicy" to "CANCEL_AND_REENQUEUE",
                "alarmMode" to alarm.mode,
                "alarmPurpose" to alarm.purpose,
                "alarmScheduleId" to alarm.scheduleId,
                "exactAlarmAllowed" to alarm.exactAllowed,
                "reason" to reason
            )
        )
    }

    fun reconcile(context: Context, settings: AppSettings) {
        val appContext = context.applicationContext
        val manager = WorkManager.getInstance(appContext)

        if (!settings.enabled) {
            manager.cancelUniqueWork(PERIODIC_NAME)
            manager.cancelUniqueWork(PRELOAD_NAME)
            RotationAlarmScheduler.cancel(appContext)
            RotationForegroundService.stop(appContext)
            return
        }

        if (AutoRotationGate.configVersion(appContext) != AutoRotationGate.CONFIG_VERSION) {
            Diagnostics.log(
                appContext,
                "scheduler.reconcile.migration",
                fields = mapOf(
                    "fromVersion" to AutoRotationGate.configVersion(appContext),
                    "toVersion" to AutoRotationGate.CONFIG_VERSION,
                    "clock" to "elapsedRealtime"
                )
            )
            configure(appContext, settings, reason = "alpha15_sleep_safe_migration")
            return
        }

        val minutes = AutoRotationPolicy.normalizeIntervalMinutes(settings.intervalMinutes)
        val dueAt = AutoRotationGate.ensureInitialized(appContext, minutes)
        val initialDelay = max(0L, dueAt - SystemClock.elapsedRealtime())
        manager.enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest(minutes, initialDelay)
        )
        val alarm = RotationAlarmScheduler.schedule(appContext, dueAt)
        Diagnostics.log(
            appContext,
            "scheduler.reconcile.ok",
            fields = mapOf(
                "clock" to "elapsedRealtime",
                "nextDueElapsedMs" to dueAt,
                "remainingMs" to initialDelay,
                "intervalMinutes" to minutes,
                "alarmMode" to alarm.mode,
                "alarmPurpose" to alarm.purpose,
                "alarmScheduleId" to alarm.scheduleId,
                "exactAlarmAllowed" to alarm.exactAllowed
            )
        )
    }

    fun recoverAfterSystemEvent(context: Context, settings: AppSettings, action: String) {
        if (!settings.enabled) return
        // elapsedRealtime resets at boot. Package replacement also gets one clean
        // interval, which is preferable to carrying an ambiguous old wake-up identity.
        configure(context.applicationContext, settings, reason = "system:$action")
    }

    fun exactAlarmPermissionChanged(context: Context, settings: AppSettings) {
        if (!settings.enabled) return
        val appContext = context.applicationContext
        val dueAt = AutoRotationGate.ensureInitialized(appContext, settings.intervalMinutes)
        val alarm = RotationAlarmScheduler.schedule(appContext, dueAt)
        Diagnostics.log(
            appContext,
            "scheduler.exact_alarm_permission_changed",
            fields = mapOf(
                "clock" to "elapsedRealtime",
                "exactAlarmAllowed" to alarm.exactAllowed,
                "alarmMode" to alarm.mode,
                "alarmPurpose" to alarm.purpose,
                "alarmScheduleId" to alarm.scheduleId,
                "triggerElapsedMs" to alarm.triggerAtMs
            )
        )
    }

    fun preload(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            PRELOAD_NAME,
            ExistingWorkPolicy.REPLACE,
            preloadRequest()
        )
    }

    fun preloadIfNeeded(context: Context) {
        val appContext = context.applicationContext
        if (!SettingsRepository(appContext).load().enabled) return
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            PRELOAD_NAME,
            ExistingWorkPolicy.KEEP,
            preloadRequest()
        )
    }

    fun requestManualPriority(context: Context, intervalMinutes: Long) {
        val appContext = context.applicationContext
        val manager = WorkManager.getInstance(appContext)
        val generation = PreloadControl.interrupt()
        manager.cancelUniqueWork(PRELOAD_NAME)

        val minutes = AutoRotationPolicy.normalizeIntervalMinutes(intervalMinutes)
        val nextDue = AutoRotationGate.deferAfterManual(appContext, minutes)

        manager.enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            periodicRequest(minutes, AutoRotationPolicy.intervalMillis(minutes))
        )
        AutoRotationGate.markConfigVersion(appContext)
        val alarm = RotationAlarmScheduler.schedule(appContext, nextDue)

        Diagnostics.log(
            appContext,
            "scheduler.manual_priority",
            fields = mapOf(
                "clock" to "elapsedRealtime",
                "preloadGeneration" to generation,
                "nextAutomaticDueElapsedMs" to nextDue,
                "workPolicy" to "CANCEL_AND_REENQUEUE",
                "alarmMode" to alarm.mode,
                "alarmPurpose" to alarm.purpose,
                "alarmScheduleId" to alarm.scheduleId
            )
        )
    }

    fun requestAutomaticPriority(context: Context, source: String) {
        val appContext = context.applicationContext
        val generation = PreloadControl.interrupt()
        WorkManager.getInstance(appContext).cancelUniqueWork(PRELOAD_NAME)
        Diagnostics.log(
            appContext,
            "scheduler.automatic_priority",
            fields = mapOf("source" to source, "preloadGeneration" to generation)
        )
    }

    fun rotateNow(context: Context) {
        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext).load()
        requestManualPriority(appContext, settings.intervalMinutes)
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "wallhaven_manual_rotation",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RotationWorker>()
                .setInputData(androidx.work.workDataOf("manual" to true))
                .build()
        )
    }

    private fun periodicRequest(intervalMinutes: Long, initialDelayMs: Long) =
        PeriodicWorkRequestBuilder<RotationWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .addTag("wallhaven_rotation_periodic")
            .build()

    private fun preloadRequest() = OneTimeWorkRequestBuilder<PreloadWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .addTag("wallhaven_preload")
        .build()
}
