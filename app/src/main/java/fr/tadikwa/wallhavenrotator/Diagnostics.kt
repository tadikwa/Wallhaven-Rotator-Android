package fr.tadikwa.wallhavenrotator

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Diagnostics {
    private const val DIRECTORY = "diagnostics"
    private const val CURRENT_LOG = "wallhaven-rotator.log"
    private const val MAX_LOG_BYTES = 512L * 1024L
    private const val BACKUP_COUNT = 2

    @Synchronized
    fun log(
        context: Context,
        event: String,
        level: String = "INFO",
        message: String? = null,
        fields: Map<String, Any?> = emptyMap(),
        throwable: Throwable? = null
    ) {
        runCatching {
            val appContext = context.applicationContext
            val directory = logDirectory(appContext)
            rotateIfNeeded(directory)
            val line = JSONObject().apply {
                put("timestamp", timestamp())
                put("level", level)
                put("event", event)
                put("version", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
                if (!message.isNullOrBlank()) put("message", message)
                fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
                if (throwable != null) {
                    put("exception", throwable.javaClass.name)
                    put("exceptionMessage", throwable.message ?: "")
                    put("stack", Log.getStackTraceString(throwable).take(16_000))
                }
            }
            File(directory, CURRENT_LOG).appendText(line.toString() + "\n", Charsets.UTF_8)
        }
    }

    fun createReport(context: Context, settings: AppSettings): File {
        val appContext = context.applicationContext
        val exportDir = File(appContext.cacheDir, "diagnostics-export").apply { mkdirs() }
        exportDir.listFiles()?.forEach { old ->
            if (System.currentTimeMillis() - old.lastModified() > 24L * 60L * 60L * 1000L) old.delete()
        }
        val output = File(
            exportDir,
            "Wallhaven-Rotator-diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        )
        val manager = WallpaperManager.getInstance(appContext)
        val homeId = runCatching { manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.getOrDefault(-1)
        val lockId = runCatching { manager.getWallpaperId(WallpaperManager.FLAG_LOCK) }.getOrDefault(-1)
        val cache = WallpaperCache(appContext)

        val periodic = readWorkInfo(appContext, "wallhaven_rotation")
        val preload = readWorkInfo(appContext, "wallhaven_preload")

        val report = buildString {
            appendLine("Wallhaven Rotator Android - diagnostics")
            appendLine("Generated: ${timestamp()}")
            appendLine()
            appendLine("[Application]")
            appendLine("version=${BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${BuildConfig.VERSION_CODE}")
            appendLine("applicationId=${BuildConfig.APPLICATION_ID}")
            appendLine()
            appendLine("[Device]")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("brand=${Build.BRAND}")
            appendLine("model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE}")
            appendLine("androidRelease=${Build.VERSION.RELEASE}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("locale=${Locale.getDefault().toLanguageTag()}")
            appendLine()
            appendLine("[Settings]")
            appendLine("enabled=${settings.enabled}")
            appendLine("intervalMinutes=${settings.intervalMinutes}")
            appendLine("target=${settings.targetMode.name}")
            appendLine("orientation=${settings.orientationMode.name}")
            appendLine("cacheLimitMb=${settings.cacheLimitMb}")
            appendLine("homeSource=${settings.homeProfile.source.name}")
            appendLine("homeCategory=${settings.homeProfile.category.name}")
            appendLine("homeQuery=${settings.homeProfile.query}")
            appendLine("homeContentFilter=${settings.homeProfile.contentFilter.name}")
            appendLine("lockSource=${settings.lockProfile.source.name}")
            appendLine("lockCategory=${settings.lockProfile.category.name}")
            appendLine("lockQuery=${settings.lockProfile.query}")
            appendLine("lockContentFilter=${settings.lockProfile.contentFilter.name}")
            appendLine()
            appendLine("[WallpaperManager]")
            appendLine("wallpaperSupported=${runCatching { manager.isWallpaperSupported() }.getOrDefault(false)}")
            appendLine("setWallpaperAllowed=${runCatching { manager.isSetWallpaperAllowed() }.getOrDefault(false)}")
            appendLine("homeWallpaperId=$homeId")
            appendLine("lockWallpaperId=$lockId")
            appendLine()
            appendLine("[Cache]")
            val cacheStats = cache.stats()
            appendLine("files=${cacheStats.files}")
            appendLine("bytes=${cacheStats.bytes}")
            appendLine("limitBytes=${CachePolicy.limitBytes(settings.cacheLimitMb)}")
            appendLine()
            appendLine("[WorkManager]")
            appendLine("periodic=$periodic")
            appendLine("preload=$preload")
            appendLine()
            appendLine("[Logs]")
            logFiles(appContext).forEach { file ->
                appendLine()
                appendLine("--- ${file.name} ---")
                append(file.readText(Charsets.UTF_8))
                appendLine()
            }
        }
        output.writeText(report, Charsets.UTF_8)
        log(appContext, "diagnostics.export", fields = mapOf("file" to output.name, "bytes" to output.length()))
        return output
    }

    fun shareIntent(context: Context, report: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            report
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Wallhaven Rotator - diagnostics")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun readWorkInfo(context: Context, uniqueName: String): String = runCatching {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(uniqueName)
            .get(2, TimeUnit.SECONDS)
        if (infos.isEmpty()) "none" else infos.joinToString(" | ") { info -> formatWorkInfo(info) }
    }.getOrElse { "unavailable:${it.javaClass.simpleName}:${it.message.orEmpty()}" }

    private fun formatWorkInfo(info: WorkInfo): String =
        "id=${info.id},state=${info.state},attempt=${info.runAttemptCount}"

    private fun logDirectory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply { mkdirs() }

    private fun logFiles(context: Context): List<File> {
        val directory = logDirectory(context)
        val files = mutableListOf<File>()
        for (index in BACKUP_COUNT downTo 1) {
            val file = File(directory, "$CURRENT_LOG.$index")
            if (file.isFile) files += file
        }
        File(directory, CURRENT_LOG).takeIf { it.isFile }?.let(files::add)
        return files
    }

    private fun rotateIfNeeded(directory: File) {
        val current = File(directory, CURRENT_LOG)
        if (!current.isFile || current.length() < MAX_LOG_BYTES) return
        File(directory, "$CURRENT_LOG.$BACKUP_COUNT").delete()
        for (index in BACKUP_COUNT - 1 downTo 1) {
            val source = File(directory, "$CURRENT_LOG.$index")
            if (source.isFile) source.renameTo(File(directory, "$CURRENT_LOG.${index + 1}"))
        }
        current.renameTo(File(directory, "$CURRENT_LOG.1"))
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
}
