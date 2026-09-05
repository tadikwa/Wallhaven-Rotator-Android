package fr.tadikwa.wallhavenrotator

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

sealed interface AutomaticRotationAttempt {
    data class Success(
        val outcome: RotationOutcome,
        val previousDueAtMs: Long,
        val nextDueAtMs: Long,
        val earlyByMs: Long,
        val cadenceCommitted: Boolean
    ) : AutomaticRotationAttempt

    data class NotDue(
        val dueAtMs: Long,
        val remainingMs: Long,
        val reason: String
    ) : AutomaticRotationAttempt

    data object Busy : AutomaticRotationAttempt
}

object RotationEngine {
    // The execution lock is now acquired BEFORE the cadence gate is inspected. That is
    // essential: a second automatic caller can no longer consume an interval while a
    // previous WallpaperManager transition is still active.
    private val rotationLock = ReentrantLock()

    /** Manual rotations wait for any current transition and keep deep diagnostics. */
    fun rotateOnce(context: Context, settings: AppSettings): RotationOutcome =
        rotationLock.withLock {
            rotateOnceLocked(
                appContext = context.applicationContext,
                settings = settings,
                deepApplyDiagnostics = true
            )
        }

    /** Legacy non-cadence try path retained for narrow callers/tests. */
    fun tryRotateOnce(context: Context, settings: AppSettings): RotationOutcome? {
        if (!rotationLock.tryLock()) {
            Diagnostics.log(
                context.applicationContext,
                "rotation.skipped_busy",
                fields = mapOf("target" to settings.targetMode.name)
            )
            return null
        }
        return try {
            rotateOnceLocked(
                appContext = context.applicationContext,
                settings = settings,
                deepApplyDiagnostics = false
            )
        } finally {
            rotationLock.unlock()
        }
    }

    /**
     * Atomic automatic path:
     * 1) acquire rotation slot;
     * 2) inspect due state without mutating it;
     * 3) rotate using the lightweight WallpaperManager path;
     * 4) only after success advance the durable cadence.
     */
    fun tryRotateAutomaticDue(
        context: Context,
        settings: AppSettings,
        source: String,
        allowEarlyAlarmTolerance: Boolean
    ): AutomaticRotationAttempt {
        val appContext = context.applicationContext
        if (!rotationLock.tryLock()) {
            Diagnostics.log(
                appContext,
                "rotation.automatic.busy",
                fields = mapOf("source" to source, "target" to settings.targetMode.name)
            )
            return AutomaticRotationAttempt.Busy
        }

        try {
            val eligibility = AutoRotationGate.inspectDue(
                context = appContext,
                intervalMinutes = settings.intervalMinutes,
                allowEarlyAlarmTolerance = allowEarlyAlarmTolerance
            )
            Diagnostics.log(
                appContext,
                "rotation.automatic.eligibility",
                fields = mapOf(
                    "source" to source,
                    "allowed" to eligibility.allowed,
                    "reason" to eligibility.reason,
                    "dueElapsedMs" to eligibility.dueAtMs,
                    "remainingMs" to (eligibility.dueAtMs - eligibility.nowMs),
                    "earlyByMs" to eligibility.earlyByMs
                )
            )

            if (!eligibility.allowed) {
                return AutomaticRotationAttempt.NotDue(
                    dueAtMs = eligibility.dueAtMs,
                    remainingMs = eligibility.dueAtMs - eligibility.nowMs,
                    reason = eligibility.reason
                )
            }

            // Only the caller that actually owns the rotation slot gets to interrupt a
            // preload. Busy/not-due callers leave the cache worker alone.
            RotationScheduler.requestAutomaticPriority(appContext, source)

            // Safety wake-up while the durable gate remains unchanged. If setBitmap
            // stalls/crashes, the future alarm sees the same overdue gate and retries.
            RotationAlarmScheduler.scheduleWatchdog(appContext, settings.intervalMinutes)

            val outcome = try {
                rotateOnceLocked(
                    appContext = appContext,
                    settings = settings,
                    deepApplyDiagnostics = false
                )
            } catch (failure: Throwable) {
                Diagnostics.log(
                    appContext,
                    "rotation.automatic.apply_failed_gate_preserved",
                    level = "ERROR",
                    fields = mapOf(
                        "source" to source,
                        "dueElapsedMs" to eligibility.dueAtMs
                    ),
                    throwable = failure
                )
                // Watchdog remains armed; DO NOT consume the due gate.
                throw failure
            }

            val completion = AutoRotationGate.completeSuccessfulRun(
                context = appContext,
                intervalMinutes = settings.intervalMinutes,
                expectedDueAtMs = eligibility.dueAtMs,
                source = source
            )

            if (completion.nextDueAtMs > 0L) {
                RotationAlarmScheduler.schedule(appContext, completion.nextDueAtMs)
            }

            Diagnostics.log(
                appContext,
                "rotation.automatic.cadence_complete",
                fields = mapOf(
                    "source" to source,
                    "committed" to completion.committed,
                    "reason" to completion.reason,
                    "previousDueElapsedMs" to completion.previousDueAtMs,
                    "nextDueElapsedMs" to completion.nextDueAtMs,
                    "earlyByMs" to eligibility.earlyByMs
                )
            )

            return AutomaticRotationAttempt.Success(
                outcome = outcome,
                previousDueAtMs = eligibility.dueAtMs,
                nextDueAtMs = completion.nextDueAtMs,
                earlyByMs = eligibility.earlyByMs,
                cadenceCommitted = completion.committed
            )
        } finally {
            rotationLock.unlock()
        }
    }

