package com.beakon.companion.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Non-sensitive persistent settings. Restores tracking on/off, instance URL and
 * username across process death. The API secret is NOT stored here — see
 * [SecureSecretStore].
 *
 * The primary constructor takes a [SharedPreferences] directly so this class can
 * be unit-tested on the JVM; production code uses the [Context] constructor.
 */
class AppPreferences(
    private val prefs: SharedPreferences,
) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
    )

    private val _startTrackingOnBoot = MutableStateFlow(readStartTrackingOnBoot())

    /**
     * Reactive view of [startTrackingOnBoot], kept in sync with the persisted
     * value by a preference-change listener. The UI collects this, so the toggle
     * can never drift from storage.
     */
    val startTrackingOnBootFlow: StateFlow<Boolean> = _startTrackingOnBoot.asStateFlow()

    private val bootStartListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_BOOT_START) {
                _startTrackingOnBoot.value = readStartTrackingOnBoot()
            }
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(bootStartListener)
    }

    var instanceUrl: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var trackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING, false)
        set(value) = prefs.edit().putBoolean(KEY_TRACKING, value).apply()

    /**
     * Whether to resume tracking automatically after a reboot. Default OFF: the
     * user is never silently opted in.
     */
    var startTrackingOnBoot: Boolean
        get() = readStartTrackingOnBoot()
        set(value) = prefs.edit().putBoolean(KEY_BOOT_START, value).apply()

    /** Reporting interval in seconds. Never below [MIN_INTERVAL_SECONDS]. */
    var intervalSeconds: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_SECONDS)
        set(value) = prefs.edit()
            .putInt(KEY_INTERVAL, value.coerceAtLeast(MIN_INTERVAL_SECONDS))
            .apply()

    /**
     * Only fixes at least this accurate (smaller metres is better) are pushed.
     * Defaults to [DEFAULT_ACCURACY_THRESHOLD_M] and is clamped to
     * [[MIN_ACCURACY_THRESHOLD_M], [MAX_ACCURACY_THRESHOLD_M]].
     */
    var accuracyThresholdM: Int
        get() = prefs.getInt(KEY_ACCURACY_THRESHOLD, DEFAULT_ACCURACY_THRESHOLD_M)
        set(value) = prefs.edit()
            .putInt(
                KEY_ACCURACY_THRESHOLD,
                value.coerceIn(MIN_ACCURACY_THRESHOLD_M, MAX_ACCURACY_THRESHOLD_M),
            )
            .apply()

    /**
     * The last tracking-impairing error, persisted so it survives process death
     * and is shown at app start even when its notification was hidden. Null when
     * the last send succeeded or no error has occurred.
     */
    var lastTrackingError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) = prefs.edit().putString(KEY_LAST_ERROR, value).apply()

    /** Removes credentials-related state on logout. Keeps the URL/username prefill. */
    fun clearSession() {
        prefs.edit()
            .putBoolean(KEY_TRACKING, false)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    private fun readStartTrackingOnBoot(): Boolean = prefs.getBoolean(KEY_BOOT_START, false)

    companion object {
        /**
         * The server allows 5 recordlocation requests per 300 s per IP. 90 s keeps
         * a comfortable margin; 60 s sits exactly on the ceiling. Do not lower.
         */
        const val DEFAULT_INTERVAL_SECONDS = 90
        const val MIN_INTERVAL_SECONDS = 60

        /**
         * Only fixes at least this accurate are pushed. 30 m is a common
         * "good enough" consumer-GPS fix; coarser fixes are held so the map keeps
         * showing the last good position.
         */
        const val DEFAULT_ACCURACY_THRESHOLD_M = 30

        /**
         * 5 m is stricter than consumer GPS realistically achieves, so it is the
         * strictest floor worth offering. 500 m is far beyond a usable fix and
         * caps an accidental or absurd value; the UI choices (10/20/30/50/100)
         * all sit inside the range.
         */
        const val MIN_ACCURACY_THRESHOLD_M = 5
        const val MAX_ACCURACY_THRESHOLD_M = 500

        private const val FILE_NAME = "beakon_prefs"
        private const val KEY_URL = "instance_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_TRACKING = "tracking_enabled"
        private const val KEY_INTERVAL = "interval_seconds"
        private const val KEY_ACCURACY_THRESHOLD = "accuracy_threshold_m"
        private const val KEY_BOOT_START = "start_tracking_on_boot"
        private const val KEY_LAST_ERROR = "last_tracking_error"
    }
}
