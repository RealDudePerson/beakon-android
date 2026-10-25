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
