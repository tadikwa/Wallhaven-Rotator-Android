package fr.tadikwa.wallhavenrotator

import org.junit.Assert.assertEquals
import org.junit.Test

class CachePolicyTest {
    @Test
    fun invalidLimitFallsBackToDefault() {
        assertEquals(CachePolicy.DEFAULT_LIMIT_MB, CachePolicy.normalizeLimitMb(42))
    }

    @Test
    fun bytesMatchMegabytes() {
        assertEquals(250L * 1024L * 1024L, CachePolicy.limitBytes(250))
    }

    @Test
    fun refillStopsBeforeHardLimit() {
        assertEquals(225L * 1024L * 1024L, CachePolicy.refillStopBytes(250))
    }
}
