package com.beakon.companion.tracking

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
    private val authRejections = AuthRejectionTracker()

    private var cachedClient: BeakonClient? = null
    private var cachedClientKey: Pair<String, String?>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as BeakonApplication).container
        locationSource = LocationSourceFactory.create(this) {
            container.preferences.intervalSeconds
        }
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
        isRunning = false
        if (::container.isInitialized) {
            // If the service goes away while tracking was still enabled (system
            // stop, fatal error elsewhere), the UI must not keep claiming it is on.
            container.trackingState.setTrackingHealth(
                trackingEnabled = container.preferences.trackingEnabled,
                serviceRunning = false,
            )
        }
        super.onDestroy()
    }

    private fun startTracking() {
        if (loopJob?.isActive == true) return
        isRunning = true
        authRejections.reset()
        container.trackingState.clearNeedsReauth()
        container.trackingState.setTrackingHealth(trackingEnabled = true, serviceRunning = true)
        container.preferences.trackingEnabled = true
        loopJob = scope.launch {
            TickLoop.run(
                // Read the interval on every iteration: a change in Settings
                // takes effect from the next tick without restarting tracking.
                intervalMsProvider = { container.preferences.intervalSeconds * 1000L },
                tick = { runTick() },
                onError = { error ->
                    val message =
                        "Tracking tick failed: ${error.message ?: error.javaClass.simpleName}"
                    container.trackingState.recordTickError(message)
                    updateNotification(message)
                },
            )
        }
    }

    private fun stopTracking() {
        loopJob?.cancel()
        loopJob = null
        isRunning = false
        container.trackingState.setTrackingHealth(trackingEnabled = false, serviceRunning = false)
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

        // A partial wake lock keeps the CPU awake for this tick so location
        // acquisition and the HTTPS POST are not suspended mid-flight. It is
        // acquired immediately before location acquisition and released in
        // `finally`, including on failure. It does not change the 90 s cadence
        // and it cannot force a tick during deep Doze (see README).
        withWakeLock(
            acquire = { acquireWakeLock() },
            release = { lock -> if (lock.isHeld) lock.release() },
        ) {
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

            // Accuracy gate. A fix that reports an accuracy worse than the user's
            // threshold is held back rather than sent, so the map keeps the last
            // good position. A null accuracy is pushed: the app cannot judge it, and
            // dropping it would silently remove the user from the map (see
            // [AccuracyGate]). Held fixes are never silent: the notification carries
            // the numbers and the tracking state records them for the Status screen.
            val accuracy = report?.acc
            val thresholdM = container.preferences.accuracyThresholdM
            when (val action = TickWiring.fixAction(accuracy, thresholdM)) {
                is TickWiring.FixAction.Hold -> {
                    TickWiring.recordHeldFix(
                        recordFix = { container.trackingState.recordFix() },
                        recordHold = {
                            container.trackingState.recordAccuracyHold(action.accuracyM, action.thresholdM)
                        },
                    )
                    updateNotification(action.notification)
                    return@withWakeLock
                }
                TickWiring.FixAction.Report -> Unit
            }
            // This fix is being pushed, so any previous hold no longer applies.
            if (report != null) container.trackingState.clearAccuracyHold()

            val coordinator = ReportCoordinator(
                client = client,
                username = username,
                secret = secret,
                state = container.trackingState,
                backoff = backoff,
            )
            val result = coordinator.report(report)
            if (authRejections.onResult(result)) {
                // The secret is dead (e.g. it was overwritten by a sign-in on
                // another device). Stop instead of POSTing every 90 s forever:
                // the error notification and the Status banner point the user
                // at Setup, and tracking only resumes after a fresh sign-in.
                container.trackingState.recordAuthRejectedStop()
                NotificationHelper.showError(
                    this,
                    SendResult.AuthRejected.userMessage()
                        ?: "Authentication rejected by the server. Sign in again from Setup.",
                )
                stopTracking()
                return@withWakeLock
            }
            updateNotification(TickWiring.notificationText(result, report != null))
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? {
        val power = getSystemService(PowerManager::class.java) ?: return null
        return power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Safety timeout: even if the tick wedges, the lock self-releases.
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
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
            val message = "Tracking could not start: ${e.message ?: "location permission missing"}"
            // Persist before the notification: it may be hidden when the
            // notification permission was denied, and must still show at start.
            container.trackingState.recordTickError(message)
            NotificationHelper.showError(this, message)
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

        private const val WAKE_LOCK_TAG = "beakon:tracking-tick"
        private const val WAKE_LOCK_TIMEOUT_MS = 120_000L

        /**
         * True while this process's tracking service is alive. Used at app start
         * to reconcile the persisted "tracking on" preference with reality.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

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
