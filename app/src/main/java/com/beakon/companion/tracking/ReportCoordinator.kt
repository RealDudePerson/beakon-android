package com.beakon.companion.tracking

import com.beakon.companion.net.BeakonClient
import com.beakon.companion.net.LocationReport
import com.beakon.companion.net.SendResult

/**
 * One reporting tick: takes the current fix, applies the rate-limit backoff,
 * POSTs to the instance and records the outcome in [TrackingStateStore].
 *
 * This class is deliberately free of Android dependencies so it can be driven
 * from JVM tests (including the local-mock integration test).
 */
class ReportCoordinator(
    private val client: BeakonClient,
    private val username: String,
    private val secret: String,
    private val state: TrackingStateStore,
    private val backoff: RateLimitBackoff = RateLimitBackoff(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun report(report: LocationReport?): SendResult {
        if (report == null) {
            val result = SendResult.NoFix
            state.recordResult(result, clock())
            return result
        }
        state.recordFix(clock())

        if (!backoff.shouldAttemptAt(clock())) {
            val result = SendResult.SkippedByBackoff
            state.recordResult(result, clock())
            return result
        }

        val result = client.recordLocation(username, secret, report)
        backoff.recordResult(result)
        state.recordResult(result, clock())
        return result
    }

    val isBackingOff: Boolean get() = backoff.isBackingOff
}
