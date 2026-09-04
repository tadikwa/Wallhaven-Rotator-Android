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
    fun reducedAddsHighSignalExclusionsWithoutRemovingAnime() {
        val query = ContentFilterPolicy.compose("+anime", ContentFilterMode.REDUCED)
        assertTrue(query.contains("+anime"))
        assertTrue(query.contains("-cleavage"))
        assertTrue(query.contains("-lingerie"))
        assertFalse(query.contains("-anime"))
    }

    @Test
    fun strictAddsMultiWordExclusionsWithBraces() {
        val query = ContentFilterPolicy.compose("", ContentFilterMode.STRICT)
        assertTrue(query.contains("-{fishnet stockings}"))
        assertTrue(query.contains("-{one-piece swimsuit}"))
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
