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
    val timestamp: Long? = null,
    val batt: Int? = null,
    @SerialName("ischarging") val isCharging: Boolean? = null,
)

@Serializable
data class LocationsResponse(
    val locations: List<LocationDto> = emptyList(),
    val latest: LocationDto? = null,
)
