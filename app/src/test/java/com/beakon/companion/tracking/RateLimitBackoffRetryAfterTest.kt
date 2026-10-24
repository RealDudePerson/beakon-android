package com.beakon.companion.tracking

import com.beakon.companion.net.SendResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Retry-After` must drive a time-based backoff. These tests use a fake clock so
 * no real time passes; the 90 s tracking interval itself is not involved.
 */
class RateLimitBackoffRetryAfterTest {

    @Test
    fun `Retry-After present blocks until at least that long has elapsed`() {
        var now = 1_000_000L
        val backoff = RateLimitBackoff(clock = { now })

        backoff.recordResult(SendResult.RateLimited(retryAfterSeconds = 300))

        assertFalse("must back off immediately", backoff.shouldAttemptAt(now))
        now += 299_999L
        assertFalse("must still back off one millisecond before the deadline", backoff.shouldAttemptAt(now))
        now += 1L
        assertTrue("sending resumes at the server's deadline", backoff.shouldAttemptAt(now))
    }

    @Test
    fun `Retry-After absent keeps the existing two-tick default`() {
        var now = 1_000_000L
        val backoff = RateLimitBackoff(clock = { now })

        backoff.recordResult(SendResult.RateLimited(retryAfterSeconds = null))

        assertFalse(backoff.shouldAttemptAt(now))
        now += 90_000L
        assertFalse(backoff.shouldAttemptAt(now))
        now += 90_000L
        assertTrue(backoff.shouldAttemptAt(now))
    }

    @Test
    fun `an absurd Retry-After is capped`() {
        var now = 0L
        val backoff = RateLimitBackoff(clock = { now })

        backoff.recordResult(SendResult.RateLimited(retryAfterSeconds = 86_400))

        now = (RateLimitBackoff.MAX_RETRY_AFTER_SECONDS - 1) * 1000L
        assertFalse("must not obey an absurd multi-day value", backoff.shouldAttemptAt(now))
        now = RateLimitBackoff.MAX_RETRY_AFTER_SECONDS * 1000L
        assertTrue("the cap lets reporting resume within an hour", backoff.shouldAttemptAt(now))
    }

    @Test
    fun `a success clears a pending Retry-After wait`() {
        var now = 5_000L
        val backoff = RateLimitBackoff(clock = { now })
        backoff.recordResult(SendResult.RateLimited(retryAfterSeconds = 600))
        assertFalse(backoff.shouldAttemptAt(now))

        backoff.recordResult(SendResult.Success)

        now += 1L
        assertTrue(backoff.shouldAttemptAt(now))
    }
}
