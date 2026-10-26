package com.beakon.companion.tracking

import com.beakon.companion.net.SendResult

/**
 * Backoff state for `429` responses from `/api/recordlocation`.
 *
 * Two mechanisms exist:
 *  - When a 429 carries `Retry-After`, [shouldAttemptAt] holds off until at least
 *    that many seconds have elapsed on the supplied clock. Absurd values are
 *    capped at [MAX_RETRY_AFTER_SECONDS].
 *  - When it does not, the existing tick-count default applies: the next
 *    [skipTicksAfterRateLimit] ticks are skipped.
 *
 * [shouldAttempt] remains the tick-count-only path used by older callers and its
 * tests. The service uses [shouldAttemptAt] so `Retry-After` is honoured.
 */
class RateLimitBackoff(
    private val skipTicksAfterRateLimit: Int = DEFAULT_SKIP_TICKS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    init {
        require(skipTicksAfterRateLimit >= 1) { "skipTicksAfterRateLimit must be >= 1" }
    }

    private var skipRemaining = 0
    private var retryAfterUntilMs = 0L
    private var retryAfterPending = false

    /**
     * Tick-count-only decision: returns true if the next tick should attempt a
     * send, consuming one skip when backing off. Kept for the existing two-tick
     * default semantics.
     */
    @Synchronized
    fun shouldAttempt(): Boolean {
        if (skipRemaining > 0) {
            skipRemaining--
            return false
        }
        return true
    }

    /**
     * Time-aware decision used by the tracking loop. While a `Retry-After`
     * deadline is in the future the tick is skipped; once the server's wait has
     * elapsed, the wait is satisfied and reporting resumes. When the header was
     * absent, the tick-count default is consumed as before.
     */
    @Synchronized
    fun shouldAttemptAt(nowMs: Long): Boolean {
        if (retryAfterPending) {
            if (nowMs < retryAfterUntilMs) return false
            // The server's wait is over; it is authoritative over tick counts.
            retryAfterPending = false
            retryAfterUntilMs = 0L
            skipRemaining = 0
            return true
        }
        if (skipRemaining > 0) {
            skipRemaining--
            return false
        }
        return true
    }

    @Synchronized
    fun recordResult(result: SendResult) {
        when (result) {
            is SendResult.RateLimited -> {
                // Keep the tick count set so the legacy tick-only path behaves
                // exactly as before. `shouldAttemptAt` ignores it when a
                // Retry-After deadline is present and time has not yet elapsed.
                skipRemaining = skipTicksAfterRateLimit
                val retryAfter = result.retryAfterSeconds?.coerceIn(0L, MAX_RETRY_AFTER_SECONDS)
                if (retryAfter == null) {
                    retryAfterPending = false
                    retryAfterUntilMs = 0L
                } else {
                    retryAfterPending = true
                    retryAfterUntilMs = clock() + retryAfter * 1000L
                }
            }
            is SendResult.Success -> {
                skipRemaining = 0
                retryAfterUntilMs = 0L
                retryAfterPending = false
            }
            else -> Unit
        }
    }

    val isBackingOff: Boolean
        @Synchronized get() = skipRemaining > 0 || (retryAfterPending && retryAfterUntilMs > clock())

    val remainingSkips: Int
        @Synchronized get() = skipRemaining

    companion object {
        /** One 429 without a header skips two ticks. */
        const val DEFAULT_SKIP_TICKS = 2

        /**
         * Upper bound for a `Retry-After` value: one hour. A hostile or broken
         * server must not be able to suppress reporting indefinitely.
         */
        const val MAX_RETRY_AFTER_SECONDS = 3_600L
    }
}
