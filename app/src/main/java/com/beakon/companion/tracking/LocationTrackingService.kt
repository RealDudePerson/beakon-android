package com.beakon.companion.tracking

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.beakon.companion.BeakonApplication
import com.beakon.companion.data.AppContainer
import com.beakon.companion.net.BeakonClient
import com.beakon.companion.net.BeakonUrl
import com.beakon.companion.net.LocationReport
import com.beakon.companion.net.SendResult
import com.beakon.companion.net.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that reports the device location on an interval.
 *
 * Started only by explicit user action. On each tick it reads a fix and battery
 * state, POSTs to the instance and records the outcome. Failures are surfaced in
 * the persistent notification and on the Status screen — never swallowed.
 */
class LocationTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private lateinit var container: AppContainer
    private lateinit var locationSource: LocationSource
    private val backoff = RateLimitBackoff()

    private var cachedClient: BeakonClient? = null
    private var cachedClientKey: Pair<String, String?>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as BeakonApplication).container
        locationSource = LocationSourceFactory.create(this)
        startForegroundSafely("Preparing to report location…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        startTracking()
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startTracking() {
        if (loopJob?.isActive == true) return
        container.trackingState.setTracking(true)
        container.preferences.trackingEnabled = true
        loopJob = scope.launch {
            while (isActive) {
                runTick()
                delay(container.preferences.intervalSeconds * 1000L)
            }
        }
    }

    private fun stopTracking() {
        loopJob?.cancel()
        loopJob = null
        container.trackingState.setTracking(false)
        container.preferences.trackingEnabled = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runTick() {
        val url = container.preferences.instanceUrl
        val username = container.preferences.username
        val secret = container.secrets.secret()
        if (url == null || username == null || secret == null) {
            container.trackingState.recordResult(SendResult.NotConfigured)
            updateNotification("Not configured — sign in from Setup.")
            return
        }
        val host = BeakonUrl.host(url)
        if (host == null) {
            container.trackingState.recordResult(SendResult.NotConfigured)
            updateNotification("Instance URL is invalid — check Setup.")
            return
        }

        val client = clientFor(url, container.fingerprints.pinFor(host))
        val battery = BatteryReader.read(this)
        container.trackingState.recordBattery(battery?.percent, battery?.charging)

        val location = locationSource.currentLocation()
        val report = location?.let {
            LocationReport(
                lat = it.latitude,
                lon = it.longitude,
                acc = if (it.hasAccuracy()) it.accuracy.toDouble() else null,
                batt = battery?.percent,
                isCharging = battery?.charging,
            )
        }

        val coordinator = ReportCoordinator(
            client = client,
            username = username,
            secret = secret,
            state = container.trackingState,
            backoff = backoff,
        )
        val result = coordinator.report(report)
        updateNotification(notificationText(result, report != null))
    }

    private fun clientFor(url: String, pin: String?): BeakonClient {
        val key = url to pin
        val existing = cachedClient
        if (existing != null && cachedClientKey == key) return existing
        return BeakonClient(url, pin).also {
            cachedClient = it
            cachedClientKey = key
        }
    }

    private fun notificationText(result: SendResult, hadFix: Boolean): String = when (result) {
        SendResult.Success -> "Location reported successfully."
        else -> result.userMessage() ?: if (hadFix) "Reporting…" else "Waiting for a fix…"
    }

    private fun startForegroundSafely(text: String) {
        val notification = NotificationHelper.build(this, text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                startForeground(NotificationHelper.NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            container.trackingState.setTracking(false)
            container.preferences.trackingEnabled = false
            NotificationHelper.showError(
                this,
                "Tracking could not start: ${e.message ?: "location permission missing"}",
            )
            stopSelf()
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NotificationHelper.NOTIFICATION_ID, NotificationHelper.build(this, text))
    }

    companion object {
        const val ACTION_START = "com.beakon.companion.action.START"
        const val ACTION_STOP = "com.beakon.companion.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
