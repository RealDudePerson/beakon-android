package com.beakon.companion.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for `POST /api/recordlocation`.
 *
 * `batt` and `ischarging` are nullable with a null default and the encoder is
 * configured with `encodeDefaults = false`, so an absent battery reading is
 * *omitted* from the JSON rather than sent as `null`.
 */
@Serializable
data class LocationReport(
    val lat: Double,
    val lon: Double,
    val acc: Double? = null,
    val batt: Int? = null,
    @SerialName("ischarging") val isCharging: Boolean? = null,
)

@Serializable
data class LocationDto(
    val lat: Double,
    val lon: Double,
    /**
     * The server sends a human-readable display string, never an epoch number:
     * `"just now"`, `"N minute(s) ago"`, `"N hour(s) ago"`, or `str(ts)` when
     * older than a day (and `"HH:MM:SS"` with `?date=`). Show it verbatim; do
     * not try to re-derive a time from it.
     */
    val timestamp: String? = null,
    /**
     * Additive integer Unix epoch seconds (UTC), present on a new server and
     * absent on an old one. Optional with a null default, so a response without
     * it still decodes; where a real age is wanted, compute it from this when
     * present and fall back to [timestamp] otherwise.
     */
    @SerialName("timestamp_unix") val timestampUnix: Long? = null,
    val batt: Int? = null,
    @SerialName("ischarging") val isCharging: Boolean? = null,
)

@Serializable
data class LocationsResponse(
    val locations: List<LocationDto> = emptyList(),
    val latest: LocationDto? = null,
)

/**
 * One point from `GET /api/locations/shared`.
 *
 * `age_seconds` is provided **only** by this endpoint; it is the authoritative
 * staleness value and is never derived from the [timestamp] display string.
 * `timestamp_unix` is the additive epoch fallback for an old server that sends
 * the point without `age_seconds`.
 */
@Serializable
data class SharedLocationDto(
    val lat: Double,
    val lon: Double,
    val timestamp: String? = null,
    @SerialName("timestamp_unix") val timestampUnix: Long? = null,
    @SerialName("age_seconds") val ageSeconds: Long? = null,
    val batt: Int? = null,
    @SerialName("ischarging") val isCharging: Boolean? = null,
)

/**
 * One person who shares their location with the caller, as returned by
 * `GET /api/locations/shared`.
 *
 * A permitted person with no locations is omitted entirely from the response,
 * so [latest] is normally present for anyone the endpoint returns; it stays
 * nullable so an unusual response cannot crash the widget.
 */
@Serializable
data class SharedPersonDto(
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    val latest: SharedLocationDto? = null,
    val trail: List<SharedLocationDto> = emptyList(),
)

/**
 * The `GET /api/locations/shared` body. The endpoint is fail-closed: it lists
 * exactly the people whose `sharing_permission` row names the caller, and omits
 * anyone with no reported location. On failure the server returns HTTP 500 with
 * `{"people": [], "error": ...}` — an error, never "nobody shares with you".
 */
@Serializable
data class SharedPeopleResponse(
    val people: List<SharedPersonDto> = emptyList(),
    val error: String? = null,
)
