package fr.tadikwa.wallhavenrotator

data class ContentFilterDecision(
    val allowed: Boolean,
    val category: String,
    val score: Int,
    val blockedTags: List<String>,
    val reasons: List<String>
)

object ContentFilterPolicy {
    // PoolKeys includes this version so an upgrade cannot keep wallpapers cached under
    // an older/looser interpretation of Strict.
    const val POLICY_VERSION = 5
    const val MAX_METADATA_CHECKS_PER_REFILL = 16

    private const val STRICT_SCORE_THRESHOLD = 4

    // Keep the API-side query short. Local metadata/category evaluation below remains
    // authoritative and intentionally fails closed for ambiguous Anime/People results.
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

    // Reduced stays useful for normal female/anime wallpapers while removing explicit
    // adult or strongly sexual metadata when Wallhaven provides it.
    private val reducedHardConcepts = normalizedSet(
        "cleavage",
        "lingerie",
        "underwear",
        "panties",
        "panty",
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
        "boob",
        "boobs",
        "big boobs",
        "breast",
        "breasts",
        "big breasts",
        "large breasts",
        "huge breasts",
        "busty"
    )

    // Strict hard blocks: any one of these is enough to reject regardless of category.
    // Phrase matching also catches adjective-prefixed tags such as "black stockings".
    private val strictHardConcepts = reducedHardConcepts + normalizedSet(
        "upskirt",
        "skirt lift",
        "panty shot",
        "pantyshot",
        "no panties",
        "topless",
        "nipple",
        "nipples",
        "areola",
        "areolas",
        "bare breasts",
        "see through",
        "see through clothes",
        "transparent clothes",
        "transparent clothing",
        "open shirt",
        "crotch",
        "spread legs",
        "legs spread",
        "micro bikini",
        "microkini",
        "micro skirt",
        "sexualized",
        "sexualised",
        "seductive",
        "sensual gaze",
        "lustful look",
        "provocative"
    )

    // Female-focused metadata is a fail-closed signal in Strict. Reduced deliberately
    // does not use this list.
    private val femaleSubjectWords = setOf(
        "woman", "women", "girl", "girls", "female", "females",
        "schoolgirl", "schoolgirls"
    )

    private val maleSubjectWords = setOf(
        "man", "men", "male", "males", "boy", "boys", "guy", "guys",
        "gentleman", "gentlemen", "father", "dad"
    )

    // General can also contain character art with almost no gender/body tags. If a tag
    // clearly indicates a human/character subject but does not explicitly identify a
    // male subject, Strict rejects it instead of trusting sparse metadata.
    private val strictHumanLikeConcepts = normalizedSet(
        "samurai", "warrior", "warriors", "knight", "knights", "soldier", "soldiers",
        "person", "human", "human character", "character portrait",
        "model", "celebrity", "singer", "actor", "actress", "cosplay", "cosplayer",
        "witch", "wizard", "maid", "nurse"
    )

    // Anime is especially prone to sparse tags. In Strict, an Anime result without an
    // explicit male subject must clearly be scenery/non-human/object-oriented to pass.
    private val animeSafeNonHumanConcepts = normalizedSet(
        "mecha",
        "vehicle", "vehicles", "car", "cars", "motorcycle", "motorcycles",
        "aircraft", "airplane", "airplanes", "spacecraft", "spaceship", "spaceships",
        "ship", "ships", "train", "trains", "locomotive",
        "landscape", "scenery", "architecture", "building", "buildings",
        "abstract", "minimalism", "pattern", "texture", "typography", "logo",
        "planet", "planets", "space art"
    )

    // Weaker cues are scored. One innocent cue should not reject a male/general image,
    // but combinations typical of a suggestive composition do.
    private val strictWeightedSignals = mapOf(
        "stockings" to 3,
        "fishnet" to 3,
        "fishnet stockings" to 3,
        "pantyhose" to 3,
        "thighhighs" to 3,
        "thigh highs" to 3,
        "thigh high socks" to 3,
        "thighs" to 2,
        "miniskirt" to 3,
        "short shorts" to 2,
        "hot pants" to 3,
        "bodysuit" to 3,
        "leotard" to 3,
        "bunny suit" to 3,
        "bunny girl" to 3,
        "bare shoulders" to 2,
        "bare midriff" to 2,
        "midriff" to 2,
        "crop top" to 2,
        "armpits" to 2,
        "barefoot" to 2,
        "feet" to 2,
        "toes" to 2,
        "thigh strap" to 2,
        "high heels" to 1,
        "kneeling" to 2,
        "squatting" to 2,
        "bent legs" to 1,
        "legs up" to 2,
        "rear view" to 2,
        "looking back" to 1,
        "looking over shoulder" to 2,
        "parted lips" to 1,
        "blushing" to 1,
        "bed" to 2,
        "bedroom" to 2,
        "pillow" to 1,
        "maid outfit" to 2
    ).mapKeys { normalize(it.key) }

