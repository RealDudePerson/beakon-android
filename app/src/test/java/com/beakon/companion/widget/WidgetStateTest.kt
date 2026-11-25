package com.beakon.companion.widget

import com.beakon.companion.net.SharedLocationDto
import com.beakon.companion.net.SharedLocationsResult
import com.beakon.companion.net.SharedPersonDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance criterion 4: a pure function maps `(people, chosenUsername)` to the
 * widget's view state, covering person present, person absent but shared-before,
 * an empty list, and HTTP 500. Criterion 5: a null `age_seconds` is carried
 * through so the age formatter can fall back.
 */
class WidgetStateTest {

    private val now = 1_800_000_000L

    @Test
    fun `the chosen person is shown when present`() {
        val people = listOf(person("alice", "Alice", ageSeconds = 30), person("bob", "Bob", ageSeconds = 10))

        val state = WidgetStateFactory.resolvePeople(people, "bob")

        assertTrue("expected Bob, was $state", state is WidgetState.Person)
        state as WidgetState.Person
        assertEquals("bob", state.username)
        assertEquals("Bob", state.displayName)
        assertEquals(30L, people[0].latest!!.ageSeconds)
        assertEquals(10L, state.ageSeconds)
        assertFalse(state.fallbackFromChosen)
    }

    @Test
    fun `a chosen person absent from a non-empty list falls back to the first sharer`() {
        // Carol shared before (that is why she was chosen) but no longer reports;
        // Alice is still sharing, so the widget falls back to Alice and flags it.
        val people = listOf(person("alice", "Alice", ageSeconds = 120))

        val state = WidgetStateFactory.resolvePeople(people, "carol")

        assertTrue("expected a person state, was $state", state is WidgetState.Person)
        state as WidgetState.Person
        assertEquals("alice", state.username)
        assertTrue("the substitution must be visible", state.fallbackFromChosen)
    }

    @Test
    fun `an empty list is an empty state, not an error`() {
        val state = WidgetStateFactory.resolvePeople(emptyList(), "alice")

        assertTrue("expected an empty state, was $state", state is WidgetState.Empty)
        assertTrue((state as WidgetState.Empty).message.isNotBlank())
    }

    @Test
    fun `HTTP 500 is an error state, never nobody-shares`() {
        val state = WidgetStateFactory.resolve(SharedLocationsResult.ServerError(500), "alice")

        assertTrue("expected an error state, was $state", state is WidgetState.Error)
        assertFalse(
            "a server error must not read as nobody sharing",
            state is WidgetState.Empty,
        )
        assertTrue((state as WidgetState.Error).message.contains("500"))
    }

    @Test
    fun `a successful but empty response is an empty state`() {
        val state = WidgetStateFactory.resolve(
            SharedLocationsResult.Success(emptyList()),
            "alice",
        )

        assertTrue("expected an empty state, was $state", state is WidgetState.Empty)
    }

    @Test
    fun `no chosen person shows the first sharer without a fallback flag`() {
        val people = listOf(person("alice", "Alice", ageSeconds = 5), person("bob", "Bob", ageSeconds = 5))

        val state = WidgetStateFactory.resolvePeople(people, null)

        state as WidgetState.Person
        assertEquals("alice", state.username)
        assertFalse(state.fallbackFromChosen)
    }

    @Test
    fun `a null age_seconds is carried through for the fallback`() {
        val people = listOf(
            SharedPersonDto(
                username = "alice",
                displayName = "Alice",
                latest = SharedLocationDto(lat = 1.0, lon = 2.0, timestampUnix = now - 300),
            ),
        )

        val state = WidgetStateFactory.resolvePeople(people, "alice") as WidgetState.Person

        assertNull("age_seconds must stay null so the formatter falls back", state.ageSeconds)
        assertEquals(now - 300, state.timestampUnix)
        assertEquals("5 min ago", WidgetAge.format(state.ageSeconds, state.timestampUnix, now))
    }

    @Test
    fun `a blank display name falls back to the username`() {
        val people = listOf(person("alice", "  ", ageSeconds = 5))

        val state = WidgetStateFactory.resolvePeople(people, "alice") as WidgetState.Person

        assertEquals("alice", state.displayName)
    }

    @Test
    fun `each non-success outcome produces a visible error`() {
        val outcomes = listOf(
            SharedLocationsResult.AuthRequired,
            SharedLocationsResult.CertificateError("bad cert"),
            SharedLocationsResult.NetworkError("timeout"),
            SharedLocationsResult.ParseError("bad json"),
            SharedLocationsResult.ServerError(503),
        )
        outcomes.forEach { outcome ->
            val state = WidgetStateFactory.resolve(outcome, "alice")
            assertTrue("$outcome must be an error state, was $state", state is WidgetState.Error)
            assertTrue((state as WidgetState.Error).message.isNotBlank())
        }
    }

    private fun person(username: String, displayName: String, ageSeconds: Long): SharedPersonDto =
        SharedPersonDto(
            username = username,
            displayName = displayName,
            latest = SharedLocationDto(
                lat = 40.7128,
                lon = -74.006,
                ageSeconds = ageSeconds,
                timestampUnix = now - ageSeconds,
            ),
        )
}
