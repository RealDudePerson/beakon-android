package com.beakon.companion.tracking

import com.beakon.companion.net.SendResult

/**
 * Backoff state for the `/api/recordlocation` rate limit (5 requests / 300 s / IP).
 *
 * After a 429 the next [skipTicksAfterRateLimit] ticks are skipped rather than
 * hammering the endpoint. A successful send clears any pending backoff.
 */
class RateLimitBackoff(
    private val skipTicksAfterRateLimit: Int = DEFAULT_SKIP_TICKS,
) {

    init {
        require(skipTicksAfterRateLimit >= 1) { "skipTicksAfterRateLimit must be >= 1" }
    }

    private var skipRemaining = 0

    /**
     * Returns true if the next tick should attempt a send. When backing off it
     * consumes one tick and returns false, so a single 429 skips exactly
     * [skipTicksAfterRateLimit] sends.
     */
    @Synchronized
    fun shouldAttempt(): Boolean {
        if (skipRemaining > 0) {
            skipRemaining--
            return false
        }
        return true
    }

    @Synchronized
    fun recordResult(result: SendResult) {
        when (result) {
            is SendResult.RateLimited -> skipRemaining = skipTicksAfterRateLimit
            is SendResult.Success -> skipRemaining = 0
            else -> Unit
        }
    }

    val isBackingOff: Boolean
        @Synchronized get() = skipRemaining > 0

    val remainingSkips: Int
        @Synchronized get() = skipRemaining

    companion object {
        /** One 429 skips two ticks; at a 90 s interval that clears the 300 s window. */
        const val DEFAULT_SKIP_TICKS = 2
    }
}
