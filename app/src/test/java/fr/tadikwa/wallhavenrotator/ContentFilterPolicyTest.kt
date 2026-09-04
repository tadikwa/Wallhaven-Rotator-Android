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
    fun filteredModesKeepAnimeButExcludeYouthCodedAndSexualTags() {
        val reduced = ContentFilterPolicy.compose("+anime", ContentFilterMode.REDUCED)
        val strict = ContentFilterPolicy.compose("+anime", ContentFilterMode.STRICT)
        assertTrue(reduced.contains("+anime"))
        assertTrue(reduced.contains("-cleavage"))
        assertTrue(reduced.contains("-schoolgirl"))
        assertTrue(reduced.contains("-loli"))
        assertFalse(reduced.contains("-anime"))
        // Strict deliberately uses the same broad query and tightens via metadata.
        assertEquals(reduced, strict)
    }

    @Test
    fun metadataPassBlocksSchoolgirlInReducedAndMoreInStrict() {
        assertTrue(
            ContentFilterPolicy.blockedTags(
                listOf("schoolgirl", "blonde", "anime"),
                ContentFilterMode.REDUCED
            ).contains("schoolgirl")
        )
        assertTrue(
            ContentFilterPolicy.blockedTags(
                listOf("fishnet stockings", "portrait"),
                ContentFilterMode.STRICT
            ).contains("fishnet stockings")
        )
        assertTrue(
            ContentFilterPolicy.blockedTags(
                listOf("fishnet stockings", "portrait"),
                ContentFilterMode.REDUCED
            ).isEmpty()
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
}
