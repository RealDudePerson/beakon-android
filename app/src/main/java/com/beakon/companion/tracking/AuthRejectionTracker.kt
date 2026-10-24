package com.beakon.companion.tracking

import com.beakon.companion.net.SendResult

/**
 * Counts consecutive authentication rejections from the server, so a dead
 * API secret stops tracking instead of being retried forever.
 *
 * Only [SendResult.AuthRejected] increments the count, and only
 * [SendResult.Success] resets it. Other outcomes (no fix, no network, a
 * rate limit, a server error) neither count nor reset: they say nothing
 * about whether the secret is still valid, and a flaky network must not
 * let a dead secret keep hammering the server indefinitely.
 */
class AuthRejectionTracker(
    private val maxConsecutive: Int = MAX_CONSECUTIVE_AUTH_REJECTIONS,
) {
    private var consecutive = 0

    /**
     * Records [result]. Returns true exactly when the threshold is reached
     * and the caller should stop tracking and ask the user to sign in again.
     */
    fun onResult(result: SendResult): Boolean {
        when (result) {
            is SendResult.AuthRejected -> {
                consecutive++
                return consecutive >= maxConsecutive
            }
            is SendResult.Success -> consecutive = 0
            else -> Unit
        }
        return false
    }

    fun reset() {
        consecutive = 0
    }

    companion object {
        /** Three consecutive 401s, at the default 90 s interval, is ~4.5 minutes. */
        const val MAX_CONSECUTIVE_AUTH_REJECTIONS = 3
    }
}
