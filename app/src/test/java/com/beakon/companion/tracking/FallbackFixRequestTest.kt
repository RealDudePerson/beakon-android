package com.beakon.companion.tracking

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-9: the fallback live-fix request must not ask the provider for updates as
 * fast as it can (`minTime = 0`, `minDistance = 0`). The values stay well below
 * the 20 s timeout, so the first callback still wins.
 */
class FallbackFixRequestTest {

    @Test
    fun `the fallback request asks for a sane minimum time`() {
        assertTrue(
            "minTime must be greater than 0, was ${FallbackFixRequest.MIN_TIME_MS}",
            FallbackFixRequest.MIN_TIME_MS > 0L,
        )
        assertTrue(
            "minTime must stay below the 20 s fix timeout",
            FallbackFixRequest.MIN_TIME_MS < 20_000L,
        )
    }

    @Test
    fun `the fallback request asks for a sane minimum distance`() {
        assertTrue(
            "minDistance must be greater than 0 m, was ${FallbackFixRequest.MIN_DISTANCE_M}",
            FallbackFixRequest.MIN_DISTANCE_M > 0f,
        )
        assertTrue(
            "minDistance must remain small enough for a usable first fix",
            FallbackFixRequest.MIN_DISTANCE_M <= 5f,
        )
    }
}
