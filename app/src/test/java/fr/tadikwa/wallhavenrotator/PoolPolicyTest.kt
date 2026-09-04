package fr.tadikwa.wallhavenrotator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoolPolicyTest {
    @Test fun lowWatermarkTriggersRefill() {
        assertTrue(PoolPolicy.shouldRefill(0))
        assertTrue(PoolPolicy.shouldRefill(PoolPolicy.LOW_WATERMARK))
        assertFalse(PoolPolicy.shouldRefill(PoolPolicy.LOW_WATERMARK + 1))
    }
}
