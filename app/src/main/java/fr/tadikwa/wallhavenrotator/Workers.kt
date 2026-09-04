package fr.tadikwa.wallhavenrotator

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class RotationWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).load()
        Diagnostics.log(
            applicationContext,
            "worker.rotation.start",
            fields = mapOf("attempt" to runAttemptCount, "target" to settings.targetMode.name)
        )
        return try {
            RotationEngine.rotateOnce(applicationContext, settings)
            Diagnostics.log(
                applicationContext,
                "worker.rotation.success",
                fields = mapOf("attempt" to runAttemptCount)
            )
            Result.success()
        } catch (failure: Throwable) {
            // A periodic rotation already has a future cadence. Immediate WorkManager
            // retries caused request bursts when the network or Wallhaven was unhappy.
            Diagnostics.log(
                applicationContext,
                "worker.rotation.failure",
                level = "ERROR",
                fields = mapOf("attempt" to runAttemptCount),
                throwable = failure
            )
            Result.success()
        }
    }
}

private data class PreloadPool(
    val key: String,
    val profile: ProfileSettings
)

class PreloadWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).load()
        val cache = WallpaperCache(applicationContext)
        val orientation = DeviceProfile.resolveOrientation(applicationContext, settings.orientationMode)
        val token = PreloadControl.snapshot()
        val shouldStop = { isStopped || PreloadControl.shouldStop(token) }

        Diagnostics.log(
            applicationContext,
            "worker.preload.start",
            fields = mapOf(
                "attempt" to runAttemptCount,
                "target" to settings.targetMode.name,
                "orientation" to orientation.name,
                "preloadGeneration" to token
            )
        )

        return try {
            cache.pruneToSettings(settings)
            if (shouldStop()) return interruptedResult(token, "before_warm")

            val pools = activePools(settings, orientation)

            // Phase 1: make every active destination usable first. On independent mode
            // Home must not fill eight images before Lock has even one ready wallpaper.
            for (pool in pools) {
                cache.refill(
                    poolKey = pool.key,
                    profile = pool.profile,
                    orientation = orientation,
                    targetSize = PoolPolicy.CACHE_MISS_TARGET_SIZE,
                    shouldStop = shouldStop
                )
                if (shouldStop()) return interruptedResult(token, "warm")
            }
            Diagnostics.log(
                applicationContext,
                "worker.preload.warm_complete",
                fields = mapOf("pools" to pools.size, "cacheFiles" to cache.countAll())
            )

            // Phase 2: only after every destination has a ready image do we build the
            // reserve pool. A manual request can interrupt this pass cooperatively.
            for (pool in pools) {
                cache.refill(
                    poolKey = pool.key,
                    profile = pool.profile,
                    orientation = orientation,
                    targetSize = PoolPolicy.TARGET_SIZE,
                    shouldStop = shouldStop
                )
                if (shouldStop()) return interruptedResult(token, "fill")
            }

            Diagnostics.log(
                applicationContext,
                "worker.preload.success",
                fields = mapOf("cacheFiles" to cache.countAll())
            )
            Result.success()
        } catch (failure: Throwable) {
            if (shouldStop()) {
                return interruptedResult(token, "exception_after_stop")
            }
            // Do not create an automatic retry storm. A later save/rotation will enqueue
            // another preload; manual rotation can still fetch one image on demand.
            Diagnostics.log(
                applicationContext,
                "worker.preload.failure",
                level = "ERROR",
                fields = mapOf("attempt" to runAttemptCount),
                throwable = failure
            )
            Result.success()
        }
    }

    private fun activePools(
        settings: AppSettings,
        orientation: ResolvedOrientation
    ): List<PreloadPool> = when (settings.targetMode) {
        TargetMode.HOME -> listOf(
            PreloadPool(PoolKeys.home(settings.homeProfile, orientation), settings.homeProfile)
        )

        TargetMode.LOCK -> listOf(
            PreloadPool(PoolKeys.lock(settings.lockProfile, orientation), settings.lockProfile)
        )

        TargetMode.BOTH_SAME -> listOf(
            PreloadPool(PoolKeys.shared(settings.homeProfile, orientation), settings.homeProfile)
        )

        TargetMode.BOTH_INDEPENDENT -> listOf(
            PreloadPool(PoolKeys.home(settings.homeProfile, orientation), settings.homeProfile),
            PreloadPool(PoolKeys.lock(settings.lockProfile, orientation), settings.lockProfile)
        )
    }

    private fun interruptedResult(token: Long, phase: String): Result {
        Diagnostics.log(
            applicationContext,
            "worker.preload.interrupted",
            fields = mapOf(
                "phase" to phase,
                "attempt" to runAttemptCount,
                "preloadGeneration" to token,
                "workManagerStopped" to isStopped
            )
        )
        return Result.success()
    }
}
