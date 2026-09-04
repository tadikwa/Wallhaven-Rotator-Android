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

class PreloadWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).load()
        val cache = WallpaperCache(applicationContext)
        val orientation = DeviceProfile.resolveOrientation(applicationContext, settings.orientationMode)
        Diagnostics.log(
            applicationContext,
            "worker.preload.start",
            fields = mapOf(
                "attempt" to runAttemptCount,
                "target" to settings.targetMode.name,
                "orientation" to orientation.name
            )
        )
        return try {
            cache.pruneToSettings(settings)
            when (settings.targetMode) {
                TargetMode.HOME -> cache.refill(PoolKeys.home(settings.homeProfile, orientation), settings.homeProfile, orientation)
                TargetMode.LOCK -> cache.refill(PoolKeys.lock(settings.lockProfile, orientation), settings.lockProfile, orientation)
                TargetMode.BOTH_SAME -> cache.refill(PoolKeys.shared(settings.homeProfile, orientation), settings.homeProfile, orientation)
                TargetMode.BOTH_INDEPENDENT -> {
                    cache.refill(PoolKeys.home(settings.homeProfile, orientation), settings.homeProfile, orientation)
                    cache.refill(PoolKeys.lock(settings.lockProfile, orientation), settings.lockProfile, orientation)
                }
            }
            Diagnostics.log(
                applicationContext,
                "worker.preload.success",
                fields = mapOf("cacheFiles" to cache.countAll())
            )
            Result.success()
        } catch (failure: Throwable) {
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
}
