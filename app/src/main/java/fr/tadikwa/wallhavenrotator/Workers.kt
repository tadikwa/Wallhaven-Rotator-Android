package fr.tadikwa.wallhavenrotator

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class RotationWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).load()
        val manual = inputData.getBoolean("manual", false)

        if (!settings.enabled && !manual) {
            Diagnostics.log(applicationContext, "worker.rotation.skip_disabled")
            return Result.success()
        }

        if (manual) {
            Diagnostics.log(
                applicationContext,
                "worker.rotation.start",
                fields = mapOf(
                    "attempt" to runAttemptCount,
                    "target" to settings.targetMode.name,
                    "manual" to true
                )
            )
            return try {
                val outcome = RotationEngine.rotateOnce(applicationContext, settings)
                Diagnostics.log(
                    applicationContext,
                    "worker.rotation.success",
                    fields = mapOf(
                        "attempt" to runAttemptCount,
                        "wallhavenIds" to outcome.wallhavenIds.joinToString(","),
                        "manual" to true
                    )
                )
                Result.success()
            } catch (failure: Throwable) {
                Diagnostics.log(
                    applicationContext,
                    "worker.rotation.failure",
                    level = "ERROR",
                    fields = mapOf("attempt" to runAttemptCount, "manual" to true),
                    throwable = failure
                )
                Result.success()
            }
        }

        // WorkManager is repair-only in alpha.17. It never owns WallpaperManager.
        // A periodic/fallback wake only restores the system AlarmManager trigger; the
        // exact-alarm receiver + foreground service remain the single automatic writer.
        val executionState = BackgroundExecutionState.snapshot(applicationContext)
        if (!executionState.readyForAutomaticWallpaper) {
            val deferred = RotationAlarmScheduler.scheduleDeferredUntilAwake(applicationContext)
            Diagnostics.log(
                applicationContext,
                "worker.rotation.deferred_not_ready",
                fields = mapOf(
                    "attempt" to runAttemptCount,
                    "gateDueElapsedMs" to AutoRotationGate.nextDueAt(applicationContext),
                    "deferredTriggerElapsedMs" to deferred.triggerAtMs,
                    "mode" to deferred.mode,
                    "interactive" to executionState.interactive,
                    "deviceLocked" to executionState.deviceLocked,
                    "keyguardLocked" to executionState.keyguardLocked,
                    "deferReason" to executionState.reason
                )
            )
            return Result.success()
        }

        if (RotationServiceStatus.isLikelyRunning(applicationContext)) {
            Diagnostics.log(
                applicationContext,
                "worker.rotation.service_already_running",
                fields = mapOf("attempt" to runAttemptCount)
            )
            return Result.success()
        }

        val dueAt = AutoRotationGate.ensureInitialized(
            applicationContext,
            settings.intervalMinutes
        )
        val alarm = RotationAlarmScheduler.schedule(applicationContext, dueAt)
        Diagnostics.log(
            applicationContext,
            "worker.rotation.handoff_to_alarm",
            fields = mapOf(
                "attempt" to runAttemptCount,
                "gateDueElapsedMs" to dueAt,
                "alarmTriggerElapsedMs" to alarm.triggerAtMs,
                "alarmScheduleId" to alarm.scheduleId,
                "alarmMode" to alarm.mode,
                "purpose" to alarm.purpose
            )
        )
        return Result.success()
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
