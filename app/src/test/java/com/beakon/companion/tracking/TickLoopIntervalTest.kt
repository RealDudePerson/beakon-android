package com.beakon.companion.tracking

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reporting interval is read through a provider on every iteration, so
 * a change in Settings takes effect from the next tick without toggling
 * tracking off and on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TickLoopIntervalTest {

    @Test
    fun `interval provider is re-read after each tick`() = runTest {
        var intervalMs = 90_000L
        val delays = mutableListOf<Long>()
        var ticks = 0

        val job = launch {
            TickLoop.run(
                intervalMsProvider = { intervalMs },
                tick = {
                    ticks++
                    // Simulate the user changing the interval in Settings
                    // during the first tick: the next delay must use it.
                    if (ticks == 1) intervalMs = 300_000L
                    // Cancel from inside the third tick: a cancellation
                    // inside a tick escapes before the following delay.
                    if (ticks == 3) currentCoroutineContext().cancel()
                },
                onError = {},
                // Like the real delay(), a cancelled coroutine must not
                // get a further delay: throw before recording it.
                delayFor = {
                    currentCoroutineContext().ensureActive()
                    delays.add(it)
                },
            )
        }
        job.join()

        assertEquals(3, ticks)
        assertEquals(
            "the delay after the first tick must use the interval in force then",
            listOf(300_000L, 300_000L),
            delays,
        )
    }
}
