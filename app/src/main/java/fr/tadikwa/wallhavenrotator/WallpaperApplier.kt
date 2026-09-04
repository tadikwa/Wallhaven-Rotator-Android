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

        if (!manager.isWallpaperSupported()) {
            error("Android indique que les fonds d'écran ne sont pas pris en charge sur cet appareil")
        }
        if (!manager.isSetWallpaperAllowed()) {
            error("Android interdit actuellement à l'application de modifier le fond d'écran")
        }

        val (targetWidth, targetHeight) = targetSize(context, orientation)
        val bitmap = decodeSampled(file, targetWidth * 2, targetHeight * 2)
            ?: error("Impossible de décoder ${file.name}")
        val cropped = centerCrop(bitmap, targetWidth, targetHeight)
        if (cropped !== bitmap) bitmap.recycle()

        val probe = WallpaperDeepDiagnostics.begin(
            context = context,
            manager = manager,
            which = which,
            destination = destination,
            bitmap = cropped,
            sourceFile = file
        )

        try {
            val returnedId = manager.setBitmap(cropped, null, false, which)
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
                    "width" to targetWidth,
                    "height" to targetHeight
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
                    "beforeId" to beforeId
                ),
                throwable = failure
            )
            throw failure
        } finally {
            WallpaperDeepDiagnostics.end(probe)
            cropped.recycle()
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
