package com.beakon.companion.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.beakon.companion.MainActivity

/** Persistent foreground-service notification. */
object NotificationHelper {

    const val CHANNEL_ID = "beakon_tracking"
    const val ERROR_CHANNEL_ID = "beakon_errors"
    const val NOTIFICATION_ID = 1001
    const val ERROR_NOTIFICATION_ID = 1002

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Location tracking",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows that Beakon is reporting your location in the background."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ERROR_CHANNEL_ID,
                "Tracking problems",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Errors that stop Beakon from reporting your location."
            },
        )
    }

    fun build(context: Context, text: String): Notification =
        Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Beakon tracking")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(contentIntent(context))
            .build()

    /** Used when tracking cannot start; never fail silently. */
    fun showError(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = Notification.Builder(context, ERROR_CHANNEL_ID)
            .setContentTitle("Beakon tracking problem")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        manager.notify(ERROR_NOTIFICATION_ID, notification)
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
