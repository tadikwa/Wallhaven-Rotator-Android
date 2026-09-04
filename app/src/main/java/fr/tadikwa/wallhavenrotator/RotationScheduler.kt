package fr.tadikwa.wallhavenrotator

import android.content.Context
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

/**
 * In-process cooperative stop signal for background preloads.
 */
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
                "intervalMinutes" to minutes,
                "nextDueAtMs" to nextDueAt,
                "workPolicy" to "CANCEL_AND_REENQUEUE",
                "alarmMode" to alarm.mode,
                "alarmScheduleId" to alarm.scheduleId,
                "exactAlarmAllowed" to alarm.exactAllowed,
                "reason" to reason
            )
        )
    }

    /**
     * Opening the UI repairs/migrates the scheduler without postponing an existing
     * deadline. Alpha.13 also re-registers the versioned system AlarmManager trigger because
     * those alarms live outside our process and survive MagicOS process cleanup.
     */
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
                    "toVersion" to AutoRotationGate.CONFIG_VERSION
                )
            )
            configure(appContext, settings, reason = "alpha13_migration")
            return
        }

        val minutes = AutoRotationPolicy.normalizeIntervalMinutes(settings.intervalMinutes)
        val dueAt = AutoRotationGate.ensureInitialized(appContext, minutes)
        val initialDelay = max(0L, dueAt - System.currentTimeMillis())
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
                "nextDueAtMs" to dueAt,
                "intervalMinutes" to minutes,
                "alarmMode" to alarm.mode,
                "alarmScheduleId" to alarm.scheduleId,
                "exactAlarmAllowed" to alarm.exactAllowed
            )
        )
    }

    fun recoverAfterSystemEvent(context: Context, settings: AppSettings, action: String) {
        if (!settings.enabled) return
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
                "exactAlarmAllowed" to alarm.exactAllowed,
                "alarmMode" to alarm.mode,
                "alarmScheduleId" to alarm.scheduleId,
                "dueAtMs" to alarm.dueAtMs
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

        // A manual rotation defines the beginning of a fresh cadence. Rebuild the
        // WorkManager fallback here directly instead of calling configure() first; the
        // old alpha.11 path scheduled two AlarmManager deadlines a few milliseconds
        // apart, which made stale vendor deliveries harder to reason about.
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
                "preloadGeneration" to generation,
                "nextAutomaticDueAtMs" to nextDue,
                "workPolicy" to "CANCEL_AND_REENQUEUE",
                "alarmMode" to alarm.mode,
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
