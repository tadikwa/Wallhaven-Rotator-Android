package fr.tadikwa.wallhavenrotator

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread


data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val apkName: String,
    val apkUrl: String,
    val sha256: String,
    val releaseUrl: String,
    val prerelease: Boolean
)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class Available(val update: UpdateInfo) : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

object UpdateManager {
    private const val OWNER = "tadikwa"
    private const val REPOSITORY = "Wallhaven-Rotator-Android"
    private const val MANIFEST_ASSET = "Wallhaven-Rotator-Android-update.json"
    private const val PREFS = "wallhaven_rotator_updates"
    private const val LAST_AUTO_CHECK = "last_auto_check"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val MAX_RELEASES = 10

    private val mainHandler = Handler(Looper.getMainLooper())

    fun shouldAutoCheck(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(LAST_AUTO_CHECK, 0L)
        return System.currentTimeMillis() - last >= AUTO_CHECK_INTERVAL_MS
    }

    fun checkAsync(context: Context, manual: Boolean, callback: (UpdateCheckResult) -> Unit) {
        if (!manual && !shouldAutoCheck(context)) return
        val appContext = context.applicationContext
        thread(name = "wallhaven-update-check") {
            val result = runCatching { checkForUpdate() }
                .getOrElse { UpdateCheckResult.Failed(it.message ?: "Échec de la vérification") }
            if (result !is UpdateCheckResult.Failed) {
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(LAST_AUTO_CHECK, System.currentTimeMillis())
                    .apply()
            }
            mainHandler.post { callback(result) }
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun downloadAndInstallAsync(context: Context, update: UpdateInfo, callback: (Result<Unit>) -> Unit) {
        val appContext = context.applicationContext
        thread(name = "wallhaven-update-download") {
            val result = runCatching {
                val apk = downloadAndVerify(appContext, update)
                mainHandler.post { launchInstaller(appContext, apk) }
                Unit
            }
            mainHandler.post { callback(result) }
        }
    }

    private fun checkForUpdate(): UpdateCheckResult {
        val allowPrereleases = BuildConfig.VERSION_NAME.contains('-')
        val releasesUrl = "https://api.github.com/repos/$OWNER/$REPOSITORY/releases?per_page=$MAX_RELEASES"
        val releases = JSONArray(readText(releasesUrl, githubApi = true))

        for (index in 0 until releases.length()) {
            val release = releases.getJSONObject(index)
            if (release.optBoolean("draft", false)) continue
            val prerelease = release.optBoolean("prerelease", false)
            if (prerelease && !allowPrereleases) continue

            val assets = release.optJSONArray("assets") ?: continue
            val manifestAsset = findAsset(assets, MANIFEST_ASSET) ?: continue
            val manifest = JSONObject(readText(manifestAsset.getString("browser_download_url")))
            if (manifest.optInt("schema", 0) != 1) continue
            if (manifest.optString("applicationId") != BuildConfig.APPLICATION_ID) continue

            val versionCode = manifest.optLong("versionCode", -1L)
            if (versionCode <= BuildConfig.VERSION_CODE.toLong()) continue

            val apkName = manifest.optString("apk")
            if (apkName.isBlank() || File(apkName).name != apkName) continue
            val apkAsset = findAsset(assets, apkName) ?: continue
            val sha256 = manifest.optString("sha256").lowercase()
            if (!sha256.matches(Regex("[0-9a-f]{64}"))) continue

            return UpdateCheckResult.Available(
                UpdateInfo(
                    versionName = manifest.optString("versionName", release.optString("tag_name").removePrefix("v")),
                    versionCode = versionCode,
                    apkName = apkName,
                    apkUrl = apkAsset.getString("browser_download_url"),
                    sha256 = sha256,
                    releaseUrl = release.optString("html_url"),
                    prerelease = prerelease
                )
            )
        }
        return UpdateCheckResult.UpToDate
    }

    private fun findAsset(assets: JSONArray, name: String): JSONObject? {
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name") == name) return asset
        }
        return null
    }

    private fun downloadAndVerify(context: Context, update: UpdateInfo): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.forEach { if (it.name != update.apkName) it.delete() }
        val partial = File(directory, "${update.apkName}.part")
        val target = File(directory, update.apkName)
        partial.delete()
        target.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        openConnection(update.apkUrl).useConnection { connection ->
            partial.outputStream().buffered().use { output ->
                connection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        }

        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actualSha.equals(update.sha256, ignoreCase = true)) {
            partial.delete()
            error("SHA-256 de l'APK invalide")
        }
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        verifyArchive(context, target, update)
        return target
    }

    private fun verifyArchive(context: Context, apk: File, update: UpdateInfo) {
        val manager = context.packageManager
        val archive = getArchiveInfo(manager, apk) ?: error("APK téléchargé illisible")
        if (archive.packageName != BuildConfig.APPLICATION_ID) {
            apk.delete()
            error("L'APK téléchargé ne correspond pas à l'application")
        }
        if (versionCodeOf(archive) != update.versionCode) {
            apk.delete()
            error("VersionCode de l'APK inattendu")
        }

        val installed = getInstalledInfo(manager, context.packageName)
        val installedSigners = signerDigests(installed)
        val archiveSigners = signerDigests(archive)
        if (installedSigners.isEmpty() || archiveSigners.isEmpty() || installedSigners != archiveSigners) {
            apk.delete()
            error(
                "Signature APK différente. Une alpha debug doit être désinstallée avant la première version signée ; " +
                    "les mises à jour suivantes utiliseront ensuite la même clé."
            )
        }
    }

    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun versionCodeOf(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun getInstalledInfo(manager: PackageManager, packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(
                packageName,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                }
            )
        }
    }

    private fun getArchiveInfo(manager: PackageManager, apk: File): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageArchiveInfo(
                apk.absolutePath,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                }
            )
        }
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
            else signingInfo.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun readText(url: String, githubApi: Boolean = false): String =
        openConnection(url, githubApi).useConnection { connection ->
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

    private fun openConnection(url: String, githubApi: Boolean = false): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Wallhaven-Rotator-Android/${BuildConfig.VERSION_NAME}")
        if (githubApi) {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            error("HTTP $code lors de la vérification des mises à jour")
        }
        return connection
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }
}
