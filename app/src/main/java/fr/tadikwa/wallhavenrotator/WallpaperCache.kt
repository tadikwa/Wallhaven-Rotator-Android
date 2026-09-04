package fr.tadikwa.wallhavenrotator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WallpaperCache(private val context: Context) {
    private val prefs = context.getSharedPreferences("wallhaven_cache", Context.MODE_PRIVATE)
    private val client = WallhavenClient()

    fun peek(poolKey: String, profile: ProfileSettings, orientation: ResolvedOrientation): CachedWallpaper? {
        var queue = loadQueue(poolKey).filter { fileFor(poolKey, it.fileName).isFile }
        if (queue.isEmpty()) {
            Diagnostics.log(
                context,
                "cache.miss",
                fields = mapOf("pool" to poolKey, "orientation" to orientation.name)
            )
            refill(poolKey, profile, orientation)
            queue = loadQueue(poolKey).filter { fileFor(poolKey, it.fileName).isFile }
        }
        return queue.firstOrNull()
    }

    fun consume(poolKey: String, wallpaper: CachedWallpaper) {
        val queue = loadQueue(poolKey).filterNot { it.id == wallpaper.id && it.fileName == wallpaper.fileName }
        saveQueue(poolKey, queue)
        addHistory(wallpaper.id)
        runCatching { fileFor(poolKey, wallpaper.fileName).delete() }
    }

    fun refillIfNeeded(poolKey: String, profile: ProfileSettings, orientation: ResolvedOrientation) {
        val count = loadQueue(poolKey).count { fileFor(poolKey, it.fileName).isFile }
        if (PoolPolicy.shouldRefill(count)) refill(poolKey, profile, orientation)
    }

    fun countAll(): Int = stats().files

    fun stats(): CacheStats {
        val files = rootDir().walkTopDown().filter { it.isFile }.toList()
        return CacheStats(files = files.size, bytes = files.sumOf { it.length() })
    }

    @Synchronized
    fun clearAll(): CacheStats {
        val before = stats()
        val root = rootDir()
        root.deleteRecursively()
        root.mkdirs()

        val editor = prefs.edit()
        prefs.all.keys.forEach { key ->
            if (key.startsWith("queue_") || key.startsWith("page_") || key.startsWith("seed_")) {
                editor.remove(key)
            }
        }
        editor.apply()

        Diagnostics.log(
            context,
            "cache.clear",
            fields = mapOf("filesBefore" to before.files, "bytesBefore" to before.bytes)
        )
        return stats()
    }

    @Synchronized
    fun maintenance(settings: AppSettings): CacheStats {
        pruneToSettings(settings)
        return stats()
    }

    @Synchronized
    fun refill(poolKey: String, profile: ProfileSettings, orientation: ResolvedOrientation) {
        val existing = loadQueue(poolKey).filter { fileFor(poolKey, it.fileName).isFile }.toMutableList()
        if (existing.size >= PoolPolicy.TARGET_SIZE) return

        val initialCount = existing.size
        val history = loadHistory().toMutableSet()
        val queuedIds = loadAllQueuedIds().toMutableSet()
        val pageKey = "page_$poolKey"
        val seedKey = "seed_$poolKey"
        val cacheLimitMb = SettingsRepository(context).load().cacheLimitMb
        var page = prefs.getInt(pageKey, 1).coerceAtLeast(1)
        var seed = prefs.getString(seedKey, null)
        var attempts = 0
        var downloadFailures = 0
        var lastFailure: Throwable? = null
        var diskBudgetReached = stats().bytes >= CachePolicy.refillStopBytes(cacheLimitMb) && existing.isNotEmpty()

        Diagnostics.log(
            context,
            "cache.refill.start",
            fields = mapOf(
                "pool" to poolKey,
                "existing" to existing.size,
                "source" to profile.source.name,
                "category" to profile.category.name,
                "contentFilter" to profile.contentFilter.name,
                "orientation" to orientation.name,
                "page" to page,
                "limitMb" to cacheLimitMb
            )
        )

        // One page normally fills a pool. Extra pages are used only when history or
        // another active pool already owns many results, keeping API usage bounded.
        while (
            existing.size < PoolPolicy.TARGET_SIZE &&
            attempts < PoolPolicy.MAX_SEARCH_PAGES_PER_REFILL &&
            !diskBudgetReached
        ) {
            val result = try {
                client.search(profile, orientation, page = page, seed = seed)
            } catch (failure: Throwable) {
                lastFailure = failure
                Diagnostics.log(
                    context,
                    "cache.search.failure",
                    level = "ERROR",
                    fields = mapOf("pool" to poolKey, "page" to page),
                    throwable = failure
                )
                break
            }
            attempts += 1
            if (profile.source == SourceMode.RANDOM && seed.isNullOrBlank()) seed = result.seed

            Diagnostics.log(
                context,
                "cache.search.success",
                fields = mapOf(
                    "pool" to poolKey,
                    "page" to page,
                    "items" to result.items.size,
                    "lastPage" to result.lastPage
                )
            )

            for (item in result.items) {
                if (existing.size >= PoolPolicy.TARGET_SIZE) break
                if (item.id in history || item.id in queuedIds) continue

                val extension = item.path.substringAfterLast('.', "jpg").substringBefore('?').lowercase()
                    .takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
                val fileName = "${item.id}.$extension"
                val destination = fileFor(poolKey, fileName)
                runCatching {
                    client.download(item, destination)
                    val hardLimitBytes = CachePolicy.limitBytes(cacheLimitMb)
                    if (destination.length() > hardLimitBytes) {
                        destination.delete()
                        error("Wallpaper trop volumineux pour la limite de cache configurée")
                    }

                    existing += CachedWallpaper(item.id, fileName)
                    queuedIds += item.id
                    saveQueue(poolKey, existing)

                    if (stats().bytes > hardLimitBytes) {
                        enforceLimit(cacheLimitMb)
                        existing.removeAll { !rawFileFor(poolKey, it.fileName).isFile }
                    }
                    if (existing.isNotEmpty() && stats().bytes >= CachePolicy.refillStopBytes(cacheLimitMb)) {
                        diskBudgetReached = true
                    }
                }.onFailure { failure ->
                    destination.delete()
                    existing.removeAll { it.id == item.id && it.fileName == fileName }
                    saveQueue(poolKey, existing)
                    downloadFailures += 1
                    lastFailure = failure
                    Diagnostics.log(
                        context,
                        "cache.download.failure",
                        level = "WARN",
                        fields = mapOf("pool" to poolKey, "wallhavenId" to item.id),
                        throwable = failure
                    )
                }
                if (diskBudgetReached) break
            }
            saveQueue(poolKey, existing)

            val lastPage = result.lastPage.coerceAtLeast(1)
            page += 1
            if (page > lastPage || page > 20) {
                page = 1
                if (profile.source == SourceMode.RANDOM) seed = null
                break
            }
            if (diskBudgetReached || result.items.isEmpty()) break
        }

        prefs.edit()
            .putInt(pageKey, page)
            .putString(seedKey, seed)
            .apply()

        cleanupOrphans()
        enforceLimit(cacheLimitMb)
        val finalQueue = loadQueue(poolKey).filter { rawFileFor(poolKey, it.fileName).isFile }

        Diagnostics.log(
            context,
            "cache.refill.finish",
            level = if (finalQueue.isEmpty()) "WARN" else "INFO",
            fields = mapOf(
                "pool" to poolKey,
                "before" to initialCount,
                "after" to finalQueue.size,
                "attempts" to attempts,
                "downloadFailures" to downloadFailures,
                "diskBudgetReached" to diskBudgetReached,
                "cacheBytes" to stats().bytes
            )
        )

        if (finalQueue.isEmpty()) {
            val failure = lastFailure
            if (failure != null) throw failure
            error("Wallhaven n'a renvoyé aucun wallpaper utilisable pour ce profil")
        }
    }

    @Synchronized
    fun pruneToSettings(settings: AppSettings) {
        val active = PoolKeys.active(settings)
        rootDir().listFiles()?.filter { it.isDirectory && it.name !in active }?.forEach { it.deleteRecursively() }

        val editor = prefs.edit()
        prefs.all.keys.forEach { key ->
            val poolKey = when {
                key.startsWith("queue_") -> key.removePrefix("queue_")
                key.startsWith("page_") -> key.removePrefix("page_")
                key.startsWith("seed_") -> key.removePrefix("seed_")
                else -> null
            }
            if (poolKey != null && poolKey !in active) editor.remove(key)
        }
        editor.apply()

        cleanupOrphans()
        enforceLimit(settings.cacheLimitMb)
    }

    private fun cleanupOrphans() {
        val queuedPaths = buildSet {
            prefs.all.forEach { (key, value) ->
                if (!key.startsWith("queue_") || value !is String) return@forEach
                val poolKey = key.removePrefix("queue_")
                runCatching {
                    val arr = JSONArray(value)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        add(rawFileFor(poolKey, obj.getString("file")).absolutePath)
                    }
                }
            }
        }

        val root = rootDir()
        var deleted = 0
        var bytes = 0L
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            if (file.absolutePath !in queuedPaths) {
                bytes += file.length()
                if (file.delete()) deleted += 1
            }
        }
        root.walkBottomUp().filter { it.isDirectory && it != root }.forEach { dir ->
            if (dir.listFiles().isNullOrEmpty()) dir.delete()
        }
        pruneMissingQueueEntries()

        if (deleted > 0) {
            Diagnostics.log(
                context,
                "cache.orphan_cleanup",
                fields = mapOf("filesDeleted" to deleted, "bytesDeleted" to bytes)
            )
        }
    }

    private fun enforceLimit(limitMb: Int) {
        val limitBytes = CachePolicy.limitBytes(limitMb)
        val files = rootDir().walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toMutableList()
        var total = files.sumOf { it.length() }
        if (total <= limitBytes) return

        var deleted = 0
        var deletedBytes = 0L
        for (file in files) {
            if (total <= limitBytes) break
            val length = file.length()
            if (file.delete()) {
                total -= length
                deletedBytes += length
                deleted += 1
            }
        }
        pruneMissingQueueEntries()
        Diagnostics.log(
            context,
            "cache.limit_enforced",
            fields = mapOf(
                "limitMb" to CachePolicy.normalizeLimitMb(limitMb),
                "filesDeleted" to deleted,
                "bytesDeleted" to deletedBytes,
                "bytesAfter" to total
            )
        )
    }

    private fun pruneMissingQueueEntries() {
        val queueKeys = prefs.all.keys.filter { it.startsWith("queue_") }
        queueKeys.forEach { key ->
            val poolKey = key.removePrefix("queue_")
            val filtered = loadQueue(poolKey).filter { rawFileFor(poolKey, it.fileName).isFile }
            saveQueue(poolKey, filtered)
        }
    }

    private fun rootDir(): File = File(context.filesDir, "wallpapers").apply { mkdirs() }

    fun fileFor(poolKey: String, fileName: String): File =
        File(File(rootDir(), sanitize(poolKey)).apply { mkdirs() }, fileName)

    private fun rawFileFor(poolKey: String, fileName: String): File =
        File(File(rootDir(), sanitize(poolKey)), fileName)

    private fun loadQueue(poolKey: String): List<CachedWallpaper> {
        val raw = prefs.getString("queue_$poolKey", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(CachedWallpaper(obj.getString("id"), obj.getString("file")))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveQueue(poolKey: String, queue: List<CachedWallpaper>) {
        val arr = JSONArray()
        queue.forEach {
            arr.put(JSONObject().put("id", it.id).put("file", it.fileName))
        }
        prefs.edit().putString("queue_$poolKey", arr.toString()).apply()
    }

    private fun loadAllQueuedIds(): Set<String> = buildSet {
        prefs.all.forEach { (key, value) ->
            if (!key.startsWith("queue_") || value !is String) return@forEach
            val poolKey = key.removePrefix("queue_")
            runCatching {
                val arr = JSONArray(value)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (rawFileFor(poolKey, obj.getString("file")).isFile) add(obj.getString("id"))
                }
            }
        }
    }

    private fun loadHistory(): List<String> {
        val raw = prefs.getString("history", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }
        }.getOrDefault(emptyList())
    }

    private fun addHistory(id: String) {
        val history = loadHistory().filterNot { it == id }.toMutableList()
        history.add(0, id)
        if (history.size > PoolPolicy.HISTORY_LIMIT) {
            history.subList(PoolPolicy.HISTORY_LIMIT, history.size).clear()
        }
        val arr = JSONArray()
        history.forEach(arr::put)
        prefs.edit().putString("history", arr.toString()).apply()
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
