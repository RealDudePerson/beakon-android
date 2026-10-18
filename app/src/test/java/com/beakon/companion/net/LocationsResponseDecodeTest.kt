package com.beakon.companion.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the history/map JSON parse failure.
 *
 * The live Beakon server sends `timestamp` as a human display string (e.g.
 * `"18 hours ago"`), never an epoch number. Declaring it as `Long?` in
 * [LocationDto] made kotlinx.serialization throw on every real response, and the
 * decode failure was then reported to the user as "no network / timeout".
 */
class LocationsResponseDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the real server body decodes with string timestamps`() {
        // Exactly what the live instance returned: 197 bytes including the
        // trailing newline Flask appends to its JSON responses.
        assertEquals(
            "the fixture must match the live response byte-for-byte",
            197,
            REAL_RESPONSE_BODY.toByteArray(Charsets.UTF_8).size,
        )

        val parsed = json.decodeFromString(LocationsResponse.serializer(), REAL_RESPONSE_BODY)

        assertEquals("18 hours ago", parsed.latest?.timestamp)
        assertEquals("18 hours ago", parsed.locations.single().timestamp)
        assertEquals(40.7128, parsed.latest!!.lat, 1e-9)
        assertEquals(-74.006, parsed.latest!!.lon, 1e-9)
        assertEquals(77, parsed.latest!!.batt)
        assertEquals(false, parsed.latest!!.isCharging)
    }

    @Test
    fun `a genuinely malformed body is a ParseError, not a NetworkError`() {
        val client = BeakonClient("https://example.com", pinnedSha256 = null)

        val result = client.parseLocations("{\"latest\": {\"lat\": }")

        assertTrue("expected a ParseError, was $result", result is LocationsResult.ParseError)
        assertFalse(
            "a malformed response must never be reported as a network error",
            result is LocationsResult.NetworkError,
        )
        val message = (result as LocationsResult.ParseError).message
        assertTrue("the underlying decode detail must be kept, was: $message", message.isNotBlank())
    }

    @Test
    fun `a parse failure message does not claim a network problem`() {
        val message = LocationsResult.ParseError("Unexpected JSON token").userMessage()

        assertTrue("a parse failure must surface a message", message != null)
        assertFalse(
            "a parse failure must not read as a network problem, was: $message",
            message!!.contains("no network", ignoreCase = true),
        )
        assertTrue(
            "the message must say the response could not be read, was: $message",
            message.contains("could not be read", ignoreCase = true),
        )
    }

    private companion object {
        val REAL_RESPONSE_BODY =
            """{"latest":{"batt":77,"ischarging":false,"lat":40.7128,"lon":-74.006,"timestamp":"18 hours ago"},"locations":[{"batt":77,"ischarging":false,"lat":40.7128,"lon":-74.006,"timestamp":"18 hours ago"}]}
"""
    }
}
