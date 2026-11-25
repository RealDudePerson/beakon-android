package com.beakon.companion.data

import android.content.Context
import com.beakon.companion.net.BeakonClient
import com.beakon.companion.net.BeakonUrl
import com.beakon.companion.tracking.TrackingStateStore

/** Process-wide singletons shared by the UI and the tracking service. */
class AppContainer(context: Context) {
    val preferences = AppPreferences(context)
    val placeNames = PlaceNameStore(context)
    val fingerprints = FingerprintStore(context)
    val secrets = SecureSecretStore(context)
    val trackingState = TrackingStateStore(
        initialError = preferences.lastTrackingError,
        persistError = { message -> preferences.lastTrackingError = message },
    )

    /**
     * The app's single HTTP/TLS client for the configured instance, or null when
     * the app is not set up. Platform trust by default, with the stored
     * fingerprint only as the explicit fallback — the widget reuses this exact
     * path rather than growing a second client to audit.
     */
    fun beaconClient(): BeakonClient? {
        val url = preferences.instanceUrl ?: return null
        val host = BeakonUrl.host(url) ?: return null
        return BeakonClient(url, fingerprints.pinFor(host), secrets.session())
    }
}
