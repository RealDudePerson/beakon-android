package com.beakon.companion.ui

import android.app.Application
import com.beakon.companion.net.LocationDto
import com.beakon.companion.tracking.TrackingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * History place names: one lookup per coordinate, cached in memory, and a
 * failed lookup leaves the row showing coordinates instead of failing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelGeocodeTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val oceanside = LocationDto(lat = 33.19512, lon = -117.37948, timestamp = "just now")
    private val carlsbad = LocationDto(lat = 33.15809, lon = -117.35059, timestamp = "1 hour ago")

    @Test
    fun `a resolved place name is cached by coordinate`() = runTest(mainDispatcher) {
        val geocode = CountingGeocode(mapOf(oceanside to "Oceanside, California"))
        val viewModel = viewModel(geocode)

        viewModel.loadPlaceName(oceanside)
        awaitSettled { viewModel.placeNames.isNotEmpty() }
        viewModel.loadPlaceName(oceanside)
        advanceUntilIdle()

        assertEquals(
            "Oceanside, California",
            viewModel.placeNames[geocodeCacheKey(oceanside.lat, oceanside.lon)],
        )
        assertEquals("a cached name must not re-query", 1, geocode.calls)
    }

    @Test
    fun `two lookups for the same point before either finishes fire once`() =
        runTest(mainDispatcher) {
            val geocode = CountingGeocode(mapOf(oceanside to "Oceanside, California"))
            val viewModel = viewModel(geocode)

            viewModel.loadPlaceName(oceanside)
            viewModel.loadPlaceName(oceanside)
            awaitSettled { viewModel.placeNames.isNotEmpty() }

            assertEquals(1, geocode.calls)
        }

    @Test
    fun `different points get separate lookups`() = runTest(mainDispatcher) {
        val geocode = CountingGeocode(
            mapOf(
                oceanside to "Oceanside, California",
                carlsbad to "Carlsbad, California",
            ),
        )
        val viewModel = viewModel(geocode)

        viewModel.loadPlaceName(oceanside)
        viewModel.loadPlaceName(carlsbad)
        awaitSettled { viewModel.placeNames.size == 2 }

        assertEquals(2, geocode.calls)
        assertEquals(
            "Carlsbad, California",
            viewModel.placeNames[geocodeCacheKey(carlsbad.lat, carlsbad.lon)],
        )
    }

    @Test
    fun `a failed lookup leaves no name and does not crash`() = runTest(mainDispatcher) {
        val geocode = CountingGeocode(emptyMap())
        val viewModel = viewModel(geocode)

        viewModel.loadPlaceName(oceanside)
        advanceUntilIdle()

        assertTrue("a null lookup must not fabricate a name", viewModel.placeNames.isEmpty())
        assertEquals(1, geocode.calls)
    }

    // --- helpers -----------------------------------------------------------

    private fun viewModel(geocode: GeocodeLookup): AppViewModel = AppViewModel(
        application = Application(),
        state = FakeGeocodeState(),
        geocode = geocode,
    )

    private fun TestScope.awaitSettled(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(2)
        }
        advanceUntilIdle()
        assertTrue("timed out waiting for the ViewModel to settle", condition())
    }
}

private class CountingGeocode(private val names: Map<LocationDto, String>) : GeocodeLookup {
    var calls: Int = 0
        private set

    override suspend fun placeName(lat: Double, lon: Double): String? {
        calls++
        return names.entries.firstOrNull { it.key.lat == lat && it.key.lon == lon }?.value
    }
}

private class FakeGeocodeState : AppViewModelState {
    override var instanceUrl: String? = "https://location2.navettas.com"
    override var username: String? = "demo"
    override var intervalSeconds: Int = 90
    override var accuracyThresholdM: Int = 30

    private val _trackingStatus = MutableStateFlow(TrackingStatus())
    override val trackingStatus: StateFlow<TrackingStatus> = _trackingStatus

    override val startTrackingOnBoot: StateFlow<Boolean> = MutableStateFlow(false)

    override fun setStartTrackingOnBoot(on: Boolean) = Unit

    override fun clearSession() = Unit

    override fun pinFor(host: String): String? = null

    override fun storePin(host: String, sha256: String) = Unit

    override fun clearPin(host: String) = Unit

    override fun secret(): String? = "secret"

    override fun storeSecret(secret: String) = Unit

    override fun session(): String? = "session"

    override fun storeSession(value: String) = Unit

    override fun clearSecrets() = Unit

    override fun setTracking(on: Boolean) = Unit
}
