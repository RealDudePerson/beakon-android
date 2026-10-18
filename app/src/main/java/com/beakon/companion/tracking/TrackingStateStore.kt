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
)

/**
 * In-memory tracking status, exposed as a [StateFlow] for Compose. The service
 * writes to it; the UI reads from it. Persistent fields (tracking on/off, URL,
 * username) live in [com.beakon.companion.data.AppPreferences].
 */
class TrackingStateStore(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _status = MutableStateFlow(TrackingStatus())
    val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    private var dayOfYear: Int = dayOfYear(clock())

    fun setTracking(on: Boolean) {
        _status.value = _status.value.copy(tracking = on)
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
        } else {
            _status.value = current.copy(
                lastError = result.userMessage(),
                lastSendSucceeded = false,
            )
        }
    }

    fun clearError() {
        _status.value = _status.value.copy(lastError = null)
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
