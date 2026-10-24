package com.beakon.companion.ui

/**
 * Logout must take the user to Setup, not strand them on Status (where the
 * signed-out screen has no useful action and the bottom bar has disappeared).
 *
 * Pure so the navigation rule can be unit-tested; the Compose NavHost performs
 * the actual navigation.
 */
internal object NavigationDecision {
    fun shouldNavigateToSetup(loggedIn: Boolean, currentRoute: String?): Boolean =
        !loggedIn && currentRoute != null && currentRoute != "setup"
}
