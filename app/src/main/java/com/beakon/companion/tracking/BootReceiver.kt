package com.beakon.companion.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.beakon.companion.BeakonApplication

/**
 * Resumes location tracking after a reboot, but only when the user turned on
 * "Start tracking at boot" **and** the app is configured (server URL and
 * credentials). Otherwise it does nothing at all — no service, no notification.
 *
 * The decision itself lives in [BootStartDecision] so it can be unit-tested;
 * this receiver only reads storage, calls it, and (on the rare failure path)
 * surfaces an actionable error. It never fails silently.
 *
 * Android 15 (API 35) permits `location` foreground services to start from
 * `BOOT_COMPLETED`. The restricted types are dataSync, camera, mediaPlayback,
 * phoneCall, mediaProjection and microphone - `location` is not among them:
 * https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed
 * We do not declare `directBootAware`/`LOCKED_BOOT_COMPLETED`, because
 * credential-encrypted storage is unavailable at locked boot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val container = (context.applicationContext as BeakonApplication).container
        val shouldStart = BootStartDecision.shouldStartTrackingOnBoot(
            settingEnabled = container.preferences.startTrackingOnBoot,
            serverUrl = container.preferences.instanceUrl,
            hasCredentials = container.preferences.username != null &&
                container.secrets.secret() != null,
        )
        if (!shouldStart) return

        try {
            LocationTrackingService.start(context)
        } catch (error: RuntimeException) {
            // The service could not even be requested (e.g. a background-start
            // restriction). Tell the user instead of leaving them with nothing.
            val message = "Tracking did not resume after the reboot: " +
                "${error.message ?: "unknown error"}. Open Beakon to start it."
            // Persist it too: the notification is dropped when POST_NOTIFICATIONS
            // is denied, so the Status screen must still explain it at app start.
            container.trackingState.recordTickError(message)
            NotificationHelper.showError(context, message)
        }
    }
}
