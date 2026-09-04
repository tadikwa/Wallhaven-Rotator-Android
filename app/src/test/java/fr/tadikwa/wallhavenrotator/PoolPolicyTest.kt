package fr.tadikwa.wallhavenrotator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolPolicyTest {
    @Test
    fun lowWatermarkTriggersRefill() {
        assertTrue(PoolPolicy.shouldRefill(0))
        assertTrue(PoolPolicy.shouldRefill(PoolPolicy.LOW_WATERMARK))
        assertFalse(PoolPolicy.shouldRefill(PoolPolicy.LOW_WATERMARK + 1))
    }

    @Test
    fun poolIsDeliberatelySmallToAvoidRequestBursts() {
        assertEquals(8, PoolPolicy.TARGET_SIZE)
        assertEquals(1, PoolPolicy.CACHE_MISS_TARGET_SIZE)
        assertEquals(2, PoolPolicy.MAX_SEARCH_PAGES_PER_REFILL)
    }
}