    fun description(mode: ContentFilterMode): String = when (mode) {
        ContentFilterMode.STANDARD ->
            "SFW Wallhaven uniquement, sans exclusion supplémentaire."
        ContentFilterMode.REDUCED ->
            "Écarte les tags adultes/suggestifs à fort signal puis vérifie les tags détaillés avant téléchargement."
        ContentFilterMode.STRICT ->
            "Très conservateur : bloque les sujets féminins et les métadonnées Anime/People ambiguës, plus les signaux suggestifs cumulés."
    }

    fun compose(userQuery: String, mode: ContentFilterMode): String {
        val normalizedUserQuery = userQuery.trim()
        if (normalizedUserQuery.matches(Regex("^id:[0-9]+$", RegexOption.IGNORE_CASE))) {
            return normalizedUserQuery
        }

        val exclusions = when (mode) {
            ContentFilterMode.STANDARD -> emptyList()
            ContentFilterMode.REDUCED,
            ContentFilterMode.STRICT -> queryExclusions
        }
        return buildList {
            normalizedUserQuery.takeIf { it.isNotEmpty() }?.let(::add)
            exclusions.mapTo(this) { "-$it" }
        }.joinToString(" ")
    }

    fun requiresMetadataInspection(mode: ContentFilterMode): Boolean =
        mode != ContentFilterMode.STANDARD

    fun evaluate(
        wallhavenCategory: String,
        tags: Collection<String>,
        mode: ContentFilterMode
    ): ContentFilterDecision {
        val category = normalize(wallhavenCategory).ifBlank { "unknown" }
        if (mode == ContentFilterMode.STANDARD) {
            return ContentFilterDecision(true, category, 0, emptyList(), emptyList())
        }

        val normalizedTags = tags.map { it to normalize(it) }
        val reducedBlocked = matchingOriginalTags(normalizedTags, reducedHardConcepts)
        if (mode == ContentFilterMode.REDUCED) {
            return if (reducedBlocked.isEmpty()) {
                ContentFilterDecision(true, category, 0, emptyList(), emptyList())
            } else {
                ContentFilterDecision(
                    allowed = false,
                    category = category,
                    score = 100,
                    blockedTags = reducedBlocked,
                    reasons = reducedBlocked.map { "hard:$it" }
                )
            }
        }

        val hardBlocked = matchingOriginalTags(normalizedTags, strictHardConcepts)
        if (hardBlocked.isNotEmpty()) {
            return ContentFilterDecision(
                allowed = false,
                category = category,
                score = 100,
                blockedTags = hardBlocked,
                reasons = hardBlocked.map { "hard:$it" }
            )
        }

        val femaleTags = normalizedTags
            .filter { (_, normalized) -> isFemaleFocusedTag(normalized) }
            .map { it.first }
            .distinctBy(::normalize)
            .sortedBy(::normalize)
        if (femaleTags.isNotEmpty()) {
            return ContentFilterDecision(
                allowed = false,
                category = category,
                score = 10,
                blockedTags = femaleTags,
                reasons = listOf("strict:female_subject") + femaleTags.map { "subject:$it" }
            )
        }

        val maleSignal = normalizedTags.any { (_, normalized) -> hasWord(normalized, maleSubjectWords) } ||
            normalizedTags.any { (_, normalized) ->
                normalized == "anime boys" || normalized == "anime boy" ||
                    normalized == "male character" || normalized == "male characters"
            }
        val safeAnimeNonHuman = normalizedTags.any { (_, normalized) ->
            matchesAnyConcept(normalized, animeSafeNonHumanConcepts)
        }

        when (category) {
            "people" -> if (!maleSignal) {
                return ContentFilterDecision(
                    allowed = false,
                    category = category,
                    score = 10,
                    blockedTags = emptyList(),
                    reasons = listOf("strict:people_unclassified")
                )
            }
            "anime" -> if (!maleSignal && !safeAnimeNonHuman) {
                return ContentFilterDecision(
                    allowed = false,
                    category = category,
                    score = 10,
                    blockedTags = emptyList(),
                    reasons = listOf("strict:anime_unclassified")
                )
            }
            "general" -> {
                val ambiguousHuman = normalizedTags.any { (_, normalized) ->
                    matchesAnyConcept(normalized, strictHumanLikeConcepts)
                }
                if (ambiguousHuman && !maleSignal) {
                    return ContentFilterDecision(
                        allowed = false,
                        category = category,
                        score = 10,
                        blockedTags = emptyList(),
                        reasons = listOf("strict:general_human_unclassified")
                    )
                }
            }
            else -> {
                // A missing/new category means we cannot prove the candidate fits the
                // conservative Strict contract. Fail closed rather than silently trust it.
                return ContentFilterDecision(
                    allowed = false,
                    category = category,
                    score = 10,
                    blockedTags = emptyList(),
                    reasons = listOf("strict:unknown_category")
                )
            }
        }

        val scoreBreakdown = mutableListOf<Pair<String, Int>>()
        normalizedTags.forEach { (original, normalized) ->
            strictWeightedSignals.forEach { (concept, points) ->
                if (containsPhrase(normalized, concept)) scoreBreakdown += original to points
            }
        }
        val score = scoreBreakdown.sumOf { it.second }
        if (score >= STRICT_SCORE_THRESHOLD) {
            val cues = scoreBreakdown
                .distinctBy { normalize(it.first) }
                .sortedByDescending { it.second }
            return ContentFilterDecision(
                allowed = false,
                category = category,
                score = score,
                blockedTags = cues.map { it.first },
                reasons = listOf("strict:risk_score=$score") + cues.map { "cue:${it.first}+${it.second}" }
            )
        }

        return ContentFilterDecision(true, category, score, emptyList(), emptyList())
    }

