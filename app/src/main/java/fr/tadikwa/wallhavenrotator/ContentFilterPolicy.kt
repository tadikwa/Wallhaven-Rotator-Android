package fr.tadikwa.wallhavenrotator

object ContentFilterPolicy {
    private val reducedTags = listOf(
        "cleavage",
        "lingerie",
        "underwear",
        "panties",
        "bikini",
        "swimsuit",
        "bra",
        "sideboob",
        "underboob",
        "cameltoe",
        "ecchi"
    )

    private val strictExtraTags = listOf(
        "thong",
        "stockings",
        "fishnet stockings",
        "bikini top",
        "one-piece swimsuit",
        "sports bra",
        "garter belt",
        "boobs",
        "big boobs",
        "sexy"
    )

    fun description(mode: ContentFilterMode): String = when (mode) {
        ContentFilterMode.STANDARD -> "SFW Wallhaven uniquement, sans exclusion supplémentaire."
        ContentFilterMode.REDUCED -> "Exclut les principaux tags associés aux sous-vêtements, maillots et cadrages sexualisés."
        ContentFilterMode.STRICT -> "Liste d'exclusion plus large. Peut retirer davantage d'images parfaitement SFW."
    }

    fun compose(userQuery: String, mode: ContentFilterMode): String {
        val normalizedUserQuery = userQuery.trim()
        // Wallhaven documents id:<tag-id> as an exact-tag query that cannot be combined
        // with other terms. Preserve it verbatim rather than producing an invalid request.
        if (normalizedUserQuery.matches(Regex("^id:[0-9]+$", RegexOption.IGNORE_CASE))) {
            return normalizedUserQuery
        }

        val tags = when (mode) {
            ContentFilterMode.STANDARD -> emptyList()
            ContentFilterMode.REDUCED -> reducedTags
            ContentFilterMode.STRICT -> reducedTags + strictExtraTags
        }
        return buildList {
            normalizedUserQuery.takeIf { it.isNotEmpty() }?.let(::add)
            tags.mapTo(this) { excludeToken(it) }
        }.joinToString(" ")
    }

    private fun excludeToken(tag: String): String =
        if (tag.any(Char::isWhitespace)) "-{$tag}" else "-$tag"
}
