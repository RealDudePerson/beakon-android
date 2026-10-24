package com.beakon.companion.tracking

import com.beakon.companion.net.SendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-11: the tick's pure decisions — accuracy hold, the order in which the fix
 * and hold are recorded, and the notification text — are exercised here instead
 * of only being reachable on a device.
 */
class TickWiringTest {

    @Test
    fun `a coarse fix becomes a hold with rounded numbers and an explanatory text`() {
        val action = TickWiring.fixAction(accuracy = 41.6, thresholdM = 30)

        assertTrue("expected a hold, was $action", action is TickWiring.FixAction.Hold)
        action as TickWiring.FixAction.Hold
        assertEquals("the recorded accuracy is rounded for display", 42, action.accuracyM)
        assertEquals(30, action.thresholdM)
        assertEquals(AccuracyGate.holdMessage(41.6, 30), action.notification)
    }

    @Test
    fun `a fix exactly at the threshold is pushed`() {
        assertEquals(TickWiring.FixAction.Report, TickWiring.fixAction(accuracy = 30.0, thresholdM = 30))
    }

    @Test
    fun `a fix with no accuracy is still pushed`() {
        assertEquals(
            "an unjudgeable fix must not be dropped silently",
            TickWiring.FixAction.Report,
            TickWiring.fixAction(accuracy = null, thresholdM = 30),
        )
    }

    @Test
    fun `a held fix records the fix before the hold`() {
        val calls = mutableListOf<String>()

        TickWiring.recordHeldFix(
            recordFix = { calls += "fix" },
            recordHold = { calls += "hold" },
        )

        assertEquals(listOf("fix", "hold"), calls)
    }

    @Test
    fun `notification text names success`() {
        assertEquals(
            "Location reported successfully.",
            TickWiring.notificationText(SendResult.Success, hadFix = true),
        )
    }

    @Test
    fun `notification text surfaces a failure reason`() {
        val text = TickWiring.notificationText(SendResult.NoFix, hadFix = false)

        assertTrue("a NoFix tick must explain itself, was: $text", text.contains("No location fix"))
    }

    @Test
    fun `notification text surfaces the rate-limit reason`() {
        val text = TickWiring.notificationText(
            SendResult.RateLimited(retryAfterSeconds = 120),
            hadFix = true,
        )

        assertTrue("a 429 must explain the backoff, was: $text", text.contains("429"))
        assertTrue("the server's wait must be shown, was: $text", text.contains("120"))
    }
}
