package com.beakon.companion.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile maths and the tap URL are pure, so the map the widget fetches and the
 * URL a tap opens are both verified without a device.
 */
class WidgetMapMathTest {

    @Test
    fun `the whole world is tile zero at zoom zero`() {
        assertEquals(0, WidgetRenderer.tileX(0.0, zoom = 0))
        assertEquals(0, WidgetRenderer.tileY(0.0, zoom = 0))
        assertEquals(0.5, WidgetRenderer.fractionX(0.0, zoom = 0), 1e-9)
        assertEquals(0.5, WidgetRenderer.fractionY(0.0, zoom = 0), 1e-9)
    }

    @Test
    fun `london maps to the expected tile at zoom 12`() {
        // Verified against the standard Web-Mercator tile scheme.
        assertEquals(2046, WidgetRenderer.tileX(-0.1278, zoom = 12))
        assertEquals(1362, WidgetRenderer.tileY(51.5074, zoom = 12))
    }

    @Test
    fun `the point's fraction within its tile is always in zero-one`() {
        for (lon in listOf(-179.9, -0.1, 0.0, 45.5, 179.9)) {
            val fraction = WidgetRenderer.fractionX(lon, zoom = 15)
            assertTrue("fractionX($lon) = $fraction", fraction >= 0.0 && fraction < 1.0)
        }
        for (lat in listOf(-80.0, -0.1, 0.0, 45.5, 80.0)) {
            val fraction = WidgetRenderer.fractionY(lat, zoom = 15)
            assertTrue("fractionY($lat) = $fraction", fraction >= 0.0 && fraction < 1.0)
        }
    }

    @Test
    fun `polar and antimeridian coordinates clamp to a valid tile`() {
        val maxIndex = (1 shl 15) - 1
        for (lat in listOf(-90.0, -85.05112878, 85.05112878, 90.0)) {
            assertTrue(WidgetRenderer.tileY(lat, zoom = 15) in 0..maxIndex)
        }
        for (lon in listOf(-180.0, 180.0)) {
            assertTrue(WidgetRenderer.tileX(lon, zoom = 15) in 0..maxIndex)
        }
    }

    @Test
    fun `the grid covers every corner of the bitmap, even at a tile edge`() {
        // daniel2 sits ~90% across its tile: the single-tile version left the
        // right of the bitmap grey. The grid must cover all four corners for
        // points at any fraction within their tile.
        val width = 480
        val height = 240
        for (lon in listOf(-122.0, -0.1278, 0.0, 45.5, 120.0)) {
            val placements = WidgetRenderer.gridTiles(33.0, lon, width, height, zoom = 15)
            for ((x, y) in listOf(
                0 to 0, width - 1 to 0, 0 to height - 1, width - 1 to height - 1,
            )) {
                val covered = placements.any { p ->
                    x >= p.destLeft && x < p.destLeft + WidgetRenderer.TILE_SIZE &&
                        y >= p.destTop && y < p.destTop + WidgetRenderer.TILE_SIZE
                }
                assertTrue("corner ($x, $y) uncovered for lon $lon", covered)
            }
        }
    }

    @Test
    fun `the point's own tile covers the canvas centre, so the pin sits on the map`() {
        val width = 480
        val height = 240
        for ((lat, lon) in listOf(33.0 to -117.0, 51.5074 to -0.1278, 0.0 to 0.0)) {
            val placements = WidgetRenderer.gridTiles(lat, lon, width, height, zoom = 15)
            val own = placements.firstOrNull { p ->
                p.tileX == WidgetRenderer.tileX(lon, 15) &&
                    p.tileY == WidgetRenderer.tileY(lat, 15)
            }
            assertTrue("point's own tile missing for $lat,$lon", own != null)
            assertTrue(own!!.destLeft <= width / 2f && width / 2f < own.destLeft + 256)
            assertTrue(own.destTop <= height / 2f && height / 2f < own.destTop + 256)
        }
    }

    @Test
    fun `the grid stays a handful of tiles at the largest capped bitmap`() {
        val placements = WidgetRenderer.gridTiles(33.0, -117.0, 1200, 480, zoom = 15)
        assertTrue("tiles=${placements.size}", placements.size <= 18)
    }

    @Test
    fun `the tile URL is the OSM PNG for the point`() {
        assertEquals(
            "https://tile.openstreetmap.org/12/2046/1362.png",
            WidgetRenderer.tileUrl(51.5074, -0.1278, zoom = 12),
        )
    }

    @Test
    fun `the tap opens the person's map on the instance`() {
        assertEquals(
            "https://example.com/map/alice",
            WidgetTap.mapUrl("https://example.com", "alice"),
        )
        assertEquals(
            "https://example.com:8443/map/bob",
            WidgetTap.mapUrl("https://example.com:8443/", "bob"),
        )
    }

    @Test
    fun `the tap falls back to the instance root with no person`() {
        assertEquals("https://example.com", WidgetTap.mapUrl("https://example.com", null))
    }

    @Test
    fun `an unusable instance URL yields no tap target`() {
        assertEquals(null, WidgetTap.mapUrl(null, "alice"))
        assertEquals(null, WidgetTap.mapUrl("http://insecure", "alice"))
    }

    @Test
    fun `a widget tap target is accepted only for the configured instance`() {
        assertTrue(WidgetTap.isSameInstance("https://example.com/map/alice", "https://example.com"))
        assertTrue(WidgetTap.isSameInstance("https://example.com:8443/map/bob", "https://example.com:8443"))
    }

    @Test
    fun `a foreign or cleartext tap target is rejected`() {
        assertFalse(WidgetTap.isSameInstance("https://evil.com/map/alice", "https://example.com"))
        assertFalse(WidgetTap.isSameInstance("https://example.com.evil.com/", "https://example.com"))
        assertFalse(WidgetTap.isSameInstance("http://example.com/map/alice", "https://example.com"))
        assertFalse(WidgetTap.isSameInstance("https://example.com:8443/map/alice", "https://example.com"))
        assertFalse(WidgetTap.isSameInstance(null, "https://example.com"))
        assertFalse(WidgetTap.isSameInstance("https://example.com/map/alice", null))
    }
}
