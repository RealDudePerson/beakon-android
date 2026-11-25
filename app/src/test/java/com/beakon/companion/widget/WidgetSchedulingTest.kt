package com.beakon.companion.widget

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Acceptance criterion 6: the periodic refresh runs every 15 minutes and only
 * with a network connection. The values live in [WidgetSchedule] so they are
 * assertable without the Android `WorkManager` runtime.
 */
class WidgetSchedulingTest {

    @Test
    fun `the periodic refresh interval is 15 minutes`() {
        assertEquals(15L, WidgetSchedule.REFRESH_INTERVAL_MINUTES)
    }

    @Test
    fun `the refresh requires a network connection`() {
        assertEquals(NetworkType.CONNECTED, WidgetSchedule.REQUIRED_NETWORK_TYPE)
    }
}
