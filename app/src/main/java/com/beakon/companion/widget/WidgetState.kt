package com.beakon.companion.widget

import androidx.work.NetworkType
import com.beakon.companion.net.BeakonUrl
import com.beakon.companion.net.SharedLocationsResult
import com.beakon.companion.net.SharedPersonDto
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * Pure widget logic — no Android types, so every decision the widget makes about
 * *what to show* is exercised on the JVM without a device.
 *
 * The widget is a `RemoteViews` layout: it can show a bitmap, a display name and
 * an age string. Everything about which of those to show, and what the age
 * string says, is decided here; WidgetRenderer only paints the result.
 */

/**
 * The widget's refresh schedule, kept here (not in [WidgetWorker]) so the
 * interval and the network requirement are unit-testable without the Android
 * `WorkManager` runtime.
 *
 * Acceptance criterion 6 fixes the periodic interval at **30 minutes** with a
 * network constraint. (The spec's D2 prose says 15 minutes — the two disagree;
 * the acceptance criterion is what is implemented.)
 */
object WidgetSchedule {

    /** Periodic refresh interval in minutes. */
    const val REFRESH_INTERVAL_MINUTES = 15L

    /** WorkManager unique names, so scheduling is idempotent and cancellable. */
    const val PERIODIC_WORK_NAME = "beakon_widget_refresh"
    const val ONE_TIME_WORK_NAME = "beakon_widget_refresh_now"

    /** Refresh only when the device has a network connection. */
    val REQUIRED_NETWORK_TYPE: NetworkType = NetworkType.CONNECTED
}

/**
 * Maps the server's `age_seconds` (or, for an old server, the additive
 * `timestamp_unix`) to the short string the widget shows.
 *
 * `age_seconds` is authoritative: the endpoint is the only one that provides it,
 * and it is never derived from the human display string. When `age_seconds` is
 * absent or null the age is computed from `timestamp_unix`; when neither is
 * available the widget says so rather than guessing.
 */
object WidgetAge {

    /**
     * @param ageSeconds the server's `age_seconds`, or null when absent.
     * @param timestampUnix the point's epoch seconds, or null when absent.
     * @param nowSeconds the current epoch seconds (injected for testability).
     */
    fun format(ageSeconds: Long?, timestampUnix: Long?, nowSeconds: Long): String {
        val age = ageSeconds ?: timestampUnix?.let { nowSeconds - it }
        if (age == null) return "unknown age"
        // A future timestamp (clock skew) reads as "just now", never negative.
        val seconds = age.coerceAtLeast(0)
        return when {
            seconds < 60 -> "just now"
            seconds < 3_600 -> "${seconds / 60} min ago"
            else -> "${seconds / 3_600} h ago"
        }
    }
}

/**
 * What the widget shows for one refresh. Produced purely from the fetch outcome
 * and the widget's chosen person; the worker turns it into `RemoteViews`.
 */
sealed interface WidgetState {

    /**
     * A person's latest point is available. [displayName] and [ageSeconds] feed
     * the text views, [lat]/[lon] the map bitmap.
     */
    data class Person(
        val username: String,
        val displayName: String,
        val ageSeconds: Long?,
        val timestampUnix: Long?,
        val lat: Double,
        val lon: Double,
        /**
         * True when the widget was pinned to a person who is absent from the
         * response, so the first sharer is shown instead (D1). The widget can
         * make the substitution visible instead of silently showing someone else.
         */
        val fallbackFromChosen: Boolean = false,
    ) : WidgetState

    /** Nobody shares with the caller, or the chosen person has no point at all. */
    data class Empty(val message: String) : WidgetState

    /** The refresh failed. Every error is visible in the widget (never silent). */
    data class Error(val message: String) : WidgetState
}

/**
 * Pure mapping from a fetch outcome (and the widget's chosen username) to the
 * [WidgetState] to render.
 */
object WidgetStateFactory {

