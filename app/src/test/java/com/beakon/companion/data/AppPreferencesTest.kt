package com.beakon.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // --- accuracy threshold -------------------------------------------------

    @Test
    fun `accuracy threshold defaults to 30 when never set`() {
        val prefs = AppPreferences(FakeSharedPreferences())

        assertEquals(30, AppPreferences.DEFAULT_ACCURACY_THRESHOLD_M)
        assertEquals(30, prefs.accuracyThresholdM)
    }

    @Test
    fun `accuracy threshold round-trips through storage`() {
        val prefs = AppPreferences(FakeSharedPreferences())

        prefs.accuracyThresholdM = 50
        assertEquals(50, prefs.accuracyThresholdM)

        prefs.accuracyThresholdM = 10
        assertEquals(10, prefs.accuracyThresholdM)
    }

    @Test
    fun `accuracy threshold persists under the documented key`() {
        val fake = FakeSharedPreferences()
        val prefs = AppPreferences(fake)

        prefs.accuracyThresholdM = 50

        assertEquals(50, fake.getAll()["accuracy_threshold_m"])
    }

    @Test
    fun `accuracy threshold is coerced into the supported range`() {
        val prefs = AppPreferences(FakeSharedPreferences())

        prefs.accuracyThresholdM = 1
        assertEquals(AppPreferences.MIN_ACCURACY_THRESHOLD_M, prefs.accuracyThresholdM)

        prefs.accuracyThresholdM = 100_000
        assertEquals(AppPreferences.MAX_ACCURACY_THRESHOLD_M, prefs.accuracyThresholdM)

        // A value inside the range is stored unchanged.
        prefs.accuracyThresholdM = 30
        assertEquals(30, prefs.accuracyThresholdM)
    }

    // --- P2-7: last tracking-impairing error --------------------------------

    @Test
    fun `the last tracking error round-trips through storage`() {
        val prefs = AppPreferences(FakeSharedPreferences())

        assertNull("no error is recorded until one happens", prefs.lastTrackingError)

        prefs.lastTrackingError = "Tracking could not start: permission missing"
        assertEquals("Tracking could not start: permission missing", prefs.lastTrackingError)

        prefs.lastTrackingError = null
        assertNull(prefs.lastTrackingError)
    }

    @Test
    fun `the last tracking error is persisted under its documented key`() {
        val fake = FakeSharedPreferences()
        val prefs = AppPreferences(fake)

        prefs.lastTrackingError = "boom"

        assertEquals("boom", fake.getAll()["last_tracking_error"])
    }

    @Test
    fun `logout clears a stale tracking error`() {
        val prefs = AppPreferences(FakeSharedPreferences())
        prefs.lastTrackingError = "boom"

        prefs.clearSession()

        assertNull("a signed-out app must not show an error from the old session", prefs.lastTrackingError)
    }
}
