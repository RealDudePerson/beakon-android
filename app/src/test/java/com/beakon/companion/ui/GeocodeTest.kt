package com.beakon.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeocodeTest {

    @Test
    fun `a full address line wins over its parts`() {
        assertEquals(
            "1600 Amphitheatre Parkway, Mountain View, CA",
            formatPlaceName(
                addressLine = "1600 Amphitheatre Parkway, Mountain View, CA",
                locality = "Mountain View",
                adminArea = "California",
                countryName = "United States",
            ),
        )
    }

    @Test
    fun `parts are joined when there is no address line`() {
        assertEquals(
            "Oceanside, California, United States",
            formatPlaceName(
                addressLine = null,
                locality = "Oceanside",
                adminArea = "California",
                countryName = "United States",
            ),
        )
    }

    @Test
    fun `blank parts are skipped, not joined as empty segments`() {
        assertEquals(
            "Oceanside, United States",
            formatPlaceName(
                addressLine = "  ",
                locality = "Oceanside",
                adminArea = null,
                countryName = "United States",
            ),
        )
    }

    @Test
    fun `no usable part means no place name`() {
        assertNull(
            formatPlaceName(
                addressLine = null,
                locality = null,
                adminArea = "  ",
                countryName = null,
            ),
        )
    }

    @Test
    fun `cache keys round to the displayed precision`() {
        assertEquals(
            geocodeCacheKey(33.19512, -117.37948),
            geocodeCacheKey(33.195123, -117.379481),
        )
    }

    @Test
    fun `nominatim display name is extracted`() {
        assertEquals(
            "Poipu Beach, Poipu Road, Koloa, Kauai County, Hawaii, 96756, United States",
            parseNominatimDisplayName(
                """{"place_id":1,"display_name":"Poipu Beach, Poipu Road, Koloa, Kauai County, Hawaii, 96756, United States","lat":"21.8738","lon":"-159.4497"}""",
            ),
        )
    }

    @Test
    fun `nominatim error and blank names yield nothing`() {
        assertNull(parseNominatimDisplayName("""{"error":"Unable to geocode"}"""))
        assertNull(parseNominatimDisplayName("""{"display_name":"  "}"""))
        assertNull(parseNominatimDisplayName("not json"))
    }

    private class FixedGeocode(
        private val result: String?,
        private val onCall: () -> Unit = {},
    ) : GeocodeLookup {
        override suspend fun placeName(lat: Double, lon: Double): String? {
            onCall()
            return result
        }
    }

    private class ThrowingGeocode : GeocodeLookup {
        override suspend fun placeName(lat: Double, lon: Double): String? {
            throw RuntimeException("geocoder down")
        }
    }

    @Test
    fun `fallback is used only when the primary yields nothing`() {
        var fallbackCalls = 0
        val fallback = { FixedGeocode("Fallback Place") { fallbackCalls++ } }
        val primaryHit = FallbackGeocodeLookup(
            FixedGeocode("Primary Place"),
            fallback(),
        )
        val primaryMiss = FallbackGeocodeLookup(
            FixedGeocode(null),
            fallback(),
        )
        val primaryThrows = FallbackGeocodeLookup(
            ThrowingGeocode(),
            fallback(),
        )
        kotlinx.coroutines.test.runTest {
            assertEquals("Primary Place", primaryHit.placeName(21.0, -159.0))
            assertEquals("Fallback Place", primaryMiss.placeName(21.0, -159.0))
            assertEquals("Fallback Place", primaryThrows.placeName(21.0, -159.0))
        }
        assertEquals("fallback must not run when the primary hits", 2, fallbackCalls)
    }

    @Test
    fun `cache keys distinguish different points`() {
        assertEquals(
            "33.19512,-117.37948",
            geocodeCacheKey(33.19512, -117.37948),
        )
        assertEquals(
            "33.19513,-117.37948",
            geocodeCacheKey(33.19513, -117.37948),
        )
    }
}
