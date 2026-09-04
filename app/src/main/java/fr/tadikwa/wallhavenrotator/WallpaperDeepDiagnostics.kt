package fr.tadikwa.wallhavenrotator

import android.app.KeyguardManager
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Extra non-privileged diagnostics around WallpaperManager writes.
 *
 * Android 14+ no longer lets a normal third-party app read the actual wallpaper
 * bitmap back from WallpaperManager, so this probe deliberately uses only public,
 * non-privileged signals: wallpaper IDs, WallpaperColors, callbacks/broadcasts,
 * screen/keyguard state, and a local copy of the exact cropped bitmap we submitted.
 */
object WallpaperDeepDiagnostics {
    private const val EXPORT_DIR = "diagnostics-export"
    private const val LOCK_PREVIEW = "last-lock-submitted.jpg"
    private const val HOME_PREVIEW = "last-home-submitted.jpg"

    data class Snapshot(
        val wallpaperId: Int,
        val colors: String,
        val wallpaperInfo: String,
        val keyguardLocked: Boolean,
        val deviceLocked: Boolean,
        val interactive: Boolean,
        val desiredWidth: Int,
        val desiredHeight: Int
    ) {
        fun compact(): String = buildString {
            append("id=$wallpaperId")
            append(",colors=$colors")
            append(",info=$wallpaperInfo")
            append(",keyguardLocked=$keyguardLocked")
            append(",deviceLocked=$deviceLocked")
            append(",interactive=$interactive")
            append(",desired=${desiredWidth}x${desiredHeight}")
        }
    }

    class Probe internal constructor(
        internal val context: Context,
        internal val manager: WallpaperManager,
        internal val which: Int,
        internal val destination: String,
        internal val startedAt: Long,
        internal val receiver: BroadcastReceiver?,
        internal val colorsListener: Any?
    )

    fun begin(
        context: Context,
        manager: WallpaperManager,
        which: Int,
        destination: String,
        bitmap: Bitmap,
        sourceFile: File
    ): Probe {
        val appContext = context.applicationContext
        val startedAt = SystemClock.elapsedRealtime()
        val before = snapshot(appContext, manager, which)
        val preview = saveSubmittedPreview(appContext, destination, bitmap)
        val sourceSha = sha256(sourceFile)
        val previewSha = preview?.let(::sha256).orEmpty()
        val expectedColors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            Api27.colorsToString(WallpaperColors.fromBitmap(bitmap))
        } else {
            "unsupported"
        }

        Diagnostics.log(
            appContext,
            "wallpaper.deep.begin",
            fields = mapOf(
                "destination" to destination,
                "sourceFile" to sourceFile.name,
                "sourceSha256" to sourceSha,
                "submittedPreview" to (preview?.name ?: ""),
                "submittedPreviewSha256" to previewSha,
                "expectedColors" to expectedColors,
                "before" to before.compact()
            )
        )

