package com.beakon.companion.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beakon.companion.BeakonApplication
import com.beakon.companion.net.SharedLocationsResult
import com.beakon.companion.net.SharedPersonDto
import com.beakon.companion.ui.theme.BeakonTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Placement-time configuration (D1): one widget is pinned to one person. The
 * launcher starts this activity when the widget is dropped, the user picks a
 * person (or "the first sharer"), and the choice is stored per `appWidgetId` in
 * [com.beakon.companion.data.AppPreferences].
 *
 * It reuses the app's HTTP/TLS client and the stored API token; it adds no
 * credential store and no trust-all path.
 */
class BeakonWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cancelled unless the user actually picks someone.
        setResult(RESULT_CANCELED)

        val appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val application = application as BeakonApplication
        setContent {
            BeakonTheme {
                WidgetConfigScreen(
                    loadPeople = { loadPeople(application) },
                    onChoose = { username ->
                        application.container.preferences.setWidgetPerson(appWidgetId, username)
                        WidgetWorker.refreshNow(this)
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                        )
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    /** Null means the app is not signed in; otherwise the endpoint's outcome. */
    private suspend fun loadPeople(application: BeakonApplication): SharedLocationsResult? {
        val container = application.container
        val url = container.preferences.instanceUrl
        val secret = container.secrets.secret()
        if (url.isNullOrBlank() || secret.isNullOrBlank()) return null
        val client = container.beaconClient() ?: return null
        return withContext(Dispatchers.IO) { client.fetchSharedLocations(secret) }
    }
}

@Composable
private fun WidgetConfigScreen(
    loadPeople: suspend () -> SharedLocationsResult?,
    onChoose: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var people by remember { mutableStateOf<List<SharedPersonDto>>(emptyList()) }

    LaunchedEffect(Unit) {
        when (val result = loadPeople()) {
            null -> error = "Open Beakon and sign in first."
            is SharedLocationsResult.Success -> people = result.people
            else -> error = "Could not load who shares their location with you."
        }
        loading = false
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Choose a person for this widget",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "The widget shows that person's latest shared location.",
                style = MaterialTheme.typography.bodyMedium,
            )

            when {
                loading -> CircularProgressIndicator()
                error != null -> Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                )
                people.isEmpty() -> Text("Nobody shares their location with you yet.")
                else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(people, key = { it.username }) { person ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChoose(person.username) }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(
                                text = person.displayName?.takeIf { it.isNotBlank() }
                                    ?: person.username,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }

            TextButton(onClick = { onChoose(null) }) {
                Text("Use the first sharer")
            }
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}
