package com.beakon.companion.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * URL validation/normalisation for the user-supplied instance base URL.
 * HTTPS only: a plain `http://` URL is rejected outright.
 */
object BeakonUrl {

    /** Returns a normalised `https://host[:port]` base URL, or null if invalid. */
    fun normalize(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val withScheme = when {
            value.startsWith("https://", ignoreCase = true) -> value
            value.startsWith("http://", ignoreCase = true) -> return null
            else -> "https://$value"
        }
        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        if (parsed.host.isBlank()) return null
        return withScheme.trimEnd('/')
    }

    fun host(raw: String): String? = normalize(raw)?.toHttpUrlOrNull()?.host

    fun port(raw: String): Int? = normalize(raw)?.toHttpUrlOrNull()?.port
}
