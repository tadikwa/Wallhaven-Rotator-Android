package fr.tadikwa.wallhavenrotator

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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
        val connection = open(WallhavenQuery.build(profile, orientation, page = page, seed = seed))
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

    fun download(item: WallhavenItem, destination: File) {
        val connection = open(item.path)
        try {
            destination.parentFile?.mkdirs()
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Wallhaven-Rotator-Android/0.1")
        connection.setRequestProperty("Accept", "application/json,image/*,*/*")
        connection.connect()
        val status = connection.responseCode
        if (status !in 200..299) {
            val error = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            connection.disconnect()
            throw IllegalStateException("HTTP $status: ${error.orEmpty().take(200)}")
        }
        return connection
    }
}
