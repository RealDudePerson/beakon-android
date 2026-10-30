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
