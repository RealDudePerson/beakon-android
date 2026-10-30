package com.beakon.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The additive epoch is the only server field a client can turn into a real
 * age; the display string is not parseable. These cover that contract.
 */
class FormatTest {

    @Test
    fun `serverTime shows the display string verbatim`() {
        assertEquals("just now", Format.serverTime("just now"))
        assertEquals("18 hours ago", Format.serverTime("18 hours ago"))
    }

    @Test
    fun `serverTime falls back to a dash when absent`() {
        assertEquals("—", Format.serverTime(null))
    }

    @Test
    fun `ageSeconds is null when the server sent no epoch`() {
        assertNull(Format.ageSeconds(timestampUnix = null, nowMillis = 1_000_000L))
    }

    @Test
    fun `ageSeconds is the whole number of seconds since the epoch`() {
        // now = 1_000_000 ms -> 1000 s; epoch 940 -> 60 s old.
        assertEquals(60L, Format.ageSeconds(timestampUnix = 940L, nowMillis = 1_000_000L))
    }

    @Test
    fun `ageSeconds floors the sub-second part of now`() {
        // now = 1_000_999 ms -> 1000 s (floored); epoch 1000 -> 0.
        assertEquals(0L, Format.ageSeconds(timestampUnix = 1000L, nowMillis = 1_000_999L))
    }
}
