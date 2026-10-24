package com.beakon.companion.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastKnownFixTest {

    private data class Fix(val id: String, val time: Long)

    private val now = 1_000_000L
    private val intervalSeconds = 90
    private val maxAge = LastKnownFix.maxAgeMs(intervalSeconds)

    @Test
    fun `the bound is twice the configured interval`() {
        assertEquals(180_000L, maxAge)
    }

    @Test
    fun `fresh last-known fixes are accepted and the newest wins`() {
        val fixes = listOf(
            Fix("older", now - 120_000L),
            Fix("newest", now - 10_000L),
            Fix("middle", now - 60_000L),
        )

        val selected = LastKnownFix.freshest(fixes, now, maxAge, { it.time })

        assertEquals("newest", selected?.id)
    }

    @Test
    fun `a last-known fix older than the bound is rejected`() {
        val fixes = listOf(
            Fix("stale", now - maxAge - 1L),
        )

        val selected = LastKnownFix.freshest(fixes, now, maxAge, { it.time })

        assertNull("a stale fix must not be pushed as fresh", selected)
    }

    @Test
    fun `no last-known fixes means no fix`() {
        val selected = LastKnownFix.freshest(emptyList<Fix>(), now, maxAge, { it.time })

        assertNull("with nothing fresh the caller must fall through to a live fix", selected)
    }

    @Test
    fun `a mix of fresh and stale keeps only the fresh one`() {
        val fixes = listOf(
            Fix("stale", now - 500_000L),
            Fix("fresh", now - 5_000L),
        )

        val selected = LastKnownFix.freshest(fixes, now, maxAge, { it.time })

        assertEquals("fresh", selected?.id)
    }
}
