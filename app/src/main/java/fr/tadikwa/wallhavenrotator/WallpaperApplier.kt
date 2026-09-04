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
        orientation: ResolvedOrientation
    ): WallpaperApplyResult {
        val manager = WallpaperManager.getInstance(context)
        val destination = destinationLabel(which)
        val beforeId = runCatching { manager.getWallpaperId(which) }.getOrDefault(-1)

        Diagnostics.log(
            context,
            "wallpaper.apply.start",
            fields = mapOf(
                "destination" to destination,
                "file" to file.name,
                "bytes" to file.length(),
                "orientation" to orientation.name,
                "beforeId" to beforeId,
                "wallpaperSupported" to runCatching { manager.isWallpaperSupported() }.getOrDefault(false),
                "setWallpaperAllowed" to runCatching { manager.isSetWallpaperAllowed() }.getOrDefault(false)
            )
        )

        assertWallpaperAllowed(manager)
        val prepared = prepareBitmap(context, file, orientation)
        val probe = WallpaperDeepDiagnostics.begin(
            context = context,
            manager = manager,
            which = which,
            destination = destination,
            bitmap = prepared.bitmap,
            sourceFile = file
        )

        try {
            val returnedId = manager.setBitmap(prepared.bitmap, null, false, which)
            if (returnedId <= 0) {
                error("WallpaperManager a refusé le fond d'écran $destination (ID retourné : $returnedId)")
            }

            WallpaperDeepDiagnostics.afterSet(probe, returnedId)
            val afterId = runCatching { manager.getWallpaperId(which) }.getOrDefault(-1)
            Diagnostics.log(
                context,
                "wallpaper.apply.success",
                fields = mapOf(
                    "destination" to destination,
                    "returnedId" to returnedId,
                    "beforeId" to beforeId,
                    "afterId" to afterId,
                    "width" to prepared.width,
                    "height" to prepared.height
                )
            )
            return WallpaperApplyResult(destination, returnedId, beforeId, afterId)
        } catch (failure: Throwable) {
            Diagnostics.log(
                context,
                "wallpaper.apply.failure",
                level = "ERROR",
                fields = mapOf("destination" to destination, "beforeId" to beforeId),
                throwable = failure
            )
            throw failure
        } finally {
            WallpaperDeepDiagnostics.end(probe)
            prepared.bitmap.recycle()
        }
    }

    /**
     * Compatibility experiment for OEM lock screens that acknowledge FLAG_LOCK but
     * do not visibly refresh. This reproduces the original combined Android call:
     * the lock candidate is submitted to SYSTEM|LOCK in one setBitmap invocation.
     * RotationEngine then restores the independent Home candidate with FLAG_SYSTEM.
     */
    fun applyCombined(
        context: Context,
        file: File,
        orientation: ResolvedOrientation
    ): CombinedWallpaperApplyResult {
        val manager = WallpaperManager.getInstance(context)
        assertWallpaperAllowed(manager)
        val beforeHome = runCatching { manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(-1)
        val beforeLock = runCatching { manager.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrDefault(-1)
        val prepared = prepareBitmap(context, file, orientation)

        Diagnostics.log(
            context,
            "wallpaper.apply_combined.start",
            fields = mapOf(
                "file" to file.name,
                "bytes" to file.length(),
                "orientation" to orientation.name,
                "beforeHomeId" to beforeHome,
                "beforeLockId" to beforeLock
            )
        )

        val homeProbe = WallpaperDeepDiagnostics.begin(
            context,
            manager,
            WallpaperManager.FLAG_SYSTEM,
            "home-combined",
            prepared.bitmap,
            file
        )
        val lockProbe = WallpaperDeepDiagnostics.begin(
            context,
            manager,
            WallpaperManager.FLAG_LOCK,
            "lock",
            prepared.bitmap,
            file
        )

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
            WallpaperDeepDiagnostics.afterSet(homeProbe, returnedId)
            WallpaperDeepDiagnostics.afterSet(lockProbe, returnedId)
            val afterHome = runCatching { manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(-1)
            val afterLock = runCatching { manager.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrDefault(-1)
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
                    "height" to prepared.height
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
                fields = mapOf("beforeHomeId" to beforeHome, "beforeLockId" to beforeLock),
                throwable = failure
            )
            throw failure
        } finally {
            WallpaperDeepDiagnostics.end(lockProbe)
            WallpaperDeepDiagnostics.end(homeProbe)
            prepared.bitmap.recycle()
        }
    }

    /**
     * HONOR independent-pair compatibility path.
     *
     * Both bitmaps are fully decoded/cropped before the first WallpaperManager write.
     * The lock candidate is written with SYSTEM|LOCK (the method that visibly refreshes
     * the HONOR lock screen), then the independent Home candidate is restored
     * immediately with SYSTEM. There is no diagnostic sleep between the two writes.
     */
    fun applyHonorIndependentPair(
        context: Context,
        homeFile: File,
        lockFile: File,
        orientation: ResolvedOrientation
    ): CombinedWallpaperApplyResult {
        val manager = WallpaperManager.getInstance(context)
        assertWallpaperAllowed(manager)
        val beforeHome = runCatching { manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(-1)
        val beforeLock = runCatching { manager.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrDefault(-1)

        // Prepare both before touching either wallpaper so the temporary combined state
        // is as short as Android/OEM processing allows.
        val homePrepared = prepareBitmap(context, homeFile, orientation)
        val lockPrepared = prepareBitmap(context, lockFile, orientation)

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
                "beforeLockId" to beforeLock
            )
        )

        val homeProbe = WallpaperDeepDiagnostics.begin(
            context,
            manager,
            WallpaperManager.FLAG_SYSTEM,
            "home",
            homePrepared.bitmap,
            homeFile
        )
        val lockProbe = WallpaperDeepDiagnostics.begin(
            context,
            manager,
            WallpaperManager.FLAG_LOCK,
            "lock",
            lockPrepared.bitmap,
            lockFile
        )

        try {
            val combinedId = manager.setBitmap(
                lockPrepared.bitmap,
                null,
                false,
                WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            )
            if (combinedId <= 0) {
                error("WallpaperManager a refusé l'application combinée HONOR (ID retourné : $combinedId)")
            }

            // Restore Home immediately. Do not wait for deep probes/callbacks first.
            val homeReturnedId = manager.setBitmap(
                homePrepared.bitmap,
                null,
                false,
                WallpaperManager.FLAG_SYSTEM
            )
            if (homeReturnedId <= 0) {
                error("WallpaperManager a refusé la restauration du fond d'accueil (ID retourné : $homeReturnedId)")
            }

            WallpaperDeepDiagnostics.afterSetFast(lockProbe, combinedId)
            WallpaperDeepDiagnostics.afterSetFast(homeProbe, homeReturnedId)

            val afterHome = runCatching { manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(-1)
            val afterLock = runCatching { manager.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrDefault(-1)
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
                    "height" to homePrepared.height
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
                fields = mapOf("beforeHomeId" to beforeHome, "beforeLockId" to beforeLock),
                throwable = failure
            )
            throw failure
        } finally {
            WallpaperDeepDiagnostics.end(lockProbe)
            WallpaperDeepDiagnostics.end(homeProbe)
            lockPrepared.bitmap.recycle()
            homePrepared.bitmap.recycle()
        }
    }

    private data class PreparedBitmap(val bitmap: Bitmap, val width: Int, val height: Int)

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
