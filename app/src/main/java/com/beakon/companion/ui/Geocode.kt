package com.beakon.companion.ui

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.resume

/**
 * Client-side reverse geocoding for history points: coordinates in, a
 * human-readable place name out, or null when no name is available.
 *
 * The lookup is deliberately a suspending function with no dispatcher of its
 * own. Callers run it off the main thread (AppViewModel uses Dispatchers.IO),
 * because the platform geocoder performs a blocking network call.
 *
 * A null result is not an error: the row simply keeps showing its
 * coordinates, exactly as it did before geocoding existed. That covers a
 * device with no geocoder service, a lookup that throws, and a coordinate
 * with no address at all.
 */
fun interface GeocodeLookup {
    suspend fun placeName(lat: Double, lon: Double): String?
}

/**
 * Cache key for a history point, rounded to the 5 decimal places the History
 * screen displays. Two points that display identically share one lookup, so
 * a repeated stop does not re-query the geocoder.
 */
fun geocodeCacheKey(lat: Double, lon: Double): String =
    "%.5f,%.5f".format(Locale.US, lat, lon)

/**
 * The single line shown for a geocoded point.
 *
 * A full address line wins when the geocoder supplies one. Otherwise the
 * locality, admin area and country are joined, skipping any blank part.
 * Returns null when nothing usable was supplied.
 */
fun formatPlaceName(
    addressLine: String?,
    locality: String?,
    adminArea: String?,
    countryName: String?,
): String? {
    if (!addressLine.isNullOrBlank()) return addressLine
    val parts = listOf(locality, adminArea, countryName)
        .mapNotNull { it?.takeIf { part -> part.isNotBlank() } }
    return parts.joinToString(", ").takeIf { it.isNotEmpty() }
}

/**
 * Platform geocoder implementation. Returns null when the device has no
 * geocoder service, when no address is found, or when the lookup throws.
 * Cancellation is never swallowed: it is rethrown so the caller's coroutine
 * can actually cancel.
 *
 * On Android 13+ the synchronous geocoder call is deprecated and its backend
 * is unreliable on some devices, so the listener API is used there instead,
 * with a 10 s timeout after which the lookup simply yields no name. Older
 * devices keep the synchronous call, which is their only option.
 */
class AndroidGeocoderLookup(context: Context) : GeocodeLookup {
    private val geocoder = Geocoder(context, Locale.getDefault())

    override suspend fun placeName(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                placeNameAsync(lat, lon)
            } else {
                placeNameSync(lat, lon)
            } ?: return null
            formatAddress(address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun placeNameAsync(lat: Double, lon: Double): Address? {
        val addresses = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<List<Address>> { continuation ->
                geocoder.getFromLocation(
                    lat,
                    lon,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: List<Address>) {
                            if (continuation.isActive) continuation.resume(addresses)
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(emptyList())
                        }
                    },
                )
            }
        } ?: return null
        return addresses.firstOrNull()
    }

    @Suppress("DEPRECATION")
    private fun placeNameSync(lat: Double, lon: Double): Address? =
        geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()

    private fun formatAddress(address: Address): String? = formatPlaceName(
        addressLine = if (address.maxAddressLineIndex >= 0) {
            address.getAddressLine(0)
        } else {
            null
        },
        locality = address.locality,
        adminArea = address.adminArea,
        countryName = address.countryName,
    )
}

/**
 * Tries [primary] first and falls back to [fallback] only when the primary
 * returns no name. A primary that throws (other than cancellation) is
 * treated the same as a null result, so one failing backend never blocks
 * the other. Cancellation is never swallowed.
 */
class FallbackGeocodeLookup(
    private val primary: GeocodeLookup,
    private val fallback: GeocodeLookup,
) : GeocodeLookup {
    override suspend fun placeName(lat: Double, lon: Double): String? {
        val primaryName = try {
            primary.placeName(lat, lon)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (primaryName != null) return primaryName
        return try {
            fallback.placeName(lat, lon)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Extracts the place name from a Nominatim reverse-geocoding response
 * (`format=jsonv2`). Returns null for an error response, a missing or
 * blank `display_name`, or unparseable JSON. Pure, so it is unit-testable
 * without any network.
 */
fun parseNominatimDisplayName(json: String): String? {
    return try {
        val element = Json { ignoreUnknownKeys = true }.parseToJsonElement(json)
        val name = (element.jsonObject["display_name"] as? JsonPrimitive)?.content
        name?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}

/**
 * Reverse geocoding via Nominatim (OpenStreetMap), used only when the
 * platform Geocoder yields nothing. No API key is needed. The request
 * carries an identifying User-Agent, as Nominatim's usage policy
 * requires, and requests are throttled to at most one per second across
 * all callers of this instance, also per that policy.
 *
 * The coordinates in the request are sent to nominatim.openstreetmap.org;
 * nothing else about the device or account is sent. A non-200 response,
 * an empty body, or a network error yields null, i.e. coordinates only.
 */
class NominatimGeocoderLookup(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val minIntervalMs: Long = 1_000,
    private val now: () -> Long = System::currentTimeMillis,
) : GeocodeLookup {
    private val throttleMutex = Mutex()
    private var lastRequestMs: Long = 0

    override suspend fun placeName(lat: Double, lon: Double): String? {
        return try {
            withContext(Dispatchers.IO) {
                throttleMutex.withLock {
                    val wait = minIntervalMs - (now() - lastRequestMs)
                    if (wait > 0) delay(wait)
                    lastRequestMs = now()
                }
                val url = "https://nominatim.openstreetmap.org/reverse".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("format", "jsonv2")
                    .addQueryParameter("lat", lat.toString())
                    .addQueryParameter("lon", lon.toString())
                    .addQueryParameter("zoom", "18")
                    .addQueryParameter("addressdetails", "0")
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Beakon Companion/1.1")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    parseNominatimDisplayName(body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