    private fun rotateOnceLocked(
        appContext: Context,
        settings: AppSettings,
        deepApplyDiagnostics: Boolean
    ): RotationOutcome {
        val cache = WallpaperCache(appContext)
        val orientation = DeviceProfile.resolveOrientation(appContext, settings.orientationMode)

        Diagnostics.log(
            appContext,
            "rotation.start",
            fields = mapOf(
                "target" to settings.targetMode.name,
                "orientation" to orientation.name,
                "cacheFiles" to cache.countAll(),
                "deepApplyDiagnostics" to deepApplyDiagnostics,
                "interactive" to BackgroundExecutionState.isInteractive(appContext)
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
                    val applied = applyPrepared(
                        appContext,
                        prepared,
                        orientation,
                        cache,
                        deepApplyDiagnostics
                    )
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
                    val applied = applyPrepared(
                        appContext,
                        prepared,
                        orientation,
                        cache,
                        deepApplyDiagnostics
                    )
                    RotationOutcome(listOf(applied.destination), listOf(applied.wallhavenId))
                }

                TargetMode.BOTH_SAME -> rotateSameOnBoth(
                    context = appContext,
                    poolKey = PoolKeys.shared(settings.homeProfile, orientation),
                    profile = settings.homeProfile,
                    orientation = orientation,
                    cache = cache,
                    deepApplyDiagnostics = deepApplyDiagnostics
                )

                TargetMode.BOTH_INDEPENDENT -> {
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

                    check(homePrepared.wallpaper.id != lockPrepared.wallpaper.id) {
                        "Les profils indépendants ont sélectionné le même wallpaper Wallhaven (${homePrepared.wallpaper.id})"
                    }

                    if (useHonorCombinedCompatibility()) {
                        rotateIndependentHonorCompatibility(
                            appContext = appContext,
                            homePrepared = homePrepared,
                            lockPrepared = lockPrepared,
                            orientation = orientation,
                            cache = cache,
                            deepApplyDiagnostics = deepApplyDiagnostics
                        )
                    } else {
                        val home = applyPrepared(
                            appContext,
                            homePrepared,
                            orientation,
                            cache,
                            deepApplyDiagnostics
                        )
                        val lock = applyPrepared(
                            appContext,
                            lockPrepared,
                            orientation,
                            cache,
                            deepApplyDiagnostics
                        )
                        RotationOutcome(
                            destinations = listOf(home.destination, lock.destination),
                            wallhavenIds = listOf(home.wallhavenId, lock.wallhavenId)
                        )
                    }
                }
            }

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
        cache: WallpaperCache,
        deepApplyDiagnostics: Boolean
    ): Applied {
        val applyResult = WallpaperApplier.apply(
            context = context,
            file = prepared.file,
            which = prepared.flag,
            orientation = orientation,
            deepDiagnostics = deepApplyDiagnostics
        )
        cache.consume(prepared.poolKey, prepared.wallpaper)
        return Applied(applyResult.destination, prepared.wallpaper.id)
    }

    private fun rotateIndependentHonorCompatibility(
        appContext: Context,
        homePrepared: Prepared,
        lockPrepared: Prepared,
        orientation: ResolvedOrientation,
        cache: WallpaperCache,
        deepApplyDiagnostics: Boolean
    ): RotationOutcome {
        Diagnostics.log(
            appContext,
            "lock.compatibility.begin",
            fields = mapOf(
                "method" to "combined_then_immediate_home_restore",
                "manufacturer" to Build.MANUFACTURER,
                "brand" to Build.BRAND,
                "homeWallpaperId" to homePrepared.wallpaper.id,
                "lockWallpaperId" to lockPrepared.wallpaper.id,
                "deepApplyDiagnostics" to deepApplyDiagnostics
            )
        )

        val pair = WallpaperApplier.applyHonorIndependentPair(
            context = appContext,
            homeFile = homePrepared.file,
            lockFile = lockPrepared.file,
            orientation = orientation,
            deepDiagnostics = deepApplyDiagnostics
        )

        Diagnostics.log(
            appContext,
            "lock.compatibility.final",
            fields = mapOf(
                "method" to "combined_then_immediate_home_restore",
                "homeId" to pair.home.afterId,
                "lockId" to pair.lock.afterId,
                "homeWallhavenId" to homePrepared.wallpaper.id,
                "lockWallhavenId" to lockPrepared.wallpaper.id
            )
        )

        cache.consume(lockPrepared.poolKey, lockPrepared.wallpaper)
        cache.consume(homePrepared.poolKey, homePrepared.wallpaper)

        return RotationOutcome(
            destinations = listOf("home", "lock"),
            wallhavenIds = listOf(homePrepared.wallpaper.id, lockPrepared.wallpaper.id)
        )
    }

    private fun useHonorCombinedCompatibility(): Boolean =
        Build.MANUFACTURER.equals("HONOR", ignoreCase = true) ||
            Build.BRAND.equals("HONOR", ignoreCase = true)

    private fun rotateSameOnBoth(
        context: Context,
        poolKey: String,
        profile: ProfileSettings,
        orientation: ResolvedOrientation,
        cache: WallpaperCache,
        deepApplyDiagnostics: Boolean
    ): RotationOutcome {
        val wallpaper = cache.peek(poolKey, profile, orientation)
            ?: error("Cache vide et aucun wallpaper récupérable")
        val file = cache.fileFor(poolKey, wallpaper.fileName)

        val home = WallpaperApplier.apply(
            context,
            file,
            WallpaperManager.FLAG_SYSTEM,
            orientation,
            deepApplyDiagnostics
        )
        val lock = WallpaperApplier.apply(
            context,
            file,
            WallpaperManager.FLAG_LOCK,
            orientation,
            deepApplyDiagnostics
        )

        cache.consume(poolKey, wallpaper)

        return RotationOutcome(
            destinations = listOf(home.destination, lock.destination),
            wallhavenIds = listOf(wallpaper.id)
        )
    }
}
