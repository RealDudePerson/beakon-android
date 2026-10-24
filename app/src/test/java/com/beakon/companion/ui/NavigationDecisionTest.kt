package com.beakon.companion.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-5: logging out navigates to Setup instead of stranding the user. */
class NavigationDecisionTest {

    @Test
    fun `logging out from Status navigates to Setup`() {
        assertTrue(NavigationDecision.shouldNavigateToSetup(loggedIn = false, currentRoute = "status"))
    }

    @Test
    fun `logging out from Map or History navigates to Setup`() {
        assertTrue(NavigationDecision.shouldNavigateToSetup(loggedIn = false, currentRoute = "map"))
        assertTrue(NavigationDecision.shouldNavigateToSetup(loggedIn = false, currentRoute = "history"))
    }

    @Test
    fun `already on Setup does not navigate again`() {
        assertFalse(NavigationDecision.shouldNavigateToSetup(loggedIn = false, currentRoute = "setup"))
    }

    @Test
    fun `a signed-in user is left where they are`() {
        assertFalse(NavigationDecision.shouldNavigateToSetup(loggedIn = true, currentRoute = "status"))
    }

    @Test
    fun `before the first back-stack entry there is nothing to navigate`() {
        assertFalse(NavigationDecision.shouldNavigateToSetup(loggedIn = false, currentRoute = null))
    }
}
