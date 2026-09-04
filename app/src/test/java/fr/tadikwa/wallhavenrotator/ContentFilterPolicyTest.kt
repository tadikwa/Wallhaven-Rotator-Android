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
    fun filteredModesKeepAnimeQueryBroadEnoughForMetadataFallback() {
        val reduced = ContentFilterPolicy.compose("+anime", ContentFilterMode.REDUCED)
        val strict = ContentFilterPolicy.compose("+anime", ContentFilterMode.STRICT)
        assertTrue(reduced.contains("+anime"))
        assertTrue(reduced.contains("-cleavage"))
        assertTrue(reduced.contains("-schoolgirl"))
        assertTrue(reduced.contains("-loli"))
        assertFalse(reduced.contains("-anime"))
        assertEquals(reduced, strict)
    }

    @Test
    fun reducedRejectsObservedAdultTagsButKeepsOrdinaryAnimeGirls() {
        val observedAdult = ContentFilterPolicy.blockedTags(
            listOf("women", "Tori Black", "pornstar", "Tushy", "portrait display"),
            ContentFilterMode.REDUCED
        )
        assertTrue(observedAdult.contains("pornstar"))
        assertTrue(observedAdult.contains("Tushy"))

        assertTrue(
            ContentFilterPolicy.blockedTags(
                listOf("anime", "anime girls", "blue hair", "fan art"),
                ContentFilterMode.REDUCED
            ).isEmpty()
        )
    }

    @Test
    fun strictRejectsFemaleFocusedSubjectsEvenWithoutSexualTags() {
        assertTrue(
            ContentFilterPolicy.blockedTags(
                listOf("anime", "anime girls", "Hololive", "blue hair"),
                ContentFilterMode.STRICT
            ).contains("anime girls")
        )
        assertTrue(
            ContentFilterPolicy.blockedTags(
                listOf("Cuban women", "actress", "portrait display"),
                ContentFilterMode.STRICT
            ).isNotEmpty()
        )
        assertTrue(
            ContentFilterPolicy.blockedTags(
                listOf("video game girls", "Arknights", "flowers"),
                ContentFilterMode.STRICT
            ).contains("video game girls")
        )
    }

    @Test
    fun strictKeepsNonFemaleAnimeAndScenery() {
        assertTrue(
            ContentFilterPolicy.blockedTags(
                listOf("Solo Leveling", "Sung Jin Woo", "anime", "anime boys"),
                ContentFilterMode.STRICT
            ).isEmpty()
        )
        assertTrue(
            ContentFilterPolicy.blockedTags(
                listOf("Chinese dragon", "rice fields", "artwork"),
                ContentFilterMode.STRICT
            ).isEmpty()
        )
    }

    @Test
    fun strictBlocksObservedAssAndStockingsTags() {
        assertTrue(
            ContentFilterPolicy.blockedTags(
                listOf("Genshin Impact", "anime girls", "ass"),
                ContentFilterMode.STRICT
            ).contains("ass")
        )
        assertTrue(
            ContentFilterPolicy.blockedTags(
                listOf("anime", "stockings", "wings"),
                ContentFilterMode.STRICT
            ).contains("stockings")
        )
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

    @Test
    fun strictRejectsAdultBodyTagsObservedInDiagnostics() {
        val blocked = ContentFilterPolicy.blockedTags(
            listOf("chromatic aberration", "big boobs", "bodysuit", "sensual gaze"),
            ContentFilterMode.STRICT
        )
        assertTrue(blocked.contains("big boobs"))
        assertTrue(blocked.contains("bodysuit"))
        assertTrue(blocked.contains("sensual gaze"))
    }
}
