package fr.tadikwa.wallhavenrotator

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object WallhavenQuery {
    const val API_BASE = "https://wallhaven.cc/api/v1/search"

    fun build(
        profile: ProfileSettings,
        orientation: ResolvedOrientation,
        page: Int = 1,
        seed: String? = null
    ): String {
        val params = linkedMapOf(
            "categories" to profile.category.apiValue,
            "purity" to "100",
            "order" to "desc",
            "page" to page.toString()
        )

        when (profile.source) {
            SourceMode.TRENDING -> {
                params["sorting"] = "toplist"
                params["topRange"] = "1d"
            }
            SourceMode.POPULAR -> {
                params["sorting"] = "toplist"
                params["topRange"] = "1M"
            }
            SourceMode.NEW -> params["sorting"] = "date_added"
            SourceMode.RANDOM -> params["sorting"] = "random"
        }

        val ratios = when (orientation) {
            ResolvedOrientation.PORTRAIT -> "9x16,10x16,9x18"
            ResolvedOrientation.LANDSCAPE -> "16x9,16x10"
        }
        params["ratios"] = ratios

        val q = profile.query.trim()
        if (q.isNotEmpty()) params["q"] = q
        if (profile.source == SourceMode.RANDOM && !seed.isNullOrBlank()) params["seed"] = seed

        return API_BASE + "?" + params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
}
