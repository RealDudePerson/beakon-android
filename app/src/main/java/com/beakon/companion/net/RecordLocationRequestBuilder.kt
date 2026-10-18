package com.beakon.companion.net

import kotlinx.serialization.json.Json

/**
 * Builds the headers and JSON body for `POST /api/recordlocation`.
 *
 * Authentication is by the `username` and `secret` headers (there is no bearer
 * token). The body omits absent battery/charging values.
 */
object RecordLocationRequestBuilder {

    /** Default encoder: `encodeDefaults = false` omits properties left at their null default. */
    val json: Json = Json {
        encodeDefaults = false
    }

    fun headers(username: String, secret: String): Map<String, String> = mapOf(
        "username" to username,
        "secret" to secret,
    )

    fun body(report: LocationReport): String =
        json.encodeToString(LocationReport.serializer(), report)
}
