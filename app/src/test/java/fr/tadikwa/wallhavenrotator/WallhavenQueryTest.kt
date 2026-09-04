package fr.tadikwa.wallhavenrotator

import org.junit.Assert.assertTrue
import org.junit.Test

class WallhavenQueryTest {
    @Test
    fun trendingMatchesDesktopSemantics() {
        val url = WallhavenQuery.build(
            ProfileSettings(source = SourceMode.TRENDING, category = CategoryMode.GENERAL),
            ResolvedOrientation.PORTRAIT
        )
        assertTrue(url.contains("sorting=toplist"))
        assertTrue(url.contains("topRange=1d"))
        assertTrue(url.contains("categories=100"))
        assertTrue(url.contains("purity=100"))
        assertTrue(url.contains("ratios=9x16%2C10x16%2C9x18"))
    }

    @Test
    fun popularUsesOneMonthToplist() {
        val url = WallhavenQuery.build(
            ProfileSettings(source = SourceMode.POPULAR),
            ResolvedOrientation.LANDSCAPE
        )
        assertTrue(url.contains("sorting=toplist"))
        assertTrue(url.contains("topRange=1M"))
        assertTrue(url.contains("ratios=16x9%2C16x10"))
    }

    @Test
    fun queryIsEncoded() {
        val url = WallhavenQuery.build(
            ProfileSettings(query = "+nature -people"),
            ResolvedOrientation.PORTRAIT
        )
        assertTrue(url.contains("q=%2Bnature%20-people"))
    }
}
