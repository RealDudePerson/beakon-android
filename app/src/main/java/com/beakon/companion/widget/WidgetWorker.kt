package com.beakon.companion.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.beakon.companion.BeakonApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Refreshes every placed widget: fetch the shared locations with the stored API
 * token, resolve the widget's state, draw the map bitmap, push the `RemoteViews`.
 *
 * Scheduling (acceptance criterion 6): a **periodic** WorkManager job with a
 * **30-minute** interval and a `CONNECTED` network constraint. (WorkManager's
 * floor for periodic work is 15 minutes; the spec's D2 prose says 15, but the
 * acceptance criterion fixes it at 30, which is what is implemented here.) The
 * endpoint carries no rate limiter, so these reads do not touch the 90 s
 * reporting budget. WorkManager persists its queue in its own database and
 * reschedules after reboot (it registers its own boot receiver), so the job
 * survives a restart without any change to the tracking service or the
 * `BOOT_COMPLETED` receiver.
 *
 * Every failure path renders a visible state on the widget; nothing fails
 * silently.
 */
class WidgetWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? BeakonApplication ?: return Result.success()
        val container = application.container
        val manager = AppWidgetManager.getInstance(applicationContext)
        val ids = manager.getAppWidgetIds(
            ComponentName(applicationContext, BeakonWidgetProvider::class.java),
        )
        if (ids.isEmpty()) return Result.success()

        val prefs = container.preferences
        val instanceUrl = prefs.instanceUrl
        val secret = container.secrets.secret()
        val tileClient = WidgetRenderer.tileClient()

        if (instanceUrl.isNullOrBlank() || secret.isNullOrBlank()) {
            // Not signed in: say so on every widget rather than showing nothing.
            ids.forEach { id ->
                push(
                    manager = manager,
                    id = id,
                    state = WidgetState.Error("Open Beakon and sign in."),
                    instanceUrl = instanceUrl,
                    tileClient = tileClient,
                )
            }
            return Result.success()
        }

        // The app's one HTTP/TLS client (platform trust, fingerprint only as the
        // explicit fallback). Null only when the stored URL is unusable.
        val client = container.beaconClient()
        if (client == null) {
            ids.forEach { id ->
                push(
                    manager = manager,
                    id = id,
                    state = WidgetState.Error("Instance URL is invalid."),
                    instanceUrl = instanceUrl,
                    tileClient = tileClient,
                )
            }
            return Result.success()
        }
        // Blocking network call: never on the main thread.
        val result = withContext(Dispatchers.IO) { client.fetchSharedLocations(secret) }

        ids.forEach { id ->
            val state = WidgetStateFactory.resolve(result, prefs.widgetPerson(id))
            push(manager, id, state, instanceUrl, tileClient)
        }
        return Result.success()
    }

    private suspend fun push(
        manager: AppWidgetManager,
        id: Int,
        state: WidgetState,
        instanceUrl: String?,
        tileClient: okhttp3.OkHttpClient,
    ) {
        val nowSeconds = System.currentTimeMillis() / 1_000L
        val name: String
        val age: String
        val message: String?
        val tapUrl: String?
        when (state) {
            is WidgetState.Person -> {
                name = state.displayName
                age = WidgetAge.format(state.ageSeconds, state.timestampUnix, nowSeconds)
                message = if (state.fallbackFromChosen) {
                    "Your chosen person isn't sharing right now"
                } else {
                    null
                }
                tapUrl = WidgetTap.mapUrl(instanceUrl, state.username)
            }
            is WidgetState.Empty -> {
                name = "Beakon"
                age = ""
                message = state.message
                tapUrl = WidgetTap.mapUrl(instanceUrl, null)
            }
            is WidgetState.Error -> {
                name = "Beakon"
                age = ""
                message = state.message
                tapUrl = WidgetTap.mapUrl(instanceUrl, null)
            }
        }

        val map = if (state is WidgetState.Person) {
            // Blocking tile fetch + decode: never on the main thread.
            withContext(Dispatchers.IO) {
                WidgetRenderer.fetchTile(tileClient, state.lat, state.lon)?.let { tile ->
                    WidgetRenderer.compose(tile, state.lat, state.lon)
                }
            } ?: WidgetRenderer.placeholder()
        } else {
            WidgetRenderer.placeholder()
        }

        val views = WidgetRenderer.remoteViews(
            context = applicationContext,
            appWidgetId = id,
            name = name,
            age = age,
            message = message,
            map = map,
            tapUrl = tapUrl,
        )
        manager.updateAppWidget(id, views)
    }

    companion object {

        /** Schedules the surviving periodic refresh. Idempotent. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetWorker>(
                WidgetSchedule.REFRESH_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WidgetSchedule.PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** Requests an immediate one-off refresh (e.g. right after placement). */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetWorker>()
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WidgetSchedule.ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /** Stops refreshing once the last widget is removed. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WidgetSchedule.PERIODIC_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(WidgetSchedule.ONE_TIME_WORK_NAME)
        }

        private fun networkConstraints(): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(WidgetSchedule.REQUIRED_NETWORK_TYPE)
                .build()
    }
}
