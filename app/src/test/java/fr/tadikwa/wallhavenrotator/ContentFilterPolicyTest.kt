package fr.tadikwa.wallhavenrotator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFilterPolicyTest {
    @Test
    fun standardKeepsUserQueryOnly() {
        assertEquals(
            "+nature -people",
            ContentFilterPolicy.compose("+nature -people", ContentFilterMode.STANDARD)
        )
    }

    @Test
    fun reducedKeepsOrdinaryFemaleAnimeButRejectsExplicitAdultTags() {
        val neutral = ContentFilterPolicy.evaluate(
            "anime",
            listOf("anime", "anime girls", "blue hair", "fan art"),
            ContentFilterMode.REDUCED
        )
        assertTrue(neutral.allowed)

        val adult = ContentFilterPolicy.evaluate(
            "people",
            listOf("women", "pornstar", "Tushy", "portrait display"),
            ContentFilterMode.REDUCED
        )
        assertFalse(adult.allowed)
        assertTrue(adult.blockedTags.any { it.equals("pornstar", true) })
    }

    @Test
    fun strictRejectsSparseAnimeMetadataFailClosed() {
        // Mirrors the real failure mode seen on-device: visually female/suggestive art
        // can be tagged only as samurai/armor/sword. Category=anime must carry enough
        // trustworthy metadata to pass Strict.
        val decision = ContentFilterPolicy.evaluate(
            "anime",
            listOf("samurai", "armor", "sword"),
            ContentFilterMode.STRICT
        )
        assertFalse(decision.allowed)
        assertTrue(decision.reasons.contains("strict:anime_unclassified"))
    }

    @Test
    fun strictRejectsSparseGeneralHumanCharacterMetadata() {
        val decision = ContentFilterPolicy.evaluate(
            "general",
            listOf("samurai", "armor", "sword"),
            ContentFilterMode.STRICT
        )
        assertFalse(decision.allowed)
        assertTrue(decision.reasons.contains("strict:general_human_unclassified"))
    }

    @Test
    fun strictAllowsExplicitMaleAnime() {
        val decision = ContentFilterPolicy.evaluate(
            "anime",
            listOf("Solo Leveling", "Sung Jin Woo", "anime", "anime boys"),
            ContentFilterMode.STRICT
        )
        assertTrue(decision.allowed)
    }

    @Test
    fun strictAllowsClearlyNonHumanAnimeScenery() {
        val decision = ContentFilterPolicy.evaluate(
            "anime",
            listOf("mecha", "robot", "space art", "portrait display"),
            ContentFilterMode.STRICT
        )
        assertTrue(decision.allowed)
    }

    @Test
    fun strictRejectsFemaleFocusedGeneralAndAnime() {
        assertFalse(
            ContentFilterPolicy.evaluate(
                "general",
                listOf("women", "portrait display", "digital art"),
                ContentFilterMode.STRICT
            ).allowed
        )
        assertFalse(
            ContentFilterPolicy.evaluate(
                "anime",
                listOf("anime", "anime girls", "flowers"),
                ContentFilterMode.STRICT
            ).allowed
        )
    }

    @Test
    fun strictPeopleRequiresExplicitMaleSubject() {
        val unknown = ContentFilterPolicy.evaluate(
            "people",
            listOf("portrait display", "studio", "fashion"),
            ContentFilterMode.STRICT
        )
        assertFalse(unknown.allowed)
        assertTrue(unknown.reasons.contains("strict:people_unclassified"))

        val male = ContentFilterPolicy.evaluate(
            "people",
            listOf("men", "portrait display", "studio"),
            ContentFilterMode.STRICT
        )
        assertTrue(male.allowed)
    }

    @Test
    fun strictHardBlocksExposureVariantsInsideLongerTags() {
        val decision = ContentFilterPolicy.evaluate(
            "general",
            listOf("black stockings", "upskirt", "portrait display"),
            ContentFilterMode.STRICT
        )
        assertFalse(decision.allowed)
        assertTrue(decision.blockedTags.any { it.equals("upskirt", true) })
    }

    @Test
    fun strictRiskScoreRejectsCombinedWeakSignals() {
        val decision = ContentFilterPolicy.evaluate(
            "general",
            listOf("kneeling", "bare shoulders", "parted lips", "portrait display"),
            ContentFilterMode.STRICT
        )
        assertFalse(decision.allowed)
        assertTrue(decision.score >= 4)
        assertTrue(decision.reasons.any { it.startsWith("strict:risk_score=") })
    }

    @Test
    fun strictKeepsNeutralGeneralScenery() {
        val decision = ContentFilterPolicy.evaluate(
            "general",
            listOf("waterfall", "moss", "nature", "portrait display"),
            ContentFilterMode.STRICT
        )
        assertTrue(decision.allowed)
    }

    @Test
    fun exactTagQueryIsNotCombined() {
        assertEquals(
            "id:123",
            ContentFilterPolicy.compose("id:123", ContentFilterMode.STRICT)
        )
    }

    @Test
    fun contentFilterChangesPoolKey() {
        val standard = PoolKeys.home(
            ProfileSettings(contentFilter = ContentFilterMode.STANDARD),
            ResolvedOrientation.PORTRAIT
        )
        val strict = PoolKeys.home(
            ProfileSettings(contentFilter = ContentFilterMode.STRICT),
            ResolvedOrientation.PORTRAIT
        )
        assertNotEquals(standard, strict)
    }
}
