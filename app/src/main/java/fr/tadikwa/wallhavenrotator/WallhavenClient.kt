package fr.tadikwa.wallhavenrotator

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

class WallhavenClient {
    data class SearchResult(
        val items: List<WallhavenItem>,
        val seed: String?,
        val lastPage: Int
    )

    fun search(
        profile: ProfileSettings,
        orientation: ResolvedOrientation,
        page: Int = 1,
        seed: String? = null
    ): SearchResult {
        val connection = open(
            WallhavenQuery.build(profile, orientation, page = page, seed = seed),
            apiRequest = true
        )
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val data = root.getJSONArray("data")
            val items = buildList {
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    add(
                        WallhavenItem(
                            id = item.getString("id"),
                            path = item.getString("path"),
                            width = item.optInt("dimension_x", 0),
                            height = item.optInt("dimension_y", 0)
                        )
                    )
                }
            }
            val meta = root.optJSONObject("meta")
            val returnedSeed = meta?.optString("seed")?.takeIf { it.isNotBlank() && it != "null" }
            val lastPage = meta?.optInt("last_page", 1) ?: 1
            return SearchResult(items, returnedSeed, lastPage)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Fetch the real tag list for a wallpaper. Search listings do not include tags,
     * while the wallpaper-info endpoint does. Filtered modes use this for a local
     * second pass instead of trying to express an ever-growing blacklist in q=.
     */
    fun tags(wallpaperId: String): List<String> {
        val connection = open(
            "https://wallhaven.cc/api/v1/w/$wallpaperId",
            apiRequest = true
        )
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(body).getJSONObject("data")
            val tags = data.optJSONArray("tags") ?: return emptyList()
            return buildList {
                for (i in 0 until tags.length()) {
                    val tag = tags.optJSONObject(i) ?: continue
                    tag.optString("name")
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun download(item: WallhavenItem, destination: File) {
        val connection = open(item.path, apiRequest = false)
        try {
            destination.parentFile?.mkdirs()
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, apiRequest: Boolean): HttpURLConnection {
        if (apiRequest) ApiRateLimiter.awaitSlot()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Wallhaven-Rotator-Android/${BuildConfig.VERSION_NAME}")
        connection.setRequestProperty(
            "Accept",
            if (apiRequest) "application/json" else "image/*,*/*"
        )
        connection.connect()
        val status = connection.responseCode
        if (status !in 200..299) {
            val error = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            connection.disconnect()
            throw IllegalStateException("HTTP $status: ${error.orEmpty().take(200)}")
        }
        return connection
    }

    /** Keep deliberate headroom below Wallhaven's documented 45 API requests/minute. */
    private object ApiRateLimiter {
        private const val WINDOW_MS = 60_000L
        private const val MAX_REQUESTS_PER_WINDOW = 30
        private val lock = Any()
        private val timestamps = ArrayDeque<Long>()

        fun awaitSlot() {
            while (true) {
                val waitMs = synchronized(lock) {
                    val now = System.currentTimeMillis()
                    while (timestamps.isNotEmpty() && now - timestamps.first() >= WINDOW_MS) {
                        timestamps.removeFirst()
                    }
                    if (timestamps.size < MAX_REQUESTS_PER_WINDOW) {
                        timestamps.addLast(now)
                        0L
                    } else {
                        (WINDOW_MS - (now - timestamps.first()) + 50L).coerceAtLeast(50L)
                    }
                }
                if (waitMs <= 0L) return
                Thread.sleep(waitMs)
            }
        }
    }
}
