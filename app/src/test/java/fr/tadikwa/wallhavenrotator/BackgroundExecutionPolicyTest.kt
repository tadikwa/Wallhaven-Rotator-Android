package fr.tadikwa.wallhavenrotator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundExecutionPolicyTest {
    @Test
    fun onlyInteractiveAndFullyUnlockedIsReady() {
        assertTrue(BackgroundExecutionPolicy.isReady(true, false, false))
        assertFalse(BackgroundExecutionPolicy.isReady(false, false, false))
        assertFalse(BackgroundExecutionPolicy.isReady(true, true, false))
        assertFalse(BackgroundExecutionPolicy.isReady(true, false, true))
    }

    @Test
    fun reasonIdentifiesBlockingState() {
        assertEquals("non_interactive", BackgroundExecutionPolicy.reason(false, false, false))
        assertEquals("device_locked", BackgroundExecutionPolicy.reason(true, true, false))
        assertEquals("keyguard_locked", BackgroundExecutionPolicy.reason(true, false, true))
        assertEquals("ready", BackgroundExecutionPolicy.reason(true, false, false))
    }
}
