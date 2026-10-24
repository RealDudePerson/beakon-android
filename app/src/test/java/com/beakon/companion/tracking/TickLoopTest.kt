package com.beakon.companion.tracking

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TickLoopTest {

    @Test
    fun `a throwing tick is recorded and the loop keeps running`() = runTest {
        val store = TrackingStateStore()
        var ticks = 0

        val job = launch {
            TickLoop.run(
                intervalMs = 90_000L,
                tick = {
                    ticks++
                    if (ticks == 1) throw IllegalStateException("boom")
                    // End the otherwise-infinite loop from inside the second tick.
                    if (ticks == 2) currentCoroutineContext().cancel()
                },
                onError = { error ->
                    store.recordTickError("Tracking tick failed: ${error.message}")
                },
            )
        }
        job.join()

        assertEquals("the loop must run again after a failed tick", 2, ticks)
        assertEquals(
            "the failure must be recorded for the Status screen",
            "Tracking tick failed: boom",
            store.status.value.lastError,
        )
        assertTrue("the test cancelled the loop after the second tick", job.isCancelled)
    }
}
