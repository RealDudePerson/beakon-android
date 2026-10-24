package com.beakon.companion.tracking

/**
 * Reconciles the persisted "user wants tracking on" preference with whether the
 * foreground service is actually running. Kept Android-free so the decision can
 * be unit-tested; the Status screen only renders the result.
 */
object TrackingHealthDecision {

    /**
     * True only when tracking was enabled but the service is not running. The UI
     * must then offer an explicit resume action instead of a green toggle.
     */
    fun needsResume(trackingEnabled: Boolean, serviceRunning: Boolean): Boolean =
        trackingEnabled && !serviceRunning
}
