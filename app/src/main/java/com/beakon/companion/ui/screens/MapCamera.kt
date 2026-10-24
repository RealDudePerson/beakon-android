package com.beakon.companion.ui.screens

/**
 * Whether the map should move its camera to the latest point.
 *
 * The camera is centred once, on the first load that has points. Later refreshes
 * update the overlays but never snap the camera away from where the user panned.
 */
internal object MapCamera {
    fun shouldCentre(hasCentred: Boolean, hasPoints: Boolean): Boolean =
        !hasCentred && hasPoints
}

/** Non-composable holder so mutating it from `AndroidView.update` is not state. */
internal class MapCameraState {
    var hasCentred: Boolean = false
}
