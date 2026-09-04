package fr.tadikwa.wallhavenrotator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreloadControlTest {
    @Test
    fun manualPriorityInvalidatesRunningPreloadToken() {
        val token = PreloadControl.snapshot()
        assertFalse(PreloadControl.shouldStop(token))
        PreloadControl.interrupt()
        assertTrue(PreloadControl.shouldStop(token))
    }
}
