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

    // Wallhaven documents -tagname exclusions. Keep the built-in blacklist to
    // single-token terms only: the previous experimental -{multi word} syntax
    // was not documented and could collapse a Strict search to zero results.
    private val strictExtraTags = listOf(
        "thong",
        "stockings",
        "fishnet",
        "garter",
        "boobs",
        "breasts",
        "sexy",
        "seductive",
        "pinup",
        "thighhighs",
        "miniskirt"
    )

    fun description(mode: ContentFilterMode): String = when (mode) {
        ContentFilterMode.STANDARD -> "SFW Wallhaven uniquement, sans exclusion supplémentaire."
        ContentFilterMode.REDUCED -> "Exclut les principaux tags associés aux sous-vêtements, maillots et cadrages sexualisés."
        ContentFilterMode.STRICT -> "Exclusions SFW plus larges, avec uniquement la syntaxe de tags documentée par Wallhaven."
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
            tags.mapTo(this) { "-$it" }
        }.joinToString(" ")
    }
}
