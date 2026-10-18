package com.beakon.companion.data

import android.content.Context

/**
 * Non-sensitive persistent settings. Restores tracking on/off, instance URL and
 * username across process death. The API secret is NOT stored here — see
 * [SecureSecretStore].
 */
class AppPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("beakon_prefs", Context.MODE_PRIVATE)

    var instanceUrl: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var trackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING, false)
        set(value) = prefs.edit().putBoolean(KEY_TRACKING, value).apply()

    /** Reporting interval in seconds. Never below [MIN_INTERVAL_SECONDS]. */
    var intervalSeconds: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_SECONDS)
        set(value) = prefs.edit()
            .putInt(KEY_INTERVAL, value.coerceAtLeast(MIN_INTERVAL_SECONDS))
            .apply()

    /** Removes credentials-related state on logout. Keeps the URL/username prefill. */
    fun clearSession() {
        prefs.edit().putBoolean(KEY_TRACKING, false).apply()
    }

    companion object {
        /**
         * The server allows 5 recordlocation requests per 300 s per IP. 90 s keeps
         * a comfortable margin; 60 s sits exactly on the ceiling. Do not lower.
         */
        const val DEFAULT_INTERVAL_SECONDS = 90
        const val MIN_INTERVAL_SECONDS = 60

        private const val KEY_URL = "instance_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_TRACKING = "tracking_enabled"
        private const val KEY_INTERVAL = "interval_seconds"
    }
}
