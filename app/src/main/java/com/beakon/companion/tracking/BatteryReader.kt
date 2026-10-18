package com.beakon.companion.tracking

import android.content.Context
import android.os.BatteryManager

data class BatteryReading(val percent: Int, val charging: Boolean)

/** Reads battery percentage and charging state without registering a receiver. */
object BatteryReader {

    fun read(context: Context): BatteryReading? {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return null
        val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (percent < 0 || percent > 100) return null
        return BatteryReading(percent = percent, charging = manager.isCharging)
    }
}
