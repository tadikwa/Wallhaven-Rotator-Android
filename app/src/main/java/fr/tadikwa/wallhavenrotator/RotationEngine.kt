package fr.tadikwa.wallhavenrotator

import android.app.WallpaperManager
import android.content.Context
import java.io.File

data class RotationOutcome(
    val destinations: List<String>,
    val wallhavenIds: List<String>
) {
    fun userMessage(): String = when (destinations.toSet()) {
        setOf("home", "lock") -> "Accueil et verrouillage mis à jour."
        setOf("home") -> "Fond d'écran d'accueil mis à jour."
        setOf("lock") -> "Fond d'écran de verrouillage mis à jour."
        else -> "Fond d'écran mis à jour."
    }
}

object RotationEngine {
    // Manual actions and WorkManager can otherwise overlap and apply/refill the same
    // pools at once. One in-process rotation at a time keeps destination ordering sane.
    private val rotationLock = Any()

    fun rotateOnce(context: Context, settings: AppSettings): RotationOutcome =
        synchronized(rotationLock) {
            rotateOnceLocked(context.applicationContext, settings)
        }

    private fun rotateOnceLocked(appContext: Context, settings: AppSettings): RotationOutcome {
        val cache = WallpaperCache(appContext)
        val orientation = DeviceProfile.resolveOrientation(appContext, settings.orientationMode)

        Diagnostics.log(
            appContext,
            "rotation.start",
            fields = mapOf(
                "target" to settings.targetMode.name,
                "orientation" to orientation.name,
                "cacheFiles" to cache.countAll()
            )
        )

        try {
            cache.pruneToSettings(settings)
            val outcome = when (settings.targetMode) {
                TargetMode.HOME -> {
                    val prepared = prepareOne(
                        PoolKeys.home(settings.homeProfile, orientation),
                        settings.homeProfile,
                        WallpaperManager.FLAG_SYSTEM,
                        orientation,
                        cache
                    )
                    val applied = applyPrepared(appContext, prepared, orientation, cache)
                    RotationOutcome(listOf(applied.destination), listOf(applied.wallhavenId))
                }

                TargetMode.LOCK -> {
                    val prepared = prepareOne(
                        PoolKeys.lock(settings.lockProfile, orientation),
                        settings.lockProfile,
                        WallpaperManager.FLAG_LOCK,
                        orientation,
                        cache
                    )
                    val applied = applyPrepared(appContext, prepared, orientation, cache)
                    RotationOutcome(listOf(applied.destination), listOf(applied.wallhavenId))
                }

                TargetMode.BOTH_SAME -> rotateSameOnBoth(
                    appContext,
                    PoolKeys.shared(settings.homeProfile, orientation),
                    settings.homeProfile,
                    orientation,
                    cache
                )

                TargetMode.BOTH_INDEPENDENT -> {
                    // Prepare BOTH destinations before applying either one. On an empty
                    // cache each miss fetches just one image, so Home can no longer spend
                    // a minute filling its pool before Lock even gets attempted.
                    val homePrepared = prepareOne(
                        PoolKeys.home(settings.homeProfile, orientation),
                        settings.homeProfile,
                        WallpaperManager.FLAG_SYSTEM,
                        orientation,
                        cache
                    )
                    val lockPrepared = prepareOne(
                        PoolKeys.lock(settings.lockProfile, orientation),
                        settings.lockProfile,
                        WallpaperManager.FLAG_LOCK,
                        orientation,
                        cache
                    )

                    val home = applyPrepared(appContext, homePrepared, orientation, cache)
                    val lock = applyPrepared(appContext, lockPrepared, orientation, cache)
                    RotationOutcome(
                        destinations = listOf(home.destination, lock.destination),
                        wallhavenIds = listOf(home.wallhavenId, lock.wallhavenId)
                    )
                }
            }

            // Never refill inline between Home and Lock. Replenishment is a unique,
            // asynchronous WorkManager job and cannot block the visible rotation.
            RotationScheduler.preloadIfNeeded(appContext)

            Diagnostics.log(
                appContext,
                "rotation.success",
                fields = mapOf(
                    "target" to settings.targetMode.name,
                    "destinations" to outcome.destinations.joinToString(","),
                    "wallhavenIds" to outcome.wallhavenIds.joinToString(","),
                    "cacheFiles" to cache.countAll()
                )
            )
            return outcome
        } catch (failure: Throwable) {
            Diagnostics.log(
                appContext,
                "rotation.failure",
                level = "ERROR",
                fields = mapOf(
                    "target" to settings.targetMode.name,
                    "orientation" to orientation.name,
                    "cacheFiles" to cache.countAll()
                ),
                throwable = failure
            )
            throw failure
        }
    }

    private data class Prepared(
        val poolKey: String,
        val flag: Int,
        val wallpaper: CachedWallpaper,
        val file: File
    )

    private data class Applied(
        val destination: String,
        val wallhavenId: String
    )

    private fun prepareOne(
        poolKey: String,
        profile: ProfileSettings,
        flag: Int,
        orientation: ResolvedOrientation,
        cache: WallpaperCache
    ): Prepared {
        val wallpaper = cache.peek(poolKey, profile, orientation)
            ?: error("Cache vide et aucun wallpaper récupérable")
        return Prepared(
            poolKey = poolKey,
            flag = flag,
            wallpaper = wallpaper,
            file = cache.fileFor(poolKey, wallpaper.fileName)
        )
    }

    private fun applyPrepared(
        context: Context,
        prepared: Prepared,
        orientation: ResolvedOrientation,
        cache: WallpaperCache
    ): Applied {
        val applyResult = WallpaperApplier.apply(context, prepared.file, prepared.flag, orientation)
        cache.consume(prepared.poolKey, prepared.wallpaper)
        return Applied(applyResult.destination, prepared.wallpaper.id)
    }

    private fun rotateSameOnBoth(
        context: Context,
        poolKey: String,
        profile: ProfileSettings,
        orientation: ResolvedOrientation,
        cache: WallpaperCache
    ): RotationOutcome {
        val wallpaper = cache.peek(poolKey, profile, orientation)
            ?: error("Cache vide et aucun wallpaper récupérable")
        val file = cache.fileFor(poolKey, wallpaper.fileName)

        // Apply the exact same bitmap to each destination separately. Android's API
        // accepts combined flags, but separate calls let us verify/log home and lock
        // independently and are more robust across OEM wallpaper implementations.
        val home = WallpaperApplier.apply(context, file, WallpaperManager.FLAG_SYSTEM, orientation)
        val lock = WallpaperApplier.apply(context, file, WallpaperManager.FLAG_LOCK, orientation)

        cache.consume(poolKey, wallpaper)

        return RotationOutcome(
            destinations = listOf(home.destination, lock.destination),
            wallhavenIds = listOf(wallpaper.id)
        )
    }
}