    // Retained for simple callers/tests; category-aware Strict decisions should use evaluate().
    fun blockedTags(tags: Collection<String>, mode: ContentFilterMode): List<String> = when (mode) {
        ContentFilterMode.STANDARD -> emptyList()
        ContentFilterMode.REDUCED -> matchingOriginalTags(tags.map { it to normalize(it) }, reducedHardConcepts)
        ContentFilterMode.STRICT -> {
            val normalizedTags = tags.map { it to normalize(it) }
            val hard = matchingOriginalTags(normalizedTags, strictHardConcepts)
            val female = normalizedTags.filter { isFemaleFocusedTag(it.second) }.map { it.first }
            (hard + female).distinctBy(::normalize).sortedBy(::normalize)
        }
    }

    private fun matchingOriginalTags(
        tags: List<Pair<String, String>>,
        concepts: Set<String>
    ): List<String> = tags
        .filter { (_, normalized) -> matchesAnyConcept(normalized, concepts) }
        .map { it.first }
        .distinctBy(::normalize)
        .sortedBy(::normalize)

    private fun matchesAnyConcept(normalizedTag: String, concepts: Set<String>): Boolean =
        concepts.any { containsPhrase(normalizedTag, it) }

    private fun containsPhrase(value: String, phrase: String): Boolean =
        value == phrase || value.startsWith("$phrase ") || value.endsWith(" $phrase") || value.contains(" $phrase ")

    private fun isFemaleFocusedTag(normalized: String): Boolean {
        if (hasWord(normalized, femaleSubjectWords)) return true
        return normalized == "anime girl" || normalized == "anime girls" ||
            normalized == "video game girl" || normalized == "video game girls" ||
            normalized == "female character" || normalized == "female characters" ||
            normalized == "fantasy girl" || normalized == "fox girl" || normalized == "cat girl" ||
            normalized == "bunny girl" || normalized == "horse girls" || normalized == "girls with guns"
    }

    private fun hasWord(normalized: String, words: Set<String>): Boolean =
        normalized.split(' ').any { it in words }

    private fun normalizedSet(vararg tags: String): Set<String> = tags.map(::normalize).toSet()

    private fun normalize(value: String): String =
        value.lowercase()
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
}
