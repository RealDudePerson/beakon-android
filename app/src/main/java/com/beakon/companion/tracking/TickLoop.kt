package com.beakon.companion.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The tracking tick loop, kept Android-free so its failure behaviour is
 * unit-tested.
 *
 * A failure in [tick] is handed to [onError] and the loop continues on the same
 * cadence. Only coroutine cancellation escapes: an unexpected exception must
 * never take down the process or end tracking silently.
 */
object TickLoop {

    suspend fun run(
        intervalMs: Long,
        tick: suspend () -> Unit,
        onError: suspend (Throwable) -> Unit,
        delayFor: suspend (Long) -> Unit = { delay(it) },
    ) {
        while (currentCoroutineContext().isActive) {
            try {
                tick()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // A reporting failure must not kill the coroutine. Guard the
                // handler too, so an error while recording/notifying cannot
                // escape either.
                try {
                    onError(error)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // Nothing more we can do here without failing silently or
                    // killing the loop; the tick itself is still retried.
                }
            }
            delayFor(intervalMs)
        }
    }
}
