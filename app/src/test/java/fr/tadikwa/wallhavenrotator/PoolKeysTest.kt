package fr.tadikwa.wallhavenrotator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PoolKeysTest {
    @Test
    fun profileChangeCreatesANewPool() {
        val first = PoolKeys.home(ProfileSettings(query = "+nature"), ResolvedOrientation.PORTRAIT)
        val second = PoolKeys.home(ProfileSettings(query = "+city"), ResolvedOrientation.PORTRAIT)
        assertNotEquals(first, second)
    }

    @Test
    fun independentModeKeepsBothOrientationsForBothDestinations() {
        val keys = PoolKeys.active(AppSettings(targetMode = TargetMode.BOTH_INDEPENDENT))
        assertEquals(4, keys.size)
    }
}
