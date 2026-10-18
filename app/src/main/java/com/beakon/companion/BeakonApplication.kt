package com.beakon.companion

import android.app.Application
import android.content.Context
import com.beakon.companion.data.AppContainer
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
        // started automatically; the user re-enables it from the Status screen.
        container.trackingState.setTracking(container.preferences.trackingEnabled)

        NotificationHelper.createChannel(this)
    }
}
