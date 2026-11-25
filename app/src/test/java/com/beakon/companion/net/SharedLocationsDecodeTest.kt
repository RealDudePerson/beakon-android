package com.beakon.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /api/locations/shared` is the widget's only data source, and `age_seconds`
 * exists only there. These tests pin the wire shape (including the additive
 * `timestamp_unix` fallback) without a live server.
 */
class SharedLocationsDecodeTest {

    private val client = BeakonClient("https://example.com", pinnedSha256 = null)

    @Test
    fun `the shared body decodes with age_seconds and display name`() {
        val result = client.parseSharedLocations(BODY)

        assertTrue("expected success, was $result", result is SharedLocationsResult.Success)
        val people = (result as SharedLocationsResult.Success).people
        assertEquals(1, people.size)
        val alice = people.single()
        assertEquals("alice", alice.username)
        assertEquals("Alice", alice.displayName)
        assertEquals(40.7128, alice.latest!!.lat, 1e-9)
        assertEquals(-74.006, alice.latest!!.lon, 1e-9)
        assertEquals(30L, alice.latest!!.ageSeconds)
        assertEquals(1_800_000_000L, alice.latest!!.timestampUnix)
        assertEquals(1, alice.trail.size)
    }

    @Test
    fun `an old server body without age_seconds still decodes`() {
        val body =
            """{"people":[{"username":"alice","display_name":"Alice","latest":{"lat":1.0,"lon":2.0,"timestamp":"5 min ago","timestamp_unix":1799999700}}]}"""

        val result = client.parseSharedLocations(body) as SharedLocationsResult.Success

        assertNull("age_seconds is absent on an old server", result.people.single().latest!!.ageSeconds)
        assertEquals(1_799_999_700L, result.people.single().latest!!.timestampUnix)
    }

    @Test
    fun `the documented failure body is not a parse error`() {
        // The server returns this with HTTP 500. Decoding it is fine; the client
        // maps the status to ServerError before it ever parses the body.
        val result = client.parseSharedLocations("""{"people":[],"error":"boom"}""")

        assertTrue("expected success, was $result", result is SharedLocationsResult.Success)
        assertTrue((result as SharedLocationsResult.Success).people.isEmpty())
    }

    @Test
    fun `a genuinely malformed body is a ParseError, not a NetworkError`() {
        val result = client.parseSharedLocations("{\"people\": [{\"username\": }")

        assertTrue("expected a ParseError, was $result", result is SharedLocationsResult.ParseError)
        assertTrue(result !is SharedLocationsResult.NetworkError)
    }

    private companion object {
        val BODY =
            """{"people":[{"username":"alice","display_name":"Alice","latest":{"lat":40.7128,"lon":-74.006,"timestamp":"just now","timestamp_unix":1800000000,"age_seconds":30,"batt":77,"ischarging":false},"trail":[{"lat":40.7,"lon":-74.0,"timestamp":"1 min ago","timestamp_unix":1799999970}]}]}"""
    }
}
