package fr.tadikwa.wallhavenrotator

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WallpaperCache(private val context: Context) {
    private companion object {
        val CACHE_LOCK = Any()
    }

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
            refill(poolKey, profile, orientation, PoolPolicy.CACHE_MISS_TARGET_SIZE)
            queue = loadQueue(poolKey).filter { fileFor(poolKey, it.fileName).isFile }
        }
        return queue.firstOrNull()
    }

    /**
     * Automatic rotations are never allowed to enter refill(): refill intentionally
     * owns CACHE_LOCK while it performs Wallhaven I/O. Waiting on that lock is exactly
     * what made an alpha.18 alarm stall before WallpaperManager while a preload socket
     * was suspended by MagicOS.
     */
    fun peekCachedOnly(poolKey: String): CachedWallpaper? =
        loadQueue(poolKey).firstOrNull { rawFileFor(poolKey, it.fileName).isFile }

    /**
     * Post-apply bookkeeping for the automatic path must not wait behind a preload.
     * Delete the consumed file and leave queue compaction to the next maintenance or
     * preload pass. peekCachedOnly() already ignores missing files, so a stale queue
     * entry can never be selected again.
     */
    fun consumeAfterAutomaticApply(poolKey: String, wallpaper: CachedWallpaper) {
        runCatching { addHistory(wallpaper.id) }
            .onFailure { failure ->
                Diagnostics.log(
                    context,
                    "cache.automatic_history_failure",
                    level = "WARN",
                    fields = mapOf("pool" to poolKey, "wallhavenId" to wallpaper.id),
                    throwable = failure
                )
            }

        val deleted = runCatching { rawFileFor(poolKey, wallpaper.fileName).delete() }
            .getOrDefault(false)
        Diagnostics.log(
            context,
            "cache.automatic_consumed",
            fields = mapOf(
                "pool" to poolKey,
                "wallhavenId" to wallpaper.id,
                "file" to wallpaper.fileName,
                "fileDeleted" to deleted
            )
        )
    }

    fun consume(poolKey: String, wallpaper: CachedWallpaper) = synchronized(CACHE_LOCK) {
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

    fun clearAll(): CacheStats = synchronized(CACHE_LOCK) {
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
        stats()
    }

    fun maintenance(settings: AppSettings): CacheStats = synchronized(CACHE_LOCK) {
        pruneToSettingsLocked(settings)
        stats()
    }

    fun refill(
        poolKey: String,
        profile: ProfileSettings,
        orientation: ResolvedOrientation,
        targetSize: Int = PoolPolicy.TARGET_SIZE,
        shouldStop: () -> Boolean = { false }
    ) = synchronized(CACHE_LOCK) {
        refillLocked(poolKey, profile, orientation, targetSize, shouldStop)
    }

    private fun refillLocked(
        poolKey: String,
        profile: ProfileSettings,
        orientation: ResolvedOrientation,
        targetSize: Int,
        shouldStop: () -> Boolean
    ) {
        val requestedTarget = targetSize.coerceIn(1, PoolPolicy.TARGET_SIZE)
        val existing = loadQueue(poolKey).filter { fileFor(poolKey, it.fileName).isFile }.toMutableList()
        if (existing.size >= requestedTarget) return

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
        var metadataChecks = 0
        var contentRejected = 0
        var metadataLimitReached = false
        var broadSearchFallbacks = 0
        var lastFailure: Throwable? = null
        var abortRefill = false
        var stoppedEarly = false
        var diskBudgetReached = stats().bytes >= CachePolicy.refillStopBytes(cacheLimitMb) && existing.isNotEmpty()

        fun stopRequested(): Boolean {
            if (!stoppedEarly && shouldStop()) stoppedEarly = true
            return stoppedEarly
        }

        if (stopRequested()) {
            Diagnostics.log(
                context,
                "cache.refill.interrupted",
                fields = mapOf("pool" to poolKey, "phase" to "before_start")
            )
            return
        }

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
                "limitMb" to cacheLimitMb,
                "targetSize" to requestedTarget,
                "effectiveQuery" to ContentFilterPolicy.compose(profile.query, profile.contentFilter).take(512)
            )
        )

        // One page normally fills a pool. Extra pages are used only when history or
        // another active pool already owns many results, keeping API usage bounded.
        while (
            existing.size < requestedTarget &&
            attempts < PoolPolicy.MAX_SEARCH_PAGES_PER_REFILL &&
            !diskBudgetReached &&
            !abortRefill &&
            !stopRequested()
        ) {
            var result = try {
                client.search(profile, orientation, page = page, seed = seed)
            } catch (failure: Throwable) {
                lastFailure = failure
                abortRefill = shouldAbortRefill(failure)
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

            // Query-side exclusions are only a first-pass optimisation. Some narrow
            // Wallhaven listings can collapse to zero when negatives are combined.
            // Retry the same SFW listing without automatic negative terms and keep the
            // ORIGINAL profile for metadata inspection, so Reduced/Strict semantics
            // remain enforced locally instead of failing with "no wallpaper".
            val filteredQuery = ContentFilterPolicy.compose(profile.query, profile.contentFilter)
            if (
                result.items.isEmpty() &&
                profile.contentFilter != ContentFilterMode.STANDARD &&
                filteredQuery != profile.query.trim()
            ) {
                Diagnostics.log(
                    context,
                    "cache.search.filtered_empty_fallback",
                    level = "WARN",
                    fields = mapOf(
                        "pool" to poolKey,
                        "page" to page,
                        "contentFilter" to profile.contentFilter.name,
                        "filteredQuery" to filteredQuery.take(512),
                        "fallbackQuery" to profile.query.trim().take(512)
                    )
                )
                val broadProfile = profile.copy(contentFilter = ContentFilterMode.STANDARD)
                result = try {
                    client.search(broadProfile, orientation, page = page, seed = seed)
                } catch (failure: Throwable) {
                    lastFailure = failure
                    abortRefill = shouldAbortRefill(failure)
                    Diagnostics.log(
                        context,
                        "cache.search.fallback_failure",
                        level = "ERROR",
                        fields = mapOf("pool" to poolKey, "page" to page),
                        throwable = failure
                    )
                    break
                }
                broadSearchFallbacks += 1
            }

            if (stopRequested()) break
            if (profile.source == SourceMode.RANDOM && seed.isNullOrBlank()) seed = result.seed

            Diagnostics.log(
                context,
                "cache.search.success",
                fields = mapOf(
                    "pool" to poolKey,
                    "page" to page,
                    "items" to result.items.size,
                    "lastPage" to result.lastPage,
                    "broadFallback" to (broadSearchFallbacks > 0)
                )
            )

            for (item in result.items) {
                if (stopRequested()) break
                if (existing.size >= requestedTarget) break
                if (item.id in history || item.id in queuedIds) continue

                if (ContentFilterPolicy.requiresMetadataInspection(profile.contentFilter)) {
                    if (metadataChecks >= ContentFilterPolicy.MAX_METADATA_CHECKS_PER_REFILL) {
                        metadataLimitReached = true
                        Diagnostics.log(
                            context,
                            "content.metadata_limit",
                            level = "WARN",
                            fields = mapOf(
                                "pool" to poolKey,
                                "limit" to ContentFilterPolicy.MAX_METADATA_CHECKS_PER_REFILL,
                                "contentFilter" to profile.contentFilter.name
                            )
                        )
                        break
                    }
                    val metadata = try {
                        metadataChecks += 1
                        client.metadata(item.id)
                    } catch (failure: Throwable) {
                        lastFailure = failure
                        if (shouldAbortRefill(failure)) {
                            abortRefill = true
                        }
                        Diagnostics.log(
                            context,
                            "content.metadata_failure",
                            level = "WARN",
                            fields = mapOf(
                                "pool" to poolKey,
                                "wallhavenId" to item.id,
                                "contentFilter" to profile.contentFilter.name
                            ),
                            throwable = failure
                        )
                        if (abortRefill) break else continue
                    }
                    if (stopRequested()) break
                    val tags = metadata.tags
                    val decision = ContentFilterPolicy.evaluate(
                        wallhavenCategory = metadata.category,
                        tags = tags,
                        mode = profile.contentFilter
                    )
                    Diagnostics.log(
                        context,
                        "content.metadata_checked",
                        fields = mapOf(
                            "pool" to poolKey,
                            "wallhavenId" to item.id,
                            "wallhavenCategory" to decision.category,
                            "contentFilter" to profile.contentFilter.name,
                            "tagCount" to tags.size,
                            "tags" to tags.joinToString(",").take(1500),
                            "strictScore" to decision.score,
                            "blockedTags" to decision.blockedTags.joinToString(","),
                            "filterReasons" to decision.reasons.joinToString("|").take(1500),
                            "accepted" to decision.allowed
                        )
                    )
                    if (!decision.allowed) {
                        contentRejected += 1
                        continue
                    }
                }

                if (stopRequested()) break
                val extension = item.path.substringAfterLast('.', "jpg").substringBefore('?').lowercase()
                    .takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
                val fileName = "${item.id}.$extension"
                val destination = fileFor(poolKey, fileName)
                runCatching {
                    client.download(item, destination)
                    if (stopRequested()) {
                        destination.delete()
                        return@runCatching
                    }
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
                    if (shouldAbortRefill(failure)) {
                        abortRefill = true
                    }
                    Diagnostics.log(
                        context,
                        "cache.download.failure",
                        level = "WARN",
                        fields = mapOf("pool" to poolKey, "wallhavenId" to item.id),
                        throwable = failure
                    )
                }
                if (diskBudgetReached || abortRefill || stopRequested()) break
            }
            saveQueue(poolKey, existing)

            if (stopRequested()) break
            val lastPage = result.lastPage.coerceAtLeast(1)
            page += 1
            if (page > lastPage || page > 20) {
                page = 1
                if (profile.source == SourceMode.RANDOM) seed = null
                break
            }
            if (diskBudgetReached || abortRefill || metadataLimitReached || stopRequested() || result.items.isEmpty()) break
        }

        prefs.edit()
            .putInt(pageKey, page)
            .putString(seedKey, seed)
            .apply()

        // Cancellation is the automatic rotation asking preload to get out of its way.
        // Do not perform cleanup/enforcement while the rotation is about to decode and
        // apply files from the same cache. A later preload/maintenance pass will compact
        // stale queue entries and enforce the disk budget.
        if (stopRequested()) {
            val finalQueue = loadQueue(poolKey).filter { rawFileFor(poolKey, it.fileName).isFile }
            Diagnostics.log(
                context,
                "cache.refill.interrupted",
                fields = mapOf(
                    "pool" to poolKey,
                    "before" to initialCount,
                    "after" to finalQueue.size,
                    "metadataChecks" to metadataChecks,
                    "phase" to "before_cleanup"
                )
            )
            return
        }

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
                "metadataChecks" to metadataChecks,
                "contentRejected" to contentRejected,
                "metadataLimitReached" to metadataLimitReached,
                "broadSearchFallbacks" to broadSearchFallbacks,
                "diskBudgetReached" to diskBudgetReached,
                "networkAbort" to abortRefill,
                "stoppedEarly" to stoppedEarly,
                "cacheBytes" to stats().bytes
            )
        )

        if (finalQueue.isEmpty()) {
            val failure = lastFailure
            if (failure != null && abortRefill) throw failure
            val profileLabel = "${profile.source.label} / ${profile.category.label} / ${profile.contentFilter.label}"
            if (contentRejected > 0 || metadataLimitReached) {
                error("Aucun wallpaper accepté par le filtre pour le profil $profileLabel")
            }
            if (failure != null) throw failure
            error("Wallhaven ne renvoie aucun wallpaper pour le profil $profileLabel")
        }
    }

    fun pruneToSettings(settings: AppSettings) = synchronized(CACHE_LOCK) {
        pruneToSettingsLocked(settings)
    }

    private fun pruneToSettingsLocked(settings: AppSettings) {
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

    private fun shouldAbortRefill(failure: Throwable): Boolean {
        val message = failure.message.orEmpty()
        return message.startsWith("HTTP 429") ||
            failure is java.net.UnknownHostException ||
            failure is java.net.SocketException ||
            failure is java.net.SocketTimeoutException
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