        val receiver = registerTransientReceiver(appContext, destination, startedAt)
        val listener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            Api27.registerColorsListener(appContext, manager, destination, startedAt)
        } else {
            null
        }
        return Probe(appContext, manager, which, destination, startedAt, receiver, listener)
    }

    fun afterSet(probe: Probe, returnedId: Int) {
        logSnapshot(probe, "immediate", returnedId)

        // Deep diagnostic path retained for targeted troubleshooting only.
        if (probe.which == WallpaperManager.FLAG_LOCK) {
            Thread.sleep(500)
            logSnapshot(probe, "+500ms", returnedId)
            Thread.sleep(2000)
            logSnapshot(probe, "+2500ms", returnedId)
        }
    }

    /** Lightweight snapshot for normal operation. No diagnostic sleeps. */
    fun afterSetFast(probe: Probe, returnedId: Int) {
        logSnapshot(probe, "immediate-fast", returnedId)
    }

    fun end(probe: Probe) {
        probe.receiver?.let { receiver ->
            runCatching { probe.context.unregisterReceiver(receiver) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && probe.colorsListener != null) {
            Api27.unregisterColorsListener(probe.manager, probe.colorsListener)
        }
        Diagnostics.log(
            probe.context,
            "wallpaper.deep.end",
            fields = mapOf(
                "destination" to probe.destination,
                "elapsedMs" to (SystemClock.elapsedRealtime() - probe.startedAt)
            )
        )
    }

    fun snapshotSummary(context: Context, which: Int): String {
        val manager = WallpaperManager.getInstance(context.applicationContext)
        return snapshot(context.applicationContext, manager, which).compact()
    }

    fun latestPreviewFiles(context: Context): List<File> {
        val dir = exportDir(context.applicationContext)
        return listOf(File(dir, LOCK_PREVIEW), File(dir, HOME_PREVIEW)).filter { it.isFile }
    }

    private fun logSnapshot(probe: Probe, phase: String, returnedId: Int) {
        val snapshot = snapshot(probe.context, probe.manager, probe.which)
        Diagnostics.log(
            probe.context,
            "wallpaper.deep.snapshot",
            fields = mapOf(
                "destination" to probe.destination,
                "phase" to phase,
                "elapsedMs" to (SystemClock.elapsedRealtime() - probe.startedAt),
                "returnedId" to returnedId,
                "state" to snapshot.compact()
            )
        )
    }

    private fun snapshot(context: Context, manager: WallpaperManager, which: Int): Snapshot {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching { Api27.colorsToString(manager.getWallpaperColors(which)) }
                .getOrElse { "error:${it.javaClass.simpleName}:${it.message.orEmpty()}" }
        } else {
            "unsupported"
        }
        val wallpaperInfo = if (Build.VERSION.SDK_INT >= 34) {
            runCatching { manager.getWallpaperInfo(which)?.toString() ?: "static-or-unavailable" }
                .getOrElse { "error:${it.javaClass.simpleName}:${it.message.orEmpty()}" }
        } else {
            "api<34"
        }
        return Snapshot(
            wallpaperId = runCatching { manager.getWallpaperId(which) }.getOrDefault(-1),
            colors = colors,
            wallpaperInfo = wallpaperInfo,
            keyguardLocked = runCatching { keyguard?.isKeyguardLocked ?: false }.getOrDefault(false),
            deviceLocked = runCatching { keyguard?.isDeviceLocked ?: false }.getOrDefault(false),
            interactive = runCatching { power?.isInteractive ?: false }.getOrDefault(false),
            desiredWidth = runCatching { manager.desiredMinimumWidth }.getOrDefault(-1),
            desiredHeight = runCatching { manager.desiredMinimumHeight }.getOrDefault(-1)
        )
    }

    private fun registerTransientReceiver(
        context: Context,
        destination: String,
        startedAt: Long
    ): BroadcastReceiver? = runCatching {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val extras = intent?.extras?.keySet()?.sorted()?.joinToString(",") { key ->
                    val value = runCatching { intent.extras?.get(key) }.getOrNull()
                    "$key=$value"
                }.orEmpty()
                Diagnostics.log(
                    context,
                    "wallpaper.deep.broadcast",
                    fields = mapOf(
                        "destinationProbe" to destination,
                        "action" to (intent?.action ?: ""),
                        "elapsedMs" to (SystemClock.elapsedRealtime() - startedAt),
                        "extras" to extras
                    )
                )
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_WALLPAPER_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        receiver
    }.onFailure { failure ->
        Diagnostics.log(
            context,
            "wallpaper.deep.receiver_unavailable",
            level = "WARN",
            fields = mapOf("destinationProbe" to destination),
            throwable = failure
        )
    }.getOrNull()

    private fun saveSubmittedPreview(context: Context, destination: String, bitmap: Bitmap): File? =
        runCatching {
            val file = File(exportDir(context), if (destination == "lock") LOCK_PREVIEW else HOME_PREVIEW)
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    "Bitmap.compress returned false"
                }
            }
            file
        }.onFailure { failure ->
            Diagnostics.log(
                context,
                "wallpaper.deep.preview_failure",
                level = "WARN",
                fields = mapOf("destination" to destination),
                throwable = failure
            )
        }.getOrNull()

    private fun exportDir(context: Context): File =
        File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }

    private fun sha256(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private object Api27 {
        fun colorsToString(colors: WallpaperColors?): String {
            if (colors == null) return "null"
            fun hex(color: android.graphics.Color?): String =
                color?.toArgb()?.let { String.format("#%08X", it) } ?: "null"
            return "primary=${hex(colors.primaryColor)};secondary=${hex(colors.secondaryColor)};tertiary=${hex(colors.tertiaryColor)};hints=${colors.colorHints}"
        }

        fun registerColorsListener(
            context: Context,
            manager: WallpaperManager,
            destination: String,
            startedAt: Long
        ): Any {
            val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
                Diagnostics.log(
                    context,
                    "wallpaper.deep.colors_changed",
                    fields = mapOf(
                        "destinationProbe" to destination,
                        "changedWhich" to which,
                        "elapsedMs" to (SystemClock.elapsedRealtime() - startedAt),
                        "colors" to colorsToString(colors)
                    )
                )
            }
            manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
            return listener
        }

        fun unregisterColorsListener(manager: WallpaperManager, listener: Any) {
            runCatching {
                manager.removeOnColorsChangedListener(listener as WallpaperManager.OnColorsChangedListener)
            }
        }
    }
}
