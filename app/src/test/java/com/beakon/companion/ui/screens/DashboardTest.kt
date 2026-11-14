package com.beakon.companion.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Map tab loads the Beakon web dashboard at the instance base URL, and
 * an SSL error there may only be bypassed for a certificate the user
 * explicitly pinned for that host.
 */
class DashboardTest {

    // --- dashboard URL ------------------------------------------------------

    @Test
    fun `a bare host becomes the https dashboard root`() {
        assertEquals(
            "https://location2.navettas.com/",
            Dashboard.url("location2.navettas.com"),
        )
    }

    @Test
    fun `a full instance URL with a port keeps it and drops any path`() {
        assertEquals(
            "https://example.com:8443/",
            Dashboard.url("https://example.com:8443/some/path"),
        )
    }

    @Test
    fun `a trailing slash is not doubled`() {
        assertEquals("https://example.com/", Dashboard.url("https://example.com/"))
    }

    @Test
    fun `plain http is rejected, like everywhere else in the app`() {
        assertNull(Dashboard.url("http://example.com"))
    }

    @Test
    fun `no instance URL means no dashboard URL`() {
        assertNull(Dashboard.url(null))
        assertNull(Dashboard.url("  "))
    }

    // --- SSL bypass: only an exact pinned fingerprint -----------------------

    private val pinned = "AA:BB:CC:DD"

    @Test
    fun `the pinned certificate may proceed`() {
        assertTrue(Dashboard.shouldProceedOnSslError("AA:BB:CC:DD", pinned))
    }

    @Test
    fun `fingerprint formatting differences do not matter`() {
        assertTrue(Dashboard.shouldProceedOnSslError("aabbccdd", pinned))
    }

    @Test
    fun `a different certificate must not proceed even when a pin exists`() {
        assertFalse(Dashboard.shouldProceedOnSslError("11:22:33:44", pinned))
    }

    @Test
    fun `no pin means no bypass`() {
        assertFalse(Dashboard.shouldProceedOnSslError("AA:BB:CC:DD", null))
    }

    @Test
    fun `no presented certificate means no bypass`() {
        assertFalse(Dashboard.shouldProceedOnSslError(null, pinned))
    }
}
