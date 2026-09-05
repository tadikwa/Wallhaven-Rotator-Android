package fr.tadikwa.wallhavenrotator

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

data class WallpaperApplyResult(
    val destination: String,
    val returnedId: Int,
    val beforeId: Int,
    val afterId: Int
)

data class CombinedWallpaperApplyResult(
    val home: WallpaperApplyResult,
    val lock: WallpaperApplyResult
)

object WallpaperApplier {
    fun apply(
        context: Context,
        file: File,
        which: Int,
        orientation: ResolvedOrientation,
        deepDiagnostics: Boolean = false
    ): WallpaperApplyResult {
        val destination = destinationLabel(which)
        requireAutomaticReady(context, deepDiagnostics, "before_prepare")
        val prepared = prepareBitmap(context, file, orientation)

        try {
            requireAutomaticReady(context, deepDiagnostics, "after_prepare")
            val manager = WallpaperManager.getInstance(context)
            assertWallpaperAllowed(manager)
            val beforeId = if (deepDiagnostics) {
                runCatching { manager.getWallpaperId(which) }.getOrDefault(-1)
            } else -1

            Diagnostics.log(
                context,
                "wallpaper.apply.start",
                fields = mapOf(
                    "destination" to destination,
                    "file" to file.name,
                    "bytes" to file.length(),
                    "orientation" to orientation.name,
                    "beforeId" to beforeId,
                    "deepDiagnostics" to deepDiagnostics
                )
            )

            val probe = if (deepDiagnostics) {
                WallpaperDeepDiagnostics.begin(
                    context = context,
                    manager = manager,
                    which = which,
                    destination = destination,
                    bitmap = prepared.bitmap,
                    sourceFile = file
                )
            } else null

            try {
                val returnedId = manager.setBitmap(prepared.bitmap, null, false, which)
                if (returnedId <= 0) {
                    error("WallpaperManager a refusé le fond d'écran $destination (ID retourné : $returnedId)")
                }

                probe?.let { WallpaperDeepDiagnostics.afterSet(it, returnedId) }
                val afterId = if (deepDiagnostics) {
                    runCatching { manager.getWallpaperId(which) }.getOrDefault(returnedId)
                } else returnedId
                Diagnostics.log(
                    context,
                    "wallpaper.apply.success",
                    fields = mapOf(
                        "destination" to destination,
                        "returnedId" to returnedId,
                        "beforeId" to beforeId,
                        "afterId" to afterId,
                        "width" to prepared.width,
                        "height" to prepared.height,
                        "deepDiagnostics" to deepDiagnostics
                    )
                )
                return WallpaperApplyResult(destination, returnedId, beforeId, afterId)
            } catch (failure: Throwable) {
                Diagnostics.log(
                    context,
                    "wallpaper.apply.failure",
                    level = "ERROR",
                    fields = mapOf(
                        "destination" to destination,
                        "beforeId" to beforeId,
                        "deepDiagnostics" to deepDiagnostics
                    ),
                    throwable = failure
                )
                throw failure
            } finally {
                probe?.let(WallpaperDeepDiagnostics::end)
            }
        } finally {
            prepared.bitmap.recycle()
        }
    }

