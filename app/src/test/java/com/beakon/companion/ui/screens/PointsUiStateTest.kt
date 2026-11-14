package com.beakon.companion.ui.screens

import com.beakon.companion.net.LocationDto
import org.junit.Assert.assertEquals
import org.junit.Test

/** P2-2 empty states. */
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
}
