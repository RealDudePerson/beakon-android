package com.beakon.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIntervalTest {

    @Test
    fun `interval defaults to 90 seconds`() {
        assertEquals(90, AppPreferences(FakeSharedPreferences()).intervalSeconds)
    }

    @Test
    fun `interval bounds are 1 and 10000 seconds inclusive`() {
        val prefs = AppPreferences(FakeSharedPreferences())
        prefs.intervalSeconds = 1
        assertEquals(1, prefs.intervalSeconds)
        prefs.intervalSeconds = 10000
        assertEquals(10000, prefs.intervalSeconds)
    }

    @Test
    fun `interval values outside the range are clamped`() {
        val prefs = AppPreferences(FakeSharedPreferences())
        prefs.intervalSeconds = 0
        assertEquals(1, prefs.intervalSeconds)
        prefs.intervalSeconds = 10001
        assertEquals(10000, prefs.intervalSeconds)
    }
}
