package com.beakon.companion.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Acceptance criterion 3: a pure function maps `age_seconds` to the display
 * string, tested at the boundaries. Criterion 5: a missing `age_seconds` falls
 * back to `timestamp_unix`, then to "unknown age".
 */
class WidgetAgeTest {

    private val now = 1_800_000_000L

    @Test
    fun `zero seconds is just now`() {
        assertEquals("just now", WidgetAge.format(0, null, now))
    }

    @Test
    fun `fifty-nine seconds is still just now`() {
        assertEquals("just now", WidgetAge.format(59, null, now))
    }

    @Test
    fun `sixty seconds is one minute`() {
        assertEquals("1 min ago", WidgetAge.format(60, null, now))
    }

    @Test
    fun `just under an hour stays in minutes`() {
        assertEquals("59 min ago", WidgetAge.format(3_599, null, now))
    }

    @Test
    fun `one hour switches to hours`() {
        assertEquals("1 h ago", WidgetAge.format(3_600, null, now))
    }

    @Test
    fun `a large age is reported in whole hours`() {
        assertEquals("24 h ago", WidgetAge.format(86_400, null, now))
        assertEquals("240 h ago", WidgetAge.format(864_000, null, now))
    }

    @Test
    fun `a missing age_seconds falls back to timestamp_unix`() {
        assertEquals("2 min ago", WidgetAge.format(null, now - 120, now))
    }

    @Test
    fun `a missing age_seconds and no timestamp is unknown`() {
        assertEquals("unknown age", WidgetAge.format(null, null, now))
    }

    @Test
    fun `a future timestamp (clock skew) reads as just now, never negative`() {
        assertEquals("just now", WidgetAge.format(null, now + 30, now))
        assertEquals("just now", WidgetAge.format(-5, null, now))
    }
}
