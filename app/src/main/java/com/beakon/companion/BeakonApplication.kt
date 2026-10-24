package com.beakon.companion

import android.app.Application
import android.content.Context
import com.beakon.companion.data.AppContainer
import com.beakon.companion.tracking.LocationTrackingService
import com.beakon.companion.tracking.NotificationHelper
import org.osmdroid.config.Configuration

class BeakonApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // osmdroid: descriptive User-Agent (required by the OSM tile policy) and a
        // private on-disk tile cache.
        val osmdroidPrefs = getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().load(this, osmdroidPrefs)
        Configuration.getInstance().userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"

        // Restore the tracking toggle across process death. The service is never
        // started automatically; if the persisted intent says "on" but the
        // service is not running, the Status screen shows an explicit resume
        // state instead of claiming tracking is active.
        container.trackingState.setTrackingHealth(
            trackingEnabled = container.preferences.trackingEnabled,
            serviceRunning = LocationTrackingService.isRunning,
        )

        NotificationHelper.createChannel(this)
    }
}
