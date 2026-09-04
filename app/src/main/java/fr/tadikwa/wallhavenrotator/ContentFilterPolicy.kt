package fr.tadikwa.wallhavenrotator

object ContentFilterPolicy {
    // Bump whenever filtering semantics change. PoolKeys includes this value, so an
    // upgrade cannot keep serving wallpapers cached under an older/looser policy.
    const val POLICY_VERSION = 4
    const val MAX_METADATA_CHECKS_PER_REFILL = 16

    // Query-side exclusions stay deliberately short/high-signal. Metadata inspection
    // remains authoritative; WallpaperCache can fall back to a broad SFW query when
    // Wallhaven returns zero results for a filtered search.
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

    // Reduced is intended to keep ordinary female/anime subjects while rejecting
    // explicit adult/suggestive signals when Wallhaven actually tags them.
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
        "loli",
        "thong",
        "garter",
        "garter belt",
        "porn",
        "pornography",
        "pornstar",
        "porn star",
        "adult model",
        "adult content",
        "onlyfans",
        "tushy",
        "playboy",
        "playmate",
        "nude",
        "nudity",
        "naked",
        "erotic",
        "erotica",
        "sexual",
        "sex",
        "fetish",
        "bdsm",
        "ass",
        "butt",
        "buttocks",
        "booty",
        "boobs",
        "big boobs",
        "breast",
        "breasts",
        "big breasts",
        "large breasts",
        "huge breasts",
        "busty"
    )

    // Strict intentionally errs on the side of false positives. In addition to the
    // adult/suggestive tags above, it rejects female-focused portrait subjects even
    // when the wallpaper has no explicit sexual tag. This is deterministic and avoids
    // pretending that a tag-only filter can visually infer sexualisation.
    private val strictExtraExactBlocks = normalizedSet(
        "stockings",
        "fishnet",
        "fishnet stockings",
        "pantyhose",
        "thighhighs",
        "thigh highs",
        "thigh high socks",
        "miniskirt",
        "thighs",
        "barefoot",
        "feet",
        "toes",
        "sexy",
        "seductive",
        "pinup",
        "pin up",
        "actress",
        "female model",
        "bodysuit",
        "tight clothing",
        "sensual gaze",
        "lustful look"
    )

    private val strictFemaleSubjectWords = setOf(
        "woman",
        "women",
        "girl",
        "girls",
        "female",
        "females",
        "schoolgirl",
        "schoolgirls"
    )

    fun description(mode: ContentFilterMode): String = when (mode) {
        ContentFilterMode.STANDARD ->
            "SFW Wallhaven uniquement, sans exclusion supplémentaire."
        ContentFilterMode.REDUCED ->
            "Écarte les tags adultes/suggestifs à fort signal puis vérifie les tags détaillés avant téléchargement."
        ContentFilterMode.STRICT ->
            "Mode conservateur : bloque aussi les wallpapers centrés sur des filles/femmes, même sans tag explicitement sexuel."
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

    /**
     * Returns the original tag labels that caused rejection. Strict uses both exact
     * adult/suggestive matches and conservative female-subject word matching.
     */
    fun blockedTags(tags: Collection<String>, mode: ContentFilterMode): List<String> {
        if (mode == ContentFilterMode.STANDARD) return emptyList()

        return tags.mapNotNull { original ->
            val normalized = normalize(original)
            val blocked = when (mode) {
                ContentFilterMode.STANDARD -> false
                ContentFilterMode.REDUCED -> normalized in reducedMetadataBlocks
                ContentFilterMode.STRICT ->
                    normalized in reducedMetadataBlocks ||
                        normalized in strictExtraExactBlocks ||
                        isFemaleFocusedTag(normalized)
            }
            original.takeIf { blocked }
        }.distinctBy(::normalize).sortedBy(::normalize)
    }

    private fun isFemaleFocusedTag(normalized: String): Boolean {
        val words = normalized.split(' ').filter { it.isNotBlank() }
        if (words.any { it in strictFemaleSubjectWords }) return true

        // Common Wallhaven taxonomy phrases that sometimes omit a standalone gender
        // token after normalization.
        return normalized == "anime girl" ||
            normalized == "video game girl" ||
            normalized == "female character" ||
            normalized == "female characters"
    }

    private fun normalizedSet(vararg tags: String): Set<String> = tags.map(::normalize).toSet()

    private fun normalize(value: String): String =
        value.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
}
