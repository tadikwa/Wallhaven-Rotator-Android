package fr.tadikwa.wallhavenrotator

import android.app.WallpaperManager
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class RotationWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).load()
        val cache = WallpaperCache(applicationContext)
        val orientation = DeviceProfile.resolveOrientation(applicationContext, settings.orientationMode)

        return runCatching {
            cache.pruneToSettings(settings)
            when (settings.targetMode) {
                TargetMode.HOME -> rotateOne(PoolKeys.home(settings.homeProfile, orientation), settings.homeProfile, WallpaperManager.FLAG_SYSTEM, orientation, cache)
                TargetMode.LOCK -> rotateOne(PoolKeys.lock(settings.lockProfile, orientation), settings.lockProfile, WallpaperManager.FLAG_LOCK, orientation, cache)
                TargetMode.BOTH_SAME -> rotateOne(
                    PoolKeys.shared(settings.homeProfile, orientation),
                    settings.homeProfile,
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                    orientation,
                    cache
                )
                TargetMode.BOTH_INDEPENDENT -> {
                    rotateOne(PoolKeys.home(settings.homeProfile, orientation), settings.homeProfile, WallpaperManager.FLAG_SYSTEM, orientation, cache)
                    rotateOne(PoolKeys.lock(settings.lockProfile, orientation), settings.lockProfile, WallpaperManager.FLAG_LOCK, orientation, cache)
                }
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun rotateOne(
        poolKey: String,
        profile: ProfileSettings,
        flag: Int,
        orientation: ResolvedOrientation,
        cache: WallpaperCache
    ) {
        val wallpaper = cache.peek(poolKey, profile, orientation)
            ?: error("Cache vide et aucun wallpaper récupérable")
        val file = cache.fileFor(poolKey, wallpaper.fileName)
        WallpaperApplier.apply(applicationContext, file, flag, orientation)
        cache.consume(poolKey, wallpaper)
        runCatching { cache.refillIfNeeded(poolKey, profile, orientation) }
    }
}

class PreloadWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).load()
        val cache = WallpaperCache(applicationContext)
        val orientation = DeviceProfile.resolveOrientation(applicationContext, settings.orientationMode)
        return runCatching {
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
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
