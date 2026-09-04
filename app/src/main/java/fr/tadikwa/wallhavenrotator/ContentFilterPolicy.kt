package fr.tadikwa.wallhavenrotator

object ContentFilterPolicy {
    const val POLICY_VERSION = 2
    const val MAX_METADATA_CHECKS_PER_REFILL = 12

    // Keep the query-side exclusions deliberately short and high-signal. Too many
    // negative terms can make narrow Wallhaven listings (notably Trending + General
    // + portrait ratios) collapse to zero results even though suitable wallpapers
    // exist elsewhere in the listing.
    private val queryExclusions = listOf(
        "cleavage",
        "lingerie",
        "underwear",
        "panties",
        "bikini",
        "swimsuit",
        "ecchi",
        "schoolgirl",
        "loli"
    )

    private val reducedMetadataBlocks = normalizedSet(
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
        "ecchi",
        "schoolgirl",
        "school girl",
        "school uniform",
        "loli"
    )

    private val strictMetadataBlocks = reducedMetadataBlocks + normalizedSet(
        "thong",
        "stockings",
        "fishnet",
        "fishnet stockings",
        "garter",
        "garter belt",
        "boobs",
        "big boobs",
        "breasts",
        "sexy",
        "seductive",
        "pinup",
        "pin up",
        "thighhighs",
        "thigh highs",
        "miniskirt"
    )

    fun description(mode: ContentFilterMode): String = when (mode) {
        ContentFilterMode.STANDARD ->
            "SFW Wallhaven uniquement, sans exclusion supplémentaire."
        ContentFilterMode.REDUCED ->
            "Écarte les tags suggestifs à fort signal, y compris schoolgirl/loli, puis vérifie les tags du wallpaper avant téléchargement."
        ContentFilterMode.STRICT ->
            "Même recherche large que Moins suggestif, avec une vérification locale plus stricte des tags détaillés avant téléchargement."
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
            ContentFilterMode.REDUCED,
            ContentFilterMode.STRICT -> queryExclusions
        }
        return buildList {
            normalizedUserQuery.takeIf { it.isNotEmpty() }?.let(::add)
            tags.mapTo(this) { "-$it" }
        }.joinToString(" ")
    }

    fun requiresMetadataInspection(mode: ContentFilterMode): Boolean =
        mode != ContentFilterMode.STANDARD

    fun blockedTags(tags: Collection<String>, mode: ContentFilterMode): List<String> {
        if (mode == ContentFilterMode.STANDARD) return emptyList()
        val blocked = when (mode) {
            ContentFilterMode.STANDARD -> emptySet()
            ContentFilterMode.REDUCED -> reducedMetadataBlocks
            ContentFilterMode.STRICT -> strictMetadataBlocks
        }
        return tags
            .map(::normalize)
            .filter { it in blocked }
            .distinct()
            .sorted()
    }

    private fun normalizedSet(vararg tags: String): Set<String> = tags.map(::normalize).toSet()

    private fun normalize(value: String): String =
        value.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
}
