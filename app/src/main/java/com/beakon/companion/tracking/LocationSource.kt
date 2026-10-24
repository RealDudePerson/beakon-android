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
    fun create(context: Context, intervalSeconds: () -> Int): LocationSource {
        val playServicesAvailable = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        return if (playServicesAvailable) {
            FusedLocationSource(context)
        } else {
            PlatformLocationSource(context, intervalSeconds)
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

class PlatformLocationSource(
    context: Context,
    private val intervalSeconds: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) : LocationSource {

    private val manager =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override fun isAvailable(): Boolean = manager.allProviders.isNotEmpty()

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): Location? {
        val providers = PROVIDERS.filter { manager.isProviderEnabled(it) }
        if (providers.isEmpty()) return null

        val knownFixes = providers
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        // Reject stale last-known fixes: they would otherwise be pushed with a
        // fresh server timestamp and present an old position as current.
        val lastKnown = LastKnownFix.freshest(
            fixes = knownFixes,
            nowMs = clock(),
            maxAgeMs = LastKnownFix.maxAgeMs(intervalSeconds()),
            timeOf = { it.time },
        )
        if (lastKnown != null) return lastKnown

        // Nothing fresh: try a live fix. If that also yields nothing the caller
        // reports NoFix/waiting — a position is never invented.
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
                // Sane minimums instead of 0/0: 0/0 asks for updates as fast as
                // the provider can deliver, the worst-case radio/GPS cost in the
                // app. The first callback still wins (the timeout is 20 s).
                manager.requestLocationUpdates(
                    providers.first(),
                    FallbackFixRequest.MIN_TIME_MS,
                    FallbackFixRequest.MIN_DISTANCE_M,
                    listener,
                    Looper.getMainLooper(),
                )
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

/**
 * Rates for the fallback live-fix request. Kept in one place so the values are
 * unit-tested and cannot silently drift back to `0`/`0`. They are deliberately
 * far below the reporting interval: this request only exists to obtain a first
 * fix, and the first callback wins.
 */
object FallbackFixRequest {
    const val MIN_TIME_MS = 1_000L
    const val MIN_DISTANCE_M = 1f
}
