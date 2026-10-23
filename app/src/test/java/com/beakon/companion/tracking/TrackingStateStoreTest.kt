package com.beakon.companion.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The accuracy hold must be visible in tracking state, so a stale map is always
 * explained on the Status screen — never fail silently.
 */
class TrackingStateStoreTest {

    @Test
    fun `a held fix is recorded with its numbers`() {
        val store = TrackingStateStore()

        store.recordAccuracyHold(accuracyM = 42, thresholdM = 30)

        val status = store.status.value
        assertEquals(42, status.lastHeldAccuracyM)
        assertEquals(30, status.lastHeldThresholdM)
    }

    @Test
    fun `a pushed fix clears a previous hold`() {
        val store = TrackingStateStore()
        store.recordAccuracyHold(accuracyM = 42, thresholdM = 30)

        store.clearAccuracyHold()

        val status = store.status.value
        assertNull(status.lastHeldAccuracyM)
        assertNull(status.lastHeldThresholdM)
    }
}
