package com.beakon.companion.ui.screens

import com.beakon.companion.net.LocationDto

/**
 * What an empty history list should say. A successful zero-point fetch is
 * explained instead of leaving a blank list. (The Map tab no longer renders
 * points itself — it shows the server dashboard in a WebView.)
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
