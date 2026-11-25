package com.beakon.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The chosen person is stored per widget id (D1), so two widgets can pin two
 * different people and a removed widget forgets only its own choice.
 */
class WidgetPersonPreferencesTest {

    @Test
    fun `a fresh widget has no chosen person`() {
        val prefs = AppPreferences(FakeSharedPreferences())

        assertNull(prefs.widgetPerson(7))
    }

    @Test
    fun `each widget keeps its own chosen person`() {
        val prefs = AppPreferences(FakeSharedPreferences())

        prefs.setWidgetPerson(1, "alice")
        prefs.setWidgetPerson(2, "bob")

        assertEquals("alice", prefs.widgetPerson(1))
        assertEquals("bob", prefs.widgetPerson(2))
    }

    @Test
    fun `setting null clears the choice`() {
        val prefs = AppPreferences(FakeSharedPreferences())
        prefs.setWidgetPerson(1, "alice")

        prefs.setWidgetPerson(1, null)

        assertNull(prefs.widgetPerson(1))
    }

    @Test
    fun `removing a widget forgets only its choice`() {
        val prefs = AppPreferences(FakeSharedPreferences())
        prefs.setWidgetPerson(1, "alice")
        prefs.setWidgetPerson(2, "bob")

        prefs.removeWidgetPerson(1)

        assertNull(prefs.widgetPerson(1))
        assertEquals("bob", prefs.widgetPerson(2))
    }
}
