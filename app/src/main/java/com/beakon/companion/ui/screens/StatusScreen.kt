package com.beakon.companion.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.beakon.companion.tracking.LocationTrackingService
import com.beakon.companion.ui.AppViewModel
import com.beakon.companion.ui.Format
import com.beakon.companion.ui.Permissions

@Composable
fun StatusScreen(
    viewModel: AppViewModel,
    onOpenSetup: () -> Unit,
) {
    val context = LocalContext.current
    val status by viewModel.status.collectAsState()
    val startTrackingOnBoot by viewModel.startTrackingOnBoot.collectAsState()
    var permissionMessage by remember { mutableStateOf<String?>(null) }

    // Permission chain: foreground -> background -> notifications -> start.
    // Declared in reverse order so each callback can reference the next launcher.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            permissionMessage =
                "Notification permission denied. Android may hide the tracking notification, but tracking will continue."
        }
        LocationTrackingService.start(context)
    }

    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            permissionMessage =
                "Background location denied. Tracking will only update while the app is open (degraded mode, not an error)."
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Permissions.hasNotifications(context)) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            LocationTrackingService.start(context)
        }
    }

    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (!granted) {
            permissionMessage = "Location permission is required to track. Tracking was not started."
            return@rememberLauncherForActivityResult
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Permissions.hasBackgroundLocation(context)) {
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Permissions.hasNotifications(context)) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            LocationTrackingService.start(context)
        }
    }

    fun requestStart() {
        permissionMessage = null
        when {
            !Permissions.hasForegroundLocation(context) ->
                foregroundLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Permissions.hasBackgroundLocation(context) ->
                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Permissions.hasNotifications(context) ->
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> LocationTrackingService.start(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Status", style = MaterialTheme.typography.headlineSmall)

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Tracking", style = MaterialTheme.typography.titleLarge)
                    Switch(
                        checked = status.tracking,
                        onCheckedChange = { on ->
                            if (on) requestStart() else LocationTrackingService.stop(context)
                        },
                    )
                }
                if (status.trackingNeedsResume) {
                    Text(
                        "Tracking was on but isn't running — tap to resume",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { requestStart() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Resume tracking")
                    }
                }
                Text(
                    "Beakon asks for foreground location, then background location, then notification " +
                        "permission. Background tracking needs the location foreground service and a " +
                        "persistent notification; denying background access still allows tracking while " +
                        "the app is open.",
                    style = MaterialTheme.typography.bodySmall,
                )
                permissionMessage?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusRow("Last fix", Format.time(status.lastFixAt))
                StatusRow("Last successful send", Format.time(status.lastSuccessfulSendAt))
                StatusRow("Points sent today", status.pointsSentToday.toString())
                StatusRow(
                    "Battery",
                    when {
                        status.batteryPercent == null -> "—"
                        status.charging == true -> "${status.batteryPercent}% (charging)"
                        else -> "${status.batteryPercent}%"
                    },
                )
                status.lastHeldAccuracyM?.let { heldM ->
                    val thresholdM = status.lastHeldThresholdM
                    Text(
                        if (thresholdM != null) {
                            "Last fix held: accurate to $heldM m, threshold $thresholdM m. " +
                                "The map still shows the last good position."
                        } else {
                            "Last fix held: accurate to $heldM m."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                status.lastError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reporting interval", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The server rate limits /api/recordlocation to 5 requests per 300 s per IP. " +
                        "The default is 90 s; 60 s is the minimum offered.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60, 90, 120, 300).forEach { seconds ->
                        FilterChip(
                            selected = viewModel.intervalSeconds == seconds,
                            onClick = { viewModel.setInterval(seconds) },
                            label = { Text("${seconds}s") },
                        )
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Accuracy threshold", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Fixes less accurate than this are held back, so the map keeps showing the " +
                        "last good position. A stricter threshold is more precise but updates less " +
                        "often — especially indoors. Default 30 m.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 20, 30, 50, 100).forEach { meters ->
                        FilterChip(
                            selected = viewModel.accuracyThresholdM == meters,
                            onClick = { viewModel.setAccuracyThreshold(meters) },
                            label = { Text("${meters} m") },
                        )
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Start tracking at boot", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = startTrackingOnBoot,
                        onCheckedChange = { viewModel.setStartTrackingOnBoot(it) },
                    )
                }
                Text(
                    "When on, Beakon resumes reporting automatically after the device " +
                        "restarts, provided it is signed in. Off by default; nothing is " +
                        "started at boot unless you turn this on.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        OutlinedButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
            Text("Open Setup")
        }
        Button(
            onClick = {
                LocationTrackingService.stop(context)
                viewModel.logout()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Log out and stop tracking")
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
