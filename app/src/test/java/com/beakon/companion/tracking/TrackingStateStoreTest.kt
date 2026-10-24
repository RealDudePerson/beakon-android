package com.beakon.companion.tracking

import com.beakon.companion.net.SendResult
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accuracy hold must be visible in tracking state, so a stale map is always
 * explained on the Status screen — never fail silently. P2-12 adds the
 * `recordResult` transitions and the day rollover; P2-7 adds error persistence.
 */
class TrackingStateStoreTest {

    @Test
    fun `a held fix is recorded with its numbers`() {
        val store = TrackingStateStore()

        store.recordAccuracyHold(accuracyM = 42, thresholdM = 30)

        val status = store.status.value
        assertEquals(42, status.lastHeldAccuracyM)
        assertEquals(30, status.lastHeldThresholdM)
    }

    @Test
    fun `a pushed fix clears a previous hold`() {
        val store = TrackingStateStore()
        store.recordAccuracyHold(accuracyM = 42, thresholdM = 30)

        store.clearAccuracyHold()

        val status = store.status.value
        assertNull(status.lastHeldAccuracyM)
        assertNull(status.lastHeldThresholdM)
    }

    // --- P2-12: recordResult transitions ------------------------------------

    @Test
    fun `a successful send records the time, increments today and clears the error`() {
        val now = 1_000L
        val store = TrackingStateStore(clock = { now })
        store.recordTickError("previous failure")

        store.recordResult(SendResult.Success, now)

        val status = store.status.value
        assertEquals(now, status.lastSuccessfulSendAt)
        assertEquals(1, status.pointsSentToday)
        assertTrue(status.lastSendSucceeded)
        assertNull("a success must clear the previous error", status.lastError)
    }

    @Test
    fun `a failed send records its message and does not increment today`() {
        val store = TrackingStateStore(clock = { 1_000L })

        store.recordResult(SendResult.NetworkError("timeout"), 1_000L)

        val status = store.status.value
        assertNotNull("a failure must surface a message", status.lastError)
        assertTrue(status.lastError!!.contains("Cannot reach"))
        assertFalse(status.lastSendSucceeded)
        assertEquals(0, status.pointsSentToday)
        assertNull(status.lastSuccessfulSendAt)
    }

    @Test
    fun `a success after a failure clears the error again`() {
        val store = TrackingStateStore(clock = { 1_000L })
        store.recordResult(SendResult.ServerError(500), 1_000L)
        assertNotNull(store.status.value.lastError)

        store.recordResult(SendResult.Success, 2_000L)

        assertNull(store.status.value.lastError)
        assertTrue(store.status.value.lastSendSucceeded)
        assertEquals(1, store.status.value.pointsSentToday)
    }

    @Test
    fun `the daily point count resets on a new day`() {
        var now = atNoon(2026, 10, 22)
        val store = TrackingStateStore(clock = { now })
        store.recordResult(SendResult.Success, now)
        store.recordResult(SendResult.Success, now)
        assertEquals(2, store.status.value.pointsSentToday)

        now = atNoon(2026, 10, 23)
        store.recordResult(SendResult.Success, now)

        assertEquals("a new day starts the count over", 1, store.status.value.pointsSentToday)
    }

    // --- P2-7: error persistence -------------------------------------------

    @Test
    fun `an error restored at construction is visible at app start`() {
        val store = TrackingStateStore(initialError = "Tracking did not resume after the reboot")

        assertEquals("Tracking did not resume after the reboot", store.status.value.lastError)
    }

    @Test
    fun `a tick error is persisted so it survives process death`() {
        val persisted = mutableListOf<String?>()
        val store = TrackingStateStore(persistError = { persisted += it })

        store.recordTickError("Tracking tick failed: boom")

        assertEquals(listOf("Tracking tick failed: boom"), persisted)
        assertEquals("Tracking tick failed: boom", store.status.value.lastError)
    }

    @Test
    fun `a failed send is persisted and a success clears the persisted value`() {
        val persisted = mutableListOf<String?>()
        val store = TrackingStateStore(clock = { 1_000L }, persistError = { persisted += it })

        store.recordResult(SendResult.NetworkError("timeout"), 1_000L)
        assertNotNull("the failure must be persisted", persisted.last())
        assertTrue(persisted.last()!!.contains("Cannot reach"))

        store.recordResult(SendResult.Success, 2_000L)
        assertNull("a success must clear the persisted error", persisted.last())
    }

    @Test
    fun `clearing the error also clears it from storage`() {
        val persisted = mutableListOf<String?>()
        val store = TrackingStateStore(persistError = { persisted += it })
        store.recordTickError("boom")

        store.clearError()

        assertNull(store.status.value.lastError)
        assertNull(persisted.last())
    }

    private fun atNoon(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day)
            .atTime(12, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
