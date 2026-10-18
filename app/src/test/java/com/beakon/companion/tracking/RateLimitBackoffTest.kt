package com.beakon.companion.tracking

import com.beakon.companion.net.SendResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitBackoffTest {

    @Test
    fun `after a 429 the next send is skipped`() {
        val backoff = RateLimitBackoff(skipTicksAfterRateLimit = 1)

        backoff.recordResult(SendResult.RateLimited(retryAfterSeconds = 60))

        assertTrue(backoff.isBackingOff)
        assertFalse("the tick immediately after a 429 must be skipped", backoff.shouldAttempt())
        assertTrue("sending resumes after the skip", backoff.shouldAttempt())
    }

    @Test
    fun `the default backoff skips two ticks after a 429`() {
        val backoff = RateLimitBackoff()

        backoff.recordResult(SendResult.RateLimited(null))

        assertFalse(backoff.shouldAttempt())
        assertFalse(backoff.shouldAttempt())
        assertTrue(backoff.shouldAttempt())
    }

    @Test
    fun `a successful send clears pending backoff`() {
        val backoff = RateLimitBackoff()
        backoff.recordResult(SendResult.RateLimited(null))

        backoff.recordResult(SendResult.Success)

        assertFalse(backoff.isBackingOff)
        assertTrue(backoff.shouldAttempt())
    }

    @Test
    fun `non rate-limit failures do not trigger backoff`() {
        val backoff = RateLimitBackoff()

        backoff.recordResult(SendResult.NetworkError("offline"))
        assertTrue(backoff.shouldAttempt())

        backoff.recordResult(SendResult.AuthRejected)
        assertTrue(backoff.shouldAttempt())

        backoff.recordResult(SendResult.NoFix)
        assertTrue(backoff.shouldAttempt())
    }
}
