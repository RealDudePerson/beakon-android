package com.beakon.companion.net

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-10: expiry eviction and seeding decide whether a persisted session survives
 * process death, so they are covered directly rather than only through the mock
 * login flow.
 */
class SimpleCookieJarTest {

    private val url = "https://location2.navettas.com/".toHttpUrl()

    @Test
    fun `an expired cookie is evicted from requests`() {
        val jar = SimpleCookieJar()
        jar.saveFromResponse(url, listOf(cookie("session", "old", expiresAt = NOW - 1_000)))

        assertEquals(emptyList<Cookie>(), jar.loadForRequest(url))
        assertNull("an expired session must not be returned", jar.cookie("session"))
    }

    @Test
    fun `a live cookie is returned and not evicted`() {
        val jar = SimpleCookieJar()
        jar.saveFromResponse(url, listOf(cookie("session", "live", expiresAt = NOW + 60_000)))

        assertEquals(listOf("live"), jar.loadForRequest(url).map { it.value })
        assertEquals("live", jar.cookie("session")?.value)
    }

    @Test
    fun `a saved cookie of the same name replaces the previous value`() {
        val jar = SimpleCookieJar()
        jar.saveFromResponse(url, listOf(cookie("session", "first", expiresAt = NOW + 60_000)))
        jar.saveFromResponse(url, listOf(cookie("session", "second", expiresAt = NOW + 60_000)))

        assertEquals(listOf("second"), jar.loadForRequest(url).map { it.value })
    }

    @Test
    fun `seeding restores a persisted session without a server round-trip`() {
        val jar = SimpleCookieJar()

        jar.seed(url.host, "session", "restored")

        assertEquals("restored", jar.cookie("session")?.value)
        assertEquals(listOf("restored"), jar.loadForRequest(url).map { it.value })
    }

    @Test
    fun `seeding again replaces the session rather than duplicating it`() {
        val jar = SimpleCookieJar()
        jar.seed(url.host, "session", "old")
        jar.seed(url.host, "session", "new")

        assertEquals(1, jar.loadForRequest(url).count { it.name == "session" })
        assertEquals("new", jar.cookie("session")?.value)
    }

    @Test
    fun `a seeded session is host-only and is not sent to another host`() {
        val jar = SimpleCookieJar()
        jar.seed("location2.navettas.com", "session", "restored")

        val otherHost = "https://example.com/".toHttpUrl()
        assertTrue(jar.loadForRequest(otherHost).isEmpty())
    }

    private fun cookie(name: String, value: String, expiresAt: Long): Cookie =
        Cookie.Builder()
            .name(name)
            .value(value)
            .domain(url.host)
            .path("/")
            .expiresAt(expiresAt)
            .build()

    private companion object {
        val NOW = System.currentTimeMillis()
    }
}
