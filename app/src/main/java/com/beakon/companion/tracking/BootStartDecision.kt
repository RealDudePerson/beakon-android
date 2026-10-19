package com.beakon.companion.tracking

/**
 * The boot-start decision, deliberately kept free of Android types so it can be
 * unit-tested on the JVM. A [android.content.BroadcastReceiver] cannot be
 * exercised outside a device, so [BootReceiver] holds no logic beyond reading
 * storage and calling this.
 */
object BootStartDecision {

    /**
     * True only when the user opted in **and** the app is actually configured.
     * Starting a service that has no server URL or no credentials would just
     * produce a permanent "not configured" failure notification, so we do not.
     *
     * A blank (or whitespace-only) URL counts as not configured.
     */
    fun shouldStartTrackingOnBoot(
        settingEnabled: Boolean,
        serverUrl: String?,
        hasCredentials: Boolean,
    ): Boolean = settingEnabled && !serverUrl.isNullOrBlank() && hasCredentials
}