    fun applyCombined(
        context: Context,
        file: File,
        orientation: ResolvedOrientation,
        deepDiagnostics: Boolean = false
    ): CombinedWallpaperApplyResult {
        requireAutomaticReady(context, deepDiagnostics, "before_prepare")
        val prepared = prepareBitmap(context, file, orientation)

        try {
            requireAutomaticReady(context, deepDiagnostics, "after_prepare")
            val manager = WallpaperManager.getInstance(context)
            assertWallpaperAllowed(manager)
            val beforeHome = if (deepDiagnostics) {
                runCatching { manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(-1)
            } else -1
            val beforeLock = if (deepDiagnostics) {
                runCatching { manager.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrDefault(-1)
            } else -1

            Diagnostics.log(
                context,
                "wallpaper.apply_combined.start",
                fields = mapOf(
                    "file" to file.name,
                    "bytes" to file.length(),
                    "orientation" to orientation.name,
                    "beforeHomeId" to beforeHome,
                    "beforeLockId" to beforeLock,
                    "deepDiagnostics" to deepDiagnostics
                )
            )

            val homeProbe = if (deepDiagnostics) {
                WallpaperDeepDiagnostics.begin(
                    context,
                    manager,
                    WallpaperManager.FLAG_SYSTEM,
                    "home-combined",
                    prepared.bitmap,
                    file
                )
            } else null
            val lockProbe = if (deepDiagnostics) {
                WallpaperDeepDiagnostics.begin(
                    context,
                    manager,
                    WallpaperManager.FLAG_LOCK,
                    "lock",
                    prepared.bitmap,
                    file
                )
            } else null

            try {
                val returnedId = manager.setBitmap(
                    prepared.bitmap,
                    null,
                    false,
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                )
                if (returnedId <= 0) {
                    error("WallpaperManager a refusé l'application combinée (ID retourné : $returnedId)")
                }

                homeProbe?.let { WallpaperDeepDiagnostics.afterSet(it, returnedId) }
                lockProbe?.let { WallpaperDeepDiagnostics.afterSet(it, returnedId) }
                val afterHome = if (deepDiagnostics) {
                    runCatching { manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(returnedId)
                } else returnedId
                val afterLock = if (deepDiagnostics) {
                    runCatching { manager.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrDefault(returnedId)
                } else returnedId

                Diagnostics.log(
                    context,
                    "wallpaper.apply_combined.success",
                    fields = mapOf(
                        "returnedId" to returnedId,
                        "beforeHomeId" to beforeHome,
                        "afterHomeId" to afterHome,
                        "beforeLockId" to beforeLock,
                        "afterLockId" to afterLock,
                        "width" to prepared.width,
                        "height" to prepared.height,
                        "deepDiagnostics" to deepDiagnostics
                    )
                )
                return CombinedWallpaperApplyResult(
                    home = WallpaperApplyResult("home-combined", returnedId, beforeHome, afterHome),
                    lock = WallpaperApplyResult("lock", returnedId, beforeLock, afterLock)
                )
            } catch (failure: Throwable) {
                Diagnostics.log(
                    context,
                    "wallpaper.apply_combined.failure",
                    level = "ERROR",
                    fields = mapOf(
                        "beforeHomeId" to beforeHome,
                        "beforeLockId" to beforeLock,
                        "deepDiagnostics" to deepDiagnostics
                    ),
                    throwable = failure
                )
                throw failure
            } finally {
                lockProbe?.let(WallpaperDeepDiagnostics::end)
                homeProbe?.let(WallpaperDeepDiagnostics::end)
            }
        } finally {
            prepared.bitmap.recycle()
        }
    }

    /**
     * HONOR independent-pair compatibility path.
     * Automatic operation performs only the two required WallpaperManager writes.
     */
    fun applyHonorIndependentPair(
        context: Context,
        homeFile: File,
        lockFile: File,
        orientation: ResolvedOrientation,
        deepDiagnostics: Boolean = false
    ): CombinedWallpaperApplyResult {
        requireAutomaticReady(context, deepDiagnostics, "before_prepare")
        val homePrepared = prepareBitmap(context, homeFile, orientation)
        val lockPrepared = try {
            prepareBitmap(context, lockFile, orientation)
        } catch (failure: Throwable) {
            homePrepared.bitmap.recycle()
            throw failure
        }

        try {
            // Decoding/scaling can take time for large Wallhaven originals. Never enter
            // WallpaperManager if the screen locked or turned off during preparation.
            requireAutomaticReady(context, deepDiagnostics, "after_prepare")

            val manager = WallpaperManager.getInstance(context)
            assertWallpaperAllowed(manager)
            val beforeHome = if (deepDiagnostics) {
                runCatching { manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(-1)
            } else -1
            val beforeLock = if (deepDiagnostics) {
                runCatching { manager.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrDefault(-1)
            } else -1

            Diagnostics.log(
                context,
                "wallpaper.apply_honor_pair.start",
                fields = mapOf(
                    "homeFile" to homeFile.name,
                    "lockFile" to lockFile.name,
                    "homeBytes" to homeFile.length(),
                    "lockBytes" to lockFile.length(),
                    "orientation" to orientation.name,
                    "beforeHomeId" to beforeHome,
                    "beforeLockId" to beforeLock,
                    "deepDiagnostics" to deepDiagnostics
                )
            )

            val homeProbe = if (deepDiagnostics) {
                WallpaperDeepDiagnostics.begin(
                    context,
                    manager,
                    WallpaperManager.FLAG_SYSTEM,
                    "home",
                    homePrepared.bitmap,
                    homeFile
                )
            } else null
            val lockProbe = if (deepDiagnostics) {
                WallpaperDeepDiagnostics.begin(
                    context,
                    manager,
                    WallpaperManager.FLAG_LOCK,
                    "lock",
                    lockPrepared.bitmap,
                    lockFile
                )
            } else null

            try {
                // Once the combined write starts, Home MUST be restored immediately.
                // Do not insert readiness checks or diagnostics between these two calls.
                val combinedId = manager.setBitmap(
                    lockPrepared.bitmap,
                    null,
                    false,
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                )
                if (combinedId <= 0) {
                    error("WallpaperManager a refusé l'application combinée HONOR (ID retourné : $combinedId)")
                }

                val homeReturnedId = manager.setBitmap(
                    homePrepared.bitmap,
                    null,
                    false,
                    WallpaperManager.FLAG_SYSTEM
                )
                if (homeReturnedId <= 0) {
                    error("WallpaperManager a refusé la restauration du fond d'accueil (ID retourné : $homeReturnedId)")
                }

                lockProbe?.let { WallpaperDeepDiagnostics.afterSetFast(it, combinedId) }
                homeProbe?.let { WallpaperDeepDiagnostics.afterSetFast(it, homeReturnedId) }

                val afterHome = if (deepDiagnostics) {
                    runCatching { manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(homeReturnedId)
                } else homeReturnedId
                val afterLock = if (deepDiagnostics) {
                    runCatching { manager.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrDefault(combinedId)
                } else combinedId

                Diagnostics.log(
                    context,
                    "wallpaper.apply_honor_pair.success",
                    fields = mapOf(
                        "combinedId" to combinedId,
                        "homeReturnedId" to homeReturnedId,
                        "beforeHomeId" to beforeHome,
                        "afterHomeId" to afterHome,
                        "beforeLockId" to beforeLock,
                        "afterLockId" to afterLock,
                        "width" to homePrepared.width,
                        "height" to homePrepared.height,
                        "deepDiagnostics" to deepDiagnostics
                    )
                )

                return CombinedWallpaperApplyResult(
                    home = WallpaperApplyResult("home", homeReturnedId, beforeHome, afterHome),
                    lock = WallpaperApplyResult("lock", combinedId, beforeLock, afterLock)
                )
            } catch (failure: Throwable) {
                Diagnostics.log(
                    context,
                    "wallpaper.apply_honor_pair.failure",
                    level = "ERROR",
                    fields = mapOf(
                        "beforeHomeId" to beforeHome,
                        "beforeLockId" to beforeLock,
                        "deepDiagnostics" to deepDiagnostics
                    ),
                    throwable = failure
                )
                throw failure
            } finally {
                lockProbe?.let(WallpaperDeepDiagnostics::end)
                homeProbe?.let(WallpaperDeepDiagnostics::end)
            }
        } finally {
            lockPrepared.bitmap.recycle()
            homePrepared.bitmap.recycle()
        }
    }

    private data class PreparedBitmap(val bitmap: Bitmap, val width: Int, val height: Int)

    private fun requireAutomaticReady(
        context: Context,
        deepDiagnostics: Boolean,
        phase: String
    ) {
        if (deepDiagnostics) return
        val executionState = BackgroundExecutionState.snapshot(context)
        if (!executionState.readyForAutomaticWallpaper) {
            Diagnostics.log(
                context.applicationContext,
                "wallpaper.apply.deferred_not_ready",
                fields = mapOf(
                    "phase" to phase,
                    "interactive" to executionState.interactive,
                    "deviceLocked" to executionState.deviceLocked,
                    "keyguardLocked" to executionState.keyguardLocked,
                    "reason" to executionState.reason
                )
            )
            throw AutomaticWallpaperApplyDeferredException(executionState, phase)
        }
    }

    private fun prepareBitmap(
        context: Context,
        file: File,
        orientation: ResolvedOrientation
    ): PreparedBitmap {
        val (targetWidth, targetHeight) = targetSize(context, orientation)
        val bitmap = decodeSampled(file, targetWidth * 2, targetHeight * 2)
            ?: error("Impossible de décoder ${file.name}")
        val cropped = centerCrop(bitmap, targetWidth, targetHeight)
        if (cropped !== bitmap) bitmap.recycle()
        return PreparedBitmap(cropped, targetWidth, targetHeight)
    }

    private fun assertWallpaperAllowed(manager: WallpaperManager) {
        if (!manager.isWallpaperSupported()) {
            error("Android indique que les fonds d'écran ne sont pas pris en charge sur cet appareil")
        }
        if (!manager.isSetWallpaperAllowed()) {
            error("Android interdit actuellement à l'application de modifier le fond d'écran")
        }
    }

    private fun destinationLabel(which: Int): String = when (which) {
        WallpaperManager.FLAG_SYSTEM -> "home"
        WallpaperManager.FLAG_LOCK -> "lock"
        else -> "unknown:$which"
    }

    private fun targetSize(context: Context, orientation: ResolvedOrientation): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val short = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val long = maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        return when (orientation) {
            ResolvedOrientation.PORTRAIT -> short to long
            ResolvedOrientation.LANDSCAPE -> long to short
        }
    }

    private fun decodeSampled(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidth && bounds.outHeight / (sample * 2) >= reqHeight) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun centerCrop(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val sourceRatio = source.width.toFloat() / source.height.toFloat()
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
        val cropWidth: Int
        val cropHeight: Int
        if (sourceRatio > targetRatio) {
            cropHeight = source.height
            cropWidth = (cropHeight * targetRatio).roundToInt().coerceAtMost(source.width)
        } else {
            cropWidth = source.width
            cropHeight = (cropWidth / targetRatio).roundToInt().coerceAtMost(source.height)
        }
        val x = max(0, (source.width - cropWidth) / 2)
        val y = max(0, (source.height - cropHeight) / 2)
        val cropped = Bitmap.createBitmap(source, x, y, cropWidth, cropHeight)
        if (cropped.width == targetWidth && cropped.height == targetHeight) return cropped
        val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }
}
