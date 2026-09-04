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
 *
 * WorkManager cancellation alone is asynchronous. A visible rotation increments this
 * generation immediately, letting a running PreloadWorker notice the priority change
 * between network operations and release the cache lock quickly.
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

    /**
     * Explicit settings save: throw away the old periodic generation completely.
     *
     * UPDATE preserves the original enqueue time by design, which is the opposite of
     * what a settings Save needs and can leave OEM schedulers with stale generations.
     * CANCEL_AND_REENQUEUE gives us one clean periodic request and a fresh cadence.
     */
    fun configure(context: Context, settings: AppSettings, reason: String = "settings_save") {
        val appContext = context.applicationContext
        val manager = WorkManager.getInstance(appContext)

        if (!settings.enabled) {
            PreloadControl.interrupt()
            manager.cancelUniqueWork(PERIODIC_NAME)
            manager.cancelUniqueWork(PRELOAD_NAME)
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
        startForegroundServiceSafely(appContext, "configure:$reason")

        Diagnostics.log(
            appContext,
            "scheduler.periodic.configured",
            fields = mapOf(
                "intervalMinutes" to minutes,
                "nextDueAtMs" to nextDueAt,
                "policy" to "CANCEL_AND_REENQUEUE",
                "reason" to reason
            )
        )
    }

    /**
     * Called when the UI opens. It repairs old alpha scheduling once, then stops
     * touching cadence on ordinary app opens so opening the app can never postpone a
     * pending automatic rotation.
     */
    fun reconcile(context: Context, settings: AppSettings) {
        val appContext = context.applicationContext
        val manager = WorkManager.getInstance(appContext)

        if (!settings.enabled) {
            manager.cancelUniqueWork(PERIODIC_NAME)
            manager.cancelUniqueWork(PRELOAD_NAME)
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
            configure(appContext, settings, reason = "alpha10_migration")
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
        startForegroundServiceSafely(appContext, "reconcile")
        Diagnostics.log(
            appContext,
            "scheduler.reconcile.ok",
            fields = mapOf("nextDueAtMs" to dueAt, "intervalMinutes" to minutes)
        )
    }

    fun recoverAfterSystemEvent(context: Context, settings: AppSettings, action: String) {
        if (!settings.enabled) return
        // BOOT_COMPLETED and MY_PACKAGE_REPLACED are Android-documented exemptions for
        // starting a foreground service from the background. Rebuild one clean cadence.
        configure(context.applicationContext, settings, reason = "system:$action")
    }

    // Explicit settings changes restart cache warming against the current pools.
    fun preload(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            PRELOAD_NAME,
            ExistingWorkPolicy.REPLACE,
            preloadRequest()
        )
    }

    // Rotation completion only needs to ensure that one refill exists. KEEP avoids
    // cancelling/restarting an already-running preload and prevents refill storms.
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
        val generation = PreloadControl.interrupt()
        WorkManager.getInstance(appContext).cancelUniqueWork(PRELOAD_NAME)
        val nextDue = AutoRotationGate.deferAfterManual(appContext, intervalMinutes)
        Diagnostics.log(
            appContext,
            "scheduler.manual_priority",
            fields = mapOf(
                "preloadGeneration" to generation,
                "nextAutomaticDueAtMs" to nextDue
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

    private fun startForegroundServiceSafely(context: Context, source: String) {
        runCatching { RotationForegroundService.start(context) }
            .onFailure { failure ->
                Diagnostics.log(
                    context,
                    "service.rotation.start_failure",
                    level = "ERROR",
                    fields = mapOf("source" to source),
                    throwable = failure
                )
            }
    }
}
