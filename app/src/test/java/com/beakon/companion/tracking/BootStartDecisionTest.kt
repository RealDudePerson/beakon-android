package com.beakon.companion.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure boot-start decision. The receiver is a thin wrapper
 * around this function and cannot run on the JVM, so the logic is verified here.
 */
class BootStartDecisionTest {

    @Test
    fun `setting on and configured starts tracking`() {
        assertTrue(
            BootStartDecision.shouldStartTrackingOnBoot(
                settingEnabled = true,
                serverUrl = "https://location2.navettas.com",
                hasCredentials = true,
            ),
        )
    }

    @Test
    fun `setting on but not configured does not start`() {
        // No server URL at all.
        assertFalse(
            BootStartDecision.shouldStartTrackingOnBoot(
                settingEnabled = true,
                serverUrl = null,
                hasCredentials = true,
            ),
        )
        // URL present but no credentials.
        assertFalse(
            BootStartDecision.shouldStartTrackingOnBoot(
                settingEnabled = true,
                serverUrl = "https://location2.navettas.com",
                hasCredentials = false,
            ),
        )
        // Neither.
        assertFalse(
            BootStartDecision.shouldStartTrackingOnBoot(
                settingEnabled = true,
                serverUrl = null,
                hasCredentials = false,
            ),
        )
    }

    @Test
    fun `setting off and configured does not start`() {
        assertFalse(
            BootStartDecision.shouldStartTrackingOnBoot(
                settingEnabled = false,
                serverUrl = "https://location2.navettas.com",
                hasCredentials = true,
            ),
        )
    }

    @Test
    fun `setting off and not configured does not start`() {
        assertFalse(
            BootStartDecision.shouldStartTrackingOnBoot(
                settingEnabled = false,
                serverUrl = null,
                hasCredentials = false,
            ),
        )
    }

    @Test
    fun `a blank server url counts as not configured`() {
        assertFalse(
            BootStartDecision.shouldStartTrackingOnBoot(
                settingEnabled = true,
                serverUrl = "   ",
                hasCredentials = true,
            ),
        )
    }
}
