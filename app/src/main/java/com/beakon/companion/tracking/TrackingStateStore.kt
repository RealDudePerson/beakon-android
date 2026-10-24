package com.beakon.companion.tracking

import com.beakon.companion.net.SendResult
import com.beakon.companion.net.userMessage
import java.util.Calendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the Status screen shows. */
data class TrackingStatus(
    val tracking: Boolean = false,
    val lastFixAt: Long? = null,
    val lastSuccessfulSendAt: Long? = null,
    val pointsSentToday: Int = 0,
    val lastError: String? = null,
    val lastSendSucceeded: Boolean = false,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val backingOff: Boolean = false,
    /**
     * Accuracy (metres) of the most recent fix that was held back by the
     * accuracy gate, or null when nothing is currently held. Shown on the Status
     * screen so a stale map is always explained — never fail silently.
     */
    val lastHeldAccuracyM: Int? = null,
    /** The threshold in force when [lastHeldAccuracyM] was held. */
    val lastHeldThresholdM: Int? = null,
    /**
     * True when tracking is persisted as enabled but the foreground service is
     * not actually running. The Status screen shows an explicit "tap to resume"
     * state instead of claiming tracking is on.
     */
    val trackingNeedsResume: Boolean = false,
)

/**
 * In-memory tracking status, exposed as a [StateFlow] for Compose. The service
 * writes to it; the UI reads from it. Persistent fields (tracking on/off, URL,
 * username) live in [com.beakon.companion.data.AppPreferences].
 */
class TrackingStateStore(
    private val clock: () -> Long = System::currentTimeMillis,
    initialError: String? = null,
    /**
     * Persists the last tracking-impairing error, or clears it on success. The
     * error is otherwise in-memory only and would vanish on process death — and
     * the notification that would have carried it may be hidden by a denied
     * POST_NOTIFICATIONS permission. [com.beakon.companion.data.AppContainer]
     * wires this to [com.beakon.companion.data.AppPreferences].
     */
    private val persistError: (String?) -> Unit = {},
) {

    private val _status = MutableStateFlow(TrackingStatus(lastError = initialError))
    val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    private var dayOfYear: Int = dayOfYear(clock())

    fun setTracking(on: Boolean) {
        _status.value = _status.value.copy(tracking = on, trackingNeedsResume = false)
    }

    /**
     * Reconciles persisted intent ("the user wants tracking on") with actual
     * service liveness. When the two disagree the UI must show the resume state,
     * never a green toggle.
     */
    fun setTrackingHealth(trackingEnabled: Boolean, serviceRunning: Boolean) {
        _status.value = _status.value.copy(
            tracking = trackingEnabled && serviceRunning,
            trackingNeedsResume = TrackingHealthDecision.needsResume(trackingEnabled, serviceRunning),
        )
    }

    /**
     * Records an unexpected failure from a tracking tick so it is never silent.
     * The tick loop calls this before updating the persistent notification.
     */
    fun recordTickError(message: String) {
        _status.value = _status.value.copy(
            lastError = message,
            lastSendSucceeded = false,
        )
        persistError(message)
    }

    fun recordBattery(percent: Int?, charging: Boolean?) {
        _status.value = _status.value.copy(batteryPercent = percent, charging = charging)
    }

    fun recordFix(at: Long = clock()) {
        _status.value = _status.value.copy(lastFixAt = at)
    }

    fun recordResult(result: SendResult, now: Long = clock()) {
        resetDailyIfNeeded(now)
        val current = _status.value
        if (result is SendResult.Success) {
            _status.value = current.copy(
                lastSuccessfulSendAt = now,
                pointsSentToday = current.pointsSentToday + 1,
                lastError = null,
                lastSendSucceeded = true,
            )
            persistError(null)
        } else {
            val message = result.userMessage()
            _status.value = current.copy(
                lastError = message,
                lastSendSucceeded = false,
            )
            persistError(message)
        }
    }

    /**
     * Records that the current fix was held back by the accuracy gate, with the
     * numbers, so the Status screen can explain a stale map. Never fail silently.
     */
    fun recordAccuracyHold(accuracyM: Int, thresholdM: Int) {
        _status.value = _status.value.copy(
            lastHeldAccuracyM = accuracyM,
            lastHeldThresholdM = thresholdM,
        )
    }

    /** Clears a previous hold once a fix is pushed again. */
    fun clearAccuracyHold() {
        val current = _status.value
        if (current.lastHeldAccuracyM == null && current.lastHeldThresholdM == null) return
        _status.value = current.copy(lastHeldAccuracyM = null, lastHeldThresholdM = null)
    }

    fun clearError() {
        _status.value = _status.value.copy(lastError = null)
        persistError(null)
    }

    private fun resetDailyIfNeeded(now: Long) {
        val today = dayOfYear(now)
        if (today != dayOfYear) {
            dayOfYear = today
            _status.value = _status.value.copy(pointsSentToday = 0)
        }
    }

    private fun dayOfYear(time: Long): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = time
        return calendar.get(Calendar.DAY_OF_YEAR)
    }
}
