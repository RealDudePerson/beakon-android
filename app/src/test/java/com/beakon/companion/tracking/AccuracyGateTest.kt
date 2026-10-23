package com.beakon.companion.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure accuracy gate. The service is a thin caller; all of
 * the push/hold logic lives here so it can be verified without Android.
 */
class AccuracyGateTest {

    @Test
    fun `a fix exactly at the threshold is pushed`() {
        // `<=` semantics: a fix whose accuracy equals the threshold is good
        // enough, not held.
        assertTrue(AccuracyGate.shouldPush(30.0, 30))
        assertTrue(AccuracyGate.shouldPush(10.0, 10))
        assertTrue(AccuracyGate.shouldPush(500.0, 500))
    }

    @Test
    fun `a fix better than the threshold is pushed`() {
        assertTrue(AccuracyGate.shouldPush(8.0, 30))
        assertTrue(AccuracyGate.shouldPush(29.999, 30))
        assertTrue(AccuracyGate.shouldPush(0.0, 30))
    }

    @Test
    fun `a fix worse than the threshold is held`() {
        assertFalse(AccuracyGate.shouldPush(42.0, 30))
        assertFalse(AccuracyGate.shouldPush(30.0001, 30))
        assertFalse(AccuracyGate.shouldPush(100.0, 30))
    }

    /**
     * Red-capability: if the gate ever returned false for a null accuracy, this
     * test fails. The app cannot judge an unjudgeable fix, and dropping it would
     * silently remove the user from the map entirely.
     */
    @Test
    fun `a fix that reports no accuracy is pushed`() {
        assertTrue(AccuracyGate.shouldPush(null, 30))
        assertTrue(AccuracyGate.shouldPush(null, 10))
        assertTrue(AccuracyGate.shouldPush(null, 500))
    }

    @Test
    fun `the held message carries the actual numbers`() {
        assertEquals(
            "Held: fix accurate to 42 m, threshold 30 m",
            AccuracyGate.holdMessage(42.0, 30),
        )
        assertEquals(
            "Held: fix accurate to 31 m, threshold 30 m",
            AccuracyGate.holdMessage(30.6, 30),
        )
        assertEquals(
            "Held: fix accurate to 101 m, threshold 100 m",
            AccuracyGate.holdMessage(100.5, 100),
        )
    }
}
