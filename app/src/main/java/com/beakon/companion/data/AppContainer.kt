package com.beakon.companion.data

import android.content.Context
import com.beakon.companion.tracking.TrackingStateStore

/** Process-wide singletons shared by the UI and the tracking service. */
class AppContainer(context: Context) {
    val preferences = AppPreferences(context)
    val fingerprints = FingerprintStore(context)
    val secrets = SecureSecretStore(context)
    val trackingState = TrackingStateStore()
}
