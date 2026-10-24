package com.beakon.companion.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-3: the camera is centred once, on the first load with points. A later
 * refresh must not snap it back from wherever the user panned.
 */
class MapCameraTest {

    @Test
    fun `the first load with points centres the camera`() {
        assertTrue(MapCamera.shouldCentre(hasCentred = false, hasPoints = true))
    }

    @Test
    fun `a later refresh does not move the camera`() {
        assertFalse(
            "a refresh must not steal the camera from the user",
            MapCamera.shouldCentre(hasCentred = true, hasPoints = true),
        )
    }

    @Test
    fun `an empty first load does not centre`() {
        assertFalse(MapCamera.shouldCentre(hasCentred = false, hasPoints = false))
    }
}
