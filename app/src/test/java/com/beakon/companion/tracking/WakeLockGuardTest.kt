package com.beakon.companion.tracking

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wake lock must be released on every tick, including a failed one. The
 * Android WakeLock itself is not available on the JVM, so this tests the
 * acquire/block/release lifecycle the service uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeLockGuardTest {

    @Test
    fun `the lock is held for the tick and released afterwards`() = runTest {
        val events = mutableListOf<String>()

        withWakeLock(
            acquire = {
                events += "acquire"
                "lock"
            },
            release = { events += "release:$it" },
            block = { events += "tick" },
        )

        assertEquals(listOf("acquire", "tick", "release:lock"), events)
    }

    @Test
    fun `the lock is released when the tick throws`() = runTest {
        val events = mutableListOf<String>()
        val lock = Any()

        val thrown = runCatching {
            withWakeLock(
                acquire = {
                    events += "acquire"
                    lock
                },
                release = { events += "release" },
                block = {
                    events += "tick"
                    throw IllegalStateException("POST failed")
                },
            )
        }.exceptionOrNull()

        assertEquals("POST failed", thrown?.message)
        assertEquals(listOf("acquire", "tick", "release"), events)
    }

    @Test
    fun `no lock means the tick still runs and no release is attempted`() = runTest {
        val events = mutableListOf<String>()

        withWakeLock(
            acquire = { events += "acquire"; null },
            release = { events += "release" },
            block = { events += "tick" },
        )

        assertEquals(listOf("acquire", "tick"), events)
    }
}
