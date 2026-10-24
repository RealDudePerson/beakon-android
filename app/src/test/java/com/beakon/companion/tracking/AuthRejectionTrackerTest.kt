package com.beakon.companion.tracking

import com.beakon.companion.net.SendResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRejectionTrackerTest {

    @Test
    fun `three consecutive rejections trip the threshold`() {
        val tracker = AuthRejectionTracker()

        assertFalse(tracker.onResult(SendResult.AuthRejected))
        assertFalse(tracker.onResult(SendResult.AuthRejected))
        assertTrue("the third consecutive 401 must stop tracking", tracker.onResult(SendResult.AuthRejected))
    }

    @Test
    fun `a success resets the count`() {
        val tracker = AuthRejectionTracker()

        tracker.onResult(SendResult.AuthRejected)
        tracker.onResult(SendResult.AuthRejected)
        tracker.onResult(SendResult.Success)
        assertFalse(tracker.onResult(SendResult.AuthRejected))
        assertFalse(tracker.onResult(SendResult.AuthRejected))
    }

    @Test
    fun `non-auth outcomes neither count nor reset`() {
        val tracker = AuthRejectionTracker()

        tracker.onResult(SendResult.AuthRejected)
        // A network outage in between must not let a dead secret run forever:
        // the next two 401s still trip the threshold.
        tracker.onResult(SendResult.NetworkError("offline"))
        tracker.onResult(SendResult.NoFix)
        assertFalse(tracker.onResult(SendResult.AuthRejected))
        assertTrue(tracker.onResult(SendResult.AuthRejected))
    }

    @Test
    fun `reset clears the count`() {
        val tracker = AuthRejectionTracker()

        tracker.onResult(SendResult.AuthRejected)
        tracker.onResult(SendResult.AuthRejected)
        tracker.reset()
        assertFalse(tracker.onResult(SendResult.AuthRejected))
    }
}
