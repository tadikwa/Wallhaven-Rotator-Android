package fr.tadikwa.wallhavenrotator

import android.app.WallpaperManager
import android.content.Context

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
    fun rotateOnce(context: Context, settings: AppSettings): RotationOutcome {
        val appContext = context.applicationContext
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
                    val applied = rotateOne(
                        appContext,
                        PoolKeys.home(settings.homeProfile, orientation),
                        settings.homeProfile,
                        WallpaperManager.FLAG_SYSTEM,
                        orientation,
                        cache
                    )
                    RotationOutcome(listOf(applied.destination), listOf(applied.wallhavenId))
                }

                TargetMode.LOCK -> {
                    val applied = rotateOne(
                        appContext,
                        PoolKeys.lock(settings.lockProfile, orientation),
                        settings.lockProfile,
                        WallpaperManager.FLAG_LOCK,
                        orientation,
                        cache
                    )
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
                    val home = rotateOne(
                        appContext,
                        PoolKeys.home(settings.homeProfile, orientation),
                        settings.homeProfile,
                        WallpaperManager.FLAG_SYSTEM,
                        orientation,
                        cache
                    )
                    val lock = rotateOne(
                        appContext,
                        PoolKeys.lock(settings.lockProfile, orientation),
                        settings.lockProfile,
                        WallpaperManager.FLAG_LOCK,
                        orientation,
                        cache
                    )
                    RotationOutcome(
                        destinations = listOf(home.destination, lock.destination),
                        wallhavenIds = listOf(home.wallhavenId, lock.wallhavenId)
                    )
                }
            }

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

    private data class Applied(
        val destination: String,
        val wallhavenId: String
    )

    private fun rotateOne(
        context: Context,
        poolKey: String,
        profile: ProfileSettings,
        flag: Int,
        orientation: ResolvedOrientation,
        cache: WallpaperCache
    ): Applied {
        val wallpaper = cache.peek(poolKey, profile, orientation)
            ?: error("Cache vide et aucun wallpaper récupérable")
        val file = cache.fileFor(poolKey, wallpaper.fileName)
        val applyResult = WallpaperApplier.apply(context, file, flag, orientation)
        cache.consume(poolKey, wallpaper)
        runCatching { cache.refillIfNeeded(poolKey, profile, orientation) }
            .onFailure { failure ->
                Diagnostics.log(
                    context,
                    "cache.refill.background_failure",
                    level = "WARN",
                    fields = mapOf("pool" to poolKey),
                    throwable = failure
                )
            }
        return Applied(applyResult.destination, wallpaper.id)
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
        runCatching { cache.refillIfNeeded(poolKey, profile, orientation) }
            .onFailure { failure ->
                Diagnostics.log(
                    context,
                    "cache.refill.background_failure",
                    level = "WARN",
                    fields = mapOf("pool" to poolKey),
                    throwable = failure
                )
            }

        return RotationOutcome(
            destinations = listOf(home.destination, lock.destination),
            wallhavenIds = listOf(wallpaper.id)
        )
    }
}
