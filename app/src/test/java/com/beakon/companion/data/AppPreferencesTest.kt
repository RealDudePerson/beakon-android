package com.beakon.companion.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the persisted "start tracking at boot" setting. Uses a
 * fake [android.content.SharedPreferences] so the real [AppPreferences] logic
 * runs on the JVM (there were no existing preference tests, and
 * [AppPreferences] needs a `Context` to build real storage).
 */
class AppPreferencesTest {

    @Test
    fun `start tracking at boot defaults to off when never set`() {
        val prefs = AppPreferences(FakeSharedPreferences())

        assertFalse(prefs.startTrackingOnBoot)
        assertFalse(prefs.startTrackingOnBootFlow.value)
    }

    @Test
    fun `start tracking at boot round-trips through storage`() {
        val prefs = AppPreferences(FakeSharedPreferences())

        prefs.startTrackingOnBoot = true
        assertTrue(prefs.startTrackingOnBoot)
        assertTrue(prefs.startTrackingOnBootFlow.value)

        prefs.startTrackingOnBoot = false
        assertFalse(prefs.startTrackingOnBoot)
        assertFalse(prefs.startTrackingOnBootFlow.value)
    }

    @Test
    fun `the exposed flow follows the persisted value`() {
        val prefs = AppPreferences(FakeSharedPreferences())
        val observed = mutableListOf(prefs.startTrackingOnBootFlow.value)

        prefs.startTrackingOnBoot = true
        observed += prefs.startTrackingOnBootFlow.value

        prefs.startTrackingOnBoot = false
        observed += prefs.startTrackingOnBootFlow.value

        assertTrue("flow must start off, then on, then off: $observed", observed == listOf(false, true, false))
    }
}