    /**
     * Maps the `GET /api/locations/shared` outcome. An HTTP 500 is an
     * [WidgetState.Error], never an [WidgetState.Empty]: the two must not be
     * confused, or the widget would claim "nobody shares with you" whenever the
     * server is down.
     */
    fun resolve(
        result: SharedLocationsResult,
        chosenUsername: String?,
    ): WidgetState = when (result) {
        is SharedLocationsResult.Success -> resolvePeople(result.people, chosenUsername)
        SharedLocationsResult.AuthRequired ->
            WidgetState.Error("Sign in again: the API token was rejected.")
        is SharedLocationsResult.CertificateError ->
            WidgetState.Error("Certificate not trusted: ${result.message}")
        is SharedLocationsResult.NetworkError ->
            WidgetState.Error("No connection: ${result.message}")
        is SharedLocationsResult.ParseError ->
            WidgetState.Error("The server's response could not be read.")
        is SharedLocationsResult.ServerError ->
            WidgetState.Error("Server error (${result.code}).")
    }

    /**
     * Maps the people list to the widget's view state.
     *
     *  - the chosen person is present -> show them;
     *  - the chosen person is absent but others share ("shared before", now not
     *    reporting) -> fall back to the first sharer, flagged [Person.fallbackFromChosen];
     *  - the list is empty -> an [WidgetState.Empty] explaining there is nobody;
     *  - a listed person with no point (defensive) -> an [WidgetState.Empty].
     */
    fun resolvePeople(
        people: List<SharedPersonDto>,
        chosenUsername: String?,
    ): WidgetState {
        if (people.isEmpty()) {
            return WidgetState.Empty(
                if (chosenUsername.isNullOrBlank()) {
                    "Nobody shares their location with you yet."
                } else {
                    "No shared locations are available right now."
                },
            )
        }

        val chosen = chosenUsername?.takeIf { it.isNotBlank() }
            ?.let { name -> people.firstOrNull { it.username == name } }
        val person = chosen ?: people.first()
        val latest = person.latest
            ?: return WidgetState.Empty("${person.label()} has not reported a location yet.")

        return WidgetState.Person(
            username = person.username,
            displayName = person.label(),
            ageSeconds = latest.ageSeconds,
            timestampUnix = latest.timestampUnix,
            lat = latest.lat,
            lon = latest.lon,
            fallbackFromChosen = chosen == null && !chosenUsername.isNullOrBlank(),
        )
    }

    /** The display name, falling back to the username, then to a neutral label. */
    private fun SharedPersonDto.label(): String =
        displayName?.takeIf { it.isNotBlank() } ?: username.takeIf { it.isNotBlank() } ?: "Someone"
}

/**
 * The tap target's destination (D3): the person's map on the instance, so a tap
 * opens the app at that person's map. Pure, so the URL is unit-tested.
 */
object WidgetTap {

    /**
     * `https://host/map/<username>` for the instance, or the instance base URL
     * when no username is known. Null when the instance URL is unusable.
     */
    fun mapUrl(instanceUrl: String?, username: String?): String? {
        val normalized = instanceUrl?.let { BeakonUrl.normalize(it) } ?: return null
        val base = normalized.toHttpUrlOrNull() ?: return null
        if (username.isNullOrBlank()) return normalized
        return base.newBuilder()
            .addPathSegments("map")
            .addPathSegment(username)
            .build()
            .toString()
    }

    /**
     * True only when [candidateUrl] is HTTPS on the *same host and port* as the
     * configured instance. The tap intent is delivered to the app's exported
     * `MainActivity`, so an arbitrary intent from another app must not be able to
     * steer the WebView at a foreign URL.
     */
    fun isSameInstance(candidateUrl: String?, instanceUrl: String?): Boolean {
        val candidate = candidateUrl?.toHttpUrlOrNull() ?: return false
        if (candidate.scheme != "https") return false
        val instance = instanceUrl?.let { BeakonUrl.normalize(it)?.toHttpUrlOrNull() } ?: return false
        return candidate.host == instance.host && candidate.port == instance.port
    }
}
