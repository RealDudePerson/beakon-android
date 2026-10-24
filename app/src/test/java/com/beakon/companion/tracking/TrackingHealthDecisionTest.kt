package com.beakon.companion.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingHealthDecisionTest {

    @Test
    fun `enabled but not running needs an explicit resume state`() {
        assertTrue(TrackingHealthDecision.needsResume(trackingEnabled = true, serviceRunning = false))
    }

    @Test
    fun `enabled and running is healthy`() {
        assertFalse(TrackingHealthDecision.needsResume(trackingEnabled = true, serviceRunning = true))
    }

    @Test
    fun `disabled never asks to resume`() {
        assertFalse(TrackingHealthDecision.needsResume(trackingEnabled = false, serviceRunning = false))
        assertFalse(TrackingHealthDecision.needsResume(trackingEnabled = false, serviceRunning = true))
    }

    @Test
    fun `the store never reports tracking on while the service is dead`() {
        val store = TrackingStateStore()

        store.setTrackingHealth(trackingEnabled = true, serviceRunning = false)

        val status = store.status.value
        assertFalse("the toggle must not look green when nothing is running", status.tracking)
        assertTrue("the UI must be able to show the resume state", status.trackingNeedsResume)
    }

    @Test
    fun `the store clears the resume state once the service is running`() {
        val store = TrackingStateStore()
        store.setTrackingHealth(trackingEnabled = true, serviceRunning = false)

        store.setTrackingHealth(trackingEnabled = true, serviceRunning = true)

        val status = store.status.value
        assertTrue(status.tracking)
        assertFalse(status.trackingNeedsResume)
        assertEquals(false, TrackingHealthDecision.needsResume(true, true))
    }
}
