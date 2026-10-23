package com.beakon.companion.ui.screens

import com.beakon.companion.net.LocationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history list can no longer be exercised on a device here, so the
 * order/key logic that replaced `history.asReversed()` with no key is covered
 * directly. These are pure functions; they do not test rendering.
 */
class HistoryListKeyTest {

    private fun point(lat: Double, lon: Double, timestamp: String?) =
        LocationDto(lat = lat, lon = lon, timestamp = timestamp)

    @Test
    fun `newest first puts the last fetched point at the top`() {
        val oldest = point(1.0, 1.0, "1 hour ago")
        val newest = point(2.0, 2.0, "just now")
        val rows = newestFirstKeyed(listOf(oldest, newest))
        assertEquals(listOf(newest, oldest), rows.map { it.point })
    }

    @Test
    fun `keys are content based and do not depend on position`() {
        val a = point(1.0, 2.0, "just now")
        val b = point(3.0, 4.0, "2 minutes ago")
        val first = newestFirstKeyed(listOf(a, b)).associate { it.point to it.key }
        val second = newestFirstKeyed(listOf(b, a)).associate { it.point to it.key }
        assertEquals(first, second)
    }

    @Test
    fun `points that share a timestamp and coordinates still get unique keys`() {
        val duplicate = point(1.0, 2.0, "just now")
        val keys = newestFirstKeyed(listOf(duplicate, duplicate)).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `an empty history produces no rows`() {
        assertTrue(newestFirstKeyed(emptyList()).isEmpty())
    }
}
