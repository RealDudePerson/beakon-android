package com.beakon.companion.ui.screens

import com.beakon.companion.net.LocationDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P2-2 empty states and P2-4 use of the API's `latest` field. */
class PointsUiStateTest {

    private val a = LocationDto(lat = 1.0, lon = 1.0, timestamp = "1 hour ago")
    private val b = LocationDto(lat = 2.0, lon = 2.0, timestamp = "just now")

    // --- P2-2: empty states ------------------------------------------------

    @Test
    fun `a successful zero-point fetch asks for the no-points state`() {
        assertEquals(
            PointsEmptyState.NoPoints,
            emptyPointsState(points = emptyList(), loading = false, message = null),
        )
    }

    @Test
    fun `while loading there is no empty state`() {
        assertEquals(
            PointsEmptyState.None,
            emptyPointsState(points = emptyList(), loading = true, message = null),
        )
    }

    @Test
    fun `an error message is the explanation, not no-points`() {
        assertEquals(
            PointsEmptyState.None,
            emptyPointsState(
                points = emptyList(),
                loading = false,
                message = "Could not load history: no network",
            ),
        )
    }

    @Test
    fun `captured points suppress the empty state`() {
        assertEquals(
            PointsEmptyState.None,
            emptyPointsState(points = listOf(a, b), loading = false, message = null),
        )
    }

    // --- P2-4: latest comes from the API field -----------------------------

    @Test
    fun `a differently ordered list still marks the API's latest point`() {
        // The list order says `b` is last, but the server says `a` is latest.
        assertSame(a, latestMarkerPoint(latest = a, points = listOf(b, a)))
    }

    @Test
    fun `an older response without a latest field falls back to list order`() {
        assertSame(b, latestMarkerPoint(latest = null, points = listOf(a, b)))
    }

    @Test
    fun `no points means no marker`() {
        assertNull(latestMarkerPoint(latest = null, points = emptyList()))
    }

    private fun assertSame(expected: LocationDto, actual: LocationDto?) {
        assertEquals("wrong marker point", expected, actual)
    }
}
