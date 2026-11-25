package com.beakon.companion.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.beakon.companion.BeakonApplication

/**
 * The home-screen widget's `AppWidgetProvider`.
 *
 * It only wires lifecycle to [WidgetWorker]: the actual fetch, bitmap compositing
 * and `RemoteViews` update happen in the worker, which is the single code path
 * the periodic schedule and an immediate placement refresh both use.
 *
 * Declared `android:exported="false"` in the manifest (acceptance criterion 1):
 * the provider receives the system's `APPWIDGET_UPDATE` broadcast, and nothing
 * outside the app should be able to invoke it.
 */
class BeakonWidgetProvider : AppWidgetProvider() {

    /** A widget was placed or resized: schedule the periodic job and refresh now. */
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetWorker.schedule(context)
        WidgetWorker.refreshNow(context)
    }

    /** The first widget was placed: start the periodic refresh. */
    override fun onEnabled(context: Context) {
        WidgetWorker.schedule(context)
    }

    /** The last widget was removed: stop refreshing. */
    override fun onDisabled(context: Context) {
        WidgetWorker.cancel(context)
    }

    /** A widget was removed: forget the person it was pinned to. */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val application = context.applicationContext as? BeakonApplication ?: return
        appWidgetIds.forEach { id -> application.container.preferences.removeWidgetPerson(id) }
    }
}
