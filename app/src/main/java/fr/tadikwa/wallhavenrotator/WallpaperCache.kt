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

    fun countAll(): Int = rootDir().walkTopDown().count { it.isFile }

    @Synchronized
    fun refill(poolKey: String, profile: ProfileSettings, orientation: ResolvedOrientation) {
        val existing = loadQueue(poolKey).filter { fileFor(poolKey, it.fileName).isFile }.toMutableList()
        if (existing.size >= PoolPolicy.TARGET_SIZE) return

        val history = loadHistory().toMutableSet()
        val queuedIds = loadAllQueuedIds().toMutableSet()
        val pageKey = "page_$poolKey"
        val seedKey = "seed_$poolKey"
        var page = prefs.getInt(pageKey, 1).coerceAtLeast(1)
        var seed = prefs.getString(seedKey, null)
        var attempts = 0
        var lastFailure: Throwable? = null

        // One page normally fills a pool. Extra pages are used only when history or
        // another active pool already owns many results, keeping API usage bounded.
        while (existing.size < PoolPolicy.TARGET_SIZE && attempts < PoolPolicy.MAX_SEARCH_PAGES_PER_REFILL) {
            val result = try {
                client.search(profile, orientation, page = page, seed = seed)
            } catch (failure: Throwable) {
                lastFailure = failure
                break
            }
            attempts += 1
            if (profile.source == SourceMode.RANDOM && seed.isNullOrBlank()) seed = result.seed

            for (item in result.items) {
                if (existing.size >= PoolPolicy.TARGET_SIZE) break
                if (item.id in history || item.id in queuedIds) continue

                val extension = item.path.substringAfterLast('.', "jpg").substringBefore('?').lowercase()
                    .takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
                val fileName = "${item.id}.$extension"
                val destination = fileFor(poolKey, fileName)
                runCatching {
                    client.download(item, destination)
                    existing += CachedWallpaper(item.id, fileName)
                    queuedIds += item.id
                }.onFailure {
                    destination.delete()
                }
            }
            saveQueue(poolKey, existing)

            val lastPage = result.lastPage.coerceAtLeast(1)
            page += 1
            if (page > lastPage || page > 20) {
                page = 1
                if (profile.source == SourceMode.RANDOM) seed = null
                break
            }
            if (result.items.isEmpty()) break
        }

        prefs.edit()
            .putInt(pageKey, page)
            .putString(seedKey, seed)
            .apply()

        if (existing.isEmpty() && lastFailure != null) throw lastFailure
    }

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
    }

    private fun rootDir(): File = File(context.filesDir, "wallpapers").apply { mkdirs() }

    fun fileFor(poolKey: String, fileName: String): File =
        File(File(rootDir(), sanitize(poolKey)).apply { mkdirs() }, fileName)

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
                    if (fileFor(poolKey, obj.getString("file")).isFile) add(obj.getString("id"))
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
