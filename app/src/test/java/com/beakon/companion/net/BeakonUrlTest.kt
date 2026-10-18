package com.beakon.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeakonUrlTest {

    @Test
    fun `adds https when no scheme is given`() {
        assertEquals("https://location.example.com", BeakonUrl.normalize("location.example.com"))
    }

    @Test
    fun `keeps an explicit https scheme and strips a trailing slash`() {
        assertEquals(
            "https://location.example.com",
            BeakonUrl.normalize("https://location.example.com/"),
        )
    }

    @Test
    fun `rejects plain http`() {
        assertNull(BeakonUrl.normalize("http://location.example.com"))
    }

    @Test
    fun `rejects blank input`() {
        assertNull(BeakonUrl.normalize("   "))
    }

    @Test
    fun `extracts host and port`() {
        assertEquals("location.example.com", BeakonUrl.host("https://location.example.com:8443"))
        assertEquals(8443, BeakonUrl.port("https://location.example.com:8443"))
        assertEquals(443, BeakonUrl.port("https://location.example.com"))
    }
}
