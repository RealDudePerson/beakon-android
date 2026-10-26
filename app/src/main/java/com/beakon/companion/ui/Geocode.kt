package com.beakon.companion.ui

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
