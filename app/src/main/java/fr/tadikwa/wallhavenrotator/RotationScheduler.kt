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

/**
 * In-process cooperative stop signal for background preloads.
 *
 * WorkManager cancellation alone is asynchronous. A manual wallpaper change increments
 * this generation immediately, letting an already-running PreloadWorker notice the
 * priority change between network operations and release the cache lock quickly.
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

    fun configure(context: Context, settings: AppSettings) {
        val manager = WorkManager.getInstance(context)
        if (!settings.enabled) {
            PreloadControl.interrupt()
            manager.cancelUniqueWork(PERIODIC_NAME)
            manager.cancelUniqueWork(PRELOAD_NAME)
            Diagnostics.log(context, "scheduler.disabled")
            return
        }

        val minutes = settings.intervalMinutes.coerceAtLeast(15L)
        val request = PeriodicWorkRequestBuilder<RotationWorker>(minutes, TimeUnit.MINUTES)
            // Saving settings warms the cache only. The first automatic wallpaper
            // rotation is intentionally delayed by the full configured interval.
            .setInitialDelay(minutes, TimeUnit.MINUTES)
            .build()

        // Reset the cadence from the moment the user presses Save. UPDATE can preserve
        // timing from a previous periodic request, which makes the first change appear
        // unexpectedly early after editing settings.
        manager.enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request
        )
        Diagnostics.log(
            context,
            "scheduler.periodic.configured",
            fields = mapOf(
                "intervalMinutes" to minutes,
                "initialDelayMinutes" to minutes,
                "policy" to "CANCEL_AND_REENQUEUE"
            )
        )
    }

    // Explicit settings changes restart cache warming against the current pools.
    fun preload(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            PRELOAD_NAME,
            ExistingWorkPolicy.REPLACE,
            preloadRequest()
        )
    }

    // Rotation completion only needs to ensure that one refill exists. KEEP avoids
    // cancelling/restarting an already-running preload and prevents refill storms.
    fun preloadIfNeeded(context: Context) {
        if (!SettingsRepository(context).load().enabled) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            PRELOAD_NAME,
            ExistingWorkPolicy.KEEP,
            preloadRequest()
        )
    }

    /**
     * Give an explicit user action priority over cache warming.
     *
     * The generation signal is immediate; WorkManager cancellation is the durable
     * companion signal. After the manual rotation MainActivity enqueues a fresh preload.
     */
    fun requestManualPriority(context: Context) {
        val generation = PreloadControl.interrupt()
        WorkManager.getInstance(context).cancelUniqueWork(PRELOAD_NAME)
        Diagnostics.log(
            context,
            "scheduler.manual_priority",
            fields = mapOf("preloadGeneration" to generation)
        )
    }

    fun rotateNow(context: Context) {
        requestManualPriority(context)
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<RotationWorker>().build())
    }

    private fun preloadRequest() = OneTimeWorkRequestBuilder<PreloadWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .build()
}
