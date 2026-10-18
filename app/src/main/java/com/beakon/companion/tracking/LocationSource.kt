package com.beakon.companion.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Source of a single current location fix. */
interface LocationSource {
    suspend fun currentLocation(): Location?
    fun isAvailable(): Boolean
}

/**
 * Uses Google Play services' fused provider when available, and falls back to
 * the platform [LocationManager] when it is not.
 *
 * Callers must hold a location permission; the service only starts tracking
 * after the UI has obtained it.
 */
object LocationSourceFactory {
    fun create(context: Context): LocationSource {
        val playServicesAvailable = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        return if (playServicesAvailable) {
            FusedLocationSource(context)
        } else {
            PlatformLocationSource(context)
        }
    }
}

class FusedLocationSource(context: Context) : LocationSource {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    override fun isAvailable(): Boolean = true

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): Location? =
        withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { location -> continuation.resume(location) }
                    .addOnFailureListener { continuation.resume(null) }
            }
        }
}

class PlatformLocationSource(context: Context) : LocationSource {

    private val manager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override fun isAvailable(): Boolean = manager.allProviders.isNotEmpty()

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): Location? {
        val providers = PROVIDERS.filter { manager.isProviderEnabled(it) }
        if (providers.isEmpty()) return null

        val lastKnown = providers
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null) return lastKnown

        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        continuation.resume(location)
                    }

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) = Unit

                    @Deprecated("Deprecated in Java")
                    @Suppress("DEPRECATION")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                manager.requestLocationUpdates(providers.first(), 0L, 0f, listener, Looper.getMainLooper())
            }
        }
    }

    private companion object {
        val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
    }
}

private const val FIX_TIMEOUT_MS = 20_000L
