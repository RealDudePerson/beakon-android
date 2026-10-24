package com.beakon.companion.ui.screens

import com.beakon.companion.net.LocationDto

/**
 * What an empty history/map should say. Both screens share this decision so a
 * successful zero-point fetch is explained instead of leaving a blank list or a
 * map parked on osmdroid's default (0,0).
 */
enum class PointsEmptyState {
    /** There is something to show, or a load/error is already being explained. */
    None,

    /** The fetch finished with no error and no points. */
    NoPoints,
}

internal fun emptyPointsState(
    points: List<LocationDto>,
    loading: Boolean,
    message: String?,
): PointsEmptyState = when {
    points.isNotEmpty() -> PointsEmptyState.None
    loading -> PointsEmptyState.None
    // An error message is already the explanation; do not claim "no points yet"
    // when the fetch actually failed.
    message != null -> PointsEmptyState.None
    else -> PointsEmptyState.NoPoints
}

/**
 * The point to mark as "latest".
 *
 * The server's `latest` field is authoritative. Falling back to the last list
 * element only covers an older/mock response that omits it; with the field
 * present, a differently-ordered list must still mark the correct point.
 */
internal fun latestMarkerPoint(
    latest: LocationDto?,
    points: List<LocationDto>,
): LocationDto? = latest ?: points.lastOrNull()
