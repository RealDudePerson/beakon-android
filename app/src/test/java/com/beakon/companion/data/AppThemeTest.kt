package com.beakon.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeTest {

    @Test
    fun `theme defaults to system`() {
        assertEquals(ThemeMode.SYSTEM, AppPreferences(FakeSharedPreferences()).themeMode)
    }

    @Test
    fun `setting a theme persists it and updates the flow`() {
        val prefs = AppPreferences(FakeSharedPreferences())
        prefs.themeMode = ThemeMode.DARK

        assertEquals(ThemeMode.DARK, prefs.themeMode)
        assertEquals(ThemeMode.DARK, prefs.themeModeFlow.value)
    }

    @Test
    fun `an unknown stored theme value falls back to system`() {
        val fake = FakeSharedPreferences()
        fake.edit().putString("theme_mode", "BOGUS").apply()

        assertEquals(ThemeMode.SYSTEM, AppPreferences(fake).themeMode)
    }
}
