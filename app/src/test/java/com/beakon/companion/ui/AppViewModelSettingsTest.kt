package com.beakon.companion.ui

import android.app.Application
import com.beakon.companion.tracking.TrackingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The settings setters write through to storage and then reflect the stored
 * value back into the UI state, so the two can never drift. Mirrors the
 * interval pair for the accuracy threshold.
 */
class AppViewModelSettingsTest {

    @Test
    fun `accuracy threshold is read from storage on construction`() {
        val state = FakeSettingsState(initialThreshold = 50)
        val viewModel = AppViewModel(application = Application(), state = state)

        assertEquals(50, viewModel.accuracyThresholdM)
    }

    @Test
    fun `accuracy threshold setter writes through to the pref and reflects it`() {
        val state = FakeSettingsState(initialThreshold = 30)
        val viewModel = AppViewModel(application = Application(), state = state)

        viewModel.setAccuracyThreshold(50)

        assertEquals("the setter must write through to storage", 50, state.accuracyThresholdM)
        assertEquals("the UI value must reflect storage", 50, viewModel.accuracyThresholdM)
    }

    @Test
    fun `accuracy threshold setter reflects the value storage actually kept`() {
        // In production the store coerces out-of-range values (AppPreferences).
        // The ViewModel must show the persisted value, not the raw argument.
        val state = FakeSettingsState(initialThreshold = 30, coerce = { it.coerceIn(5, 500) })
        val viewModel = AppViewModel(application = Application(), state = state)

        viewModel.setAccuracyThreshold(100_000)

        assertEquals(500, state.accuracyThresholdM)
        assertEquals(500, viewModel.accuracyThresholdM)
    }
}

/**
 * Minimal [AppViewModelState] for the settings setters; every other member is
 * unused here. [coerce] lets a test stand in for the preference layer's
 * clamping.
 */
private class FakeSettingsState(
    initialThreshold: Int = 30,
    private val coerce: (Int) -> Int = { it },
) : AppViewModelState {

    private var threshold = initialThreshold

    override var accuracyThresholdM: Int
        get() = threshold
        set(value) {
            threshold = coerce(value)
        }

    override var instanceUrl: String? = null
    override var username: String? = null
    override var intervalSeconds: Int = 90

    override val startTrackingOnBoot: StateFlow<Boolean> = MutableStateFlow(false)
    override fun setStartTrackingOnBoot(on: Boolean) = Unit
    override fun clearSession() = Unit
    override fun pinFor(host: String): String? = null
    override fun storePin(host: String, sha256: String) = Unit
    override fun clearPin(host: String) = Unit
    override fun secret(): String? = null
    override fun storeSecret(secret: String) = Unit
    override fun session(): String? = null
    override fun storeSession(value: String) = Unit
    override fun clearSecrets() = Unit
    override val trackingStatus: StateFlow<TrackingStatus> = MutableStateFlow(TrackingStatus())
    override fun setTracking(on: Boolean) = Unit
}
