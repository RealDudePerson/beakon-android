package com.beakon.companion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beakon.companion.net.LocationDto
import com.beakon.companion.ui.AppViewModel
import com.beakon.companion.ui.Format
import com.beakon.companion.ui.geocodeCacheKey

@Composable
fun HistoryScreen(viewModel: AppViewModel) {
    LaunchedEffect(Unit) { viewModel.loadHistory() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Recent points", style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = { viewModel.loadHistory(force = true) }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }

        // The loading flag and the error message are read inside their own small
        // composables. That scopes each read to that composable, so flipping the
        // loading flag no longer recomposes HistoryScreen (and therefore does not
        // invalidate the list below). Passing an accessor rather than the whole
        // (unstable) AppViewModel keeps the unstable parameter out of the API.
        HistoryMessage { viewModel.historyMessage }
        HistoryProgress { viewModel.historyLoading }
        HistoryEmpty(
            points = { viewModel.history },
            loading = { viewModel.historyLoading },
            message = { viewModel.historyMessage },
        )

        HistoryList(
            history = viewModel.history,
            placeNames = { viewModel.placeNames },
            loadPlaceName = { point -> viewModel.loadPlaceName(point) },
        )
    }
}

@Composable
private fun HistoryMessage(message: () -> String?) {
    message()?.let { text ->
        Text(text, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun HistoryProgress(loading: () -> Boolean) {
    if (loading()) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * A successful fetch with zero points is not an error and must not look like a
 * blank/broken screen. Read through accessors so the loading flag does not
 * invalidate the whole screen (see the comment in [HistoryScreen]).
 */
@Composable
private fun HistoryEmpty(
    points: () -> List<LocationDto>,
    loading: () -> Boolean,
    message: () -> String?,
) {
    if (emptyPointsState(points(), loading(), message()) == PointsEmptyState.NoPoints) {
        Text(
            "No points yet. Location points will appear here after Beakon reports one.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Newest-first history list.
 *
 * The reverse order and the per-item keys are computed once per [history] value
 * (inside [remember]) instead of on every composition. `asReversed()` is
 * deliberately not used: an index loop avoids allocating the extra list wrapper
 * that the old `items(viewModel.history.asReversed())` created each composition.
 * Each row is keyed by its content, so item identity is stable across a refresh
 * that returns the same points rather than being positional.
 */
@Composable
private fun HistoryList(
    history: List<LocationDto>,
    placeNames: () -> Map<String, String>,
    loadPlaceName: (LocationDto) -> Unit,
) {
    val rows = remember(history) { newestFirstKeyed(history) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(rows, key = { it.key }) { row ->
            HistoryRow(
                key = row.key,
                point = row.point,
                placeName = placeNames()[geocodeCacheKey(row.point.lat, row.point.lon)],
                loadPlaceName = loadPlaceName,
            )
        }
    }
}

/** A history row paired with the stable key used for its item identity. */
internal data class KeyedLocation(val key: String, val point: LocationDto)

/**
 * Returns [history] newest-first, each point paired with a content-based key.
 *
 * A [LocationDto] carries no field the server guarantees to be unique, so a
 * point whose timestamp+coordinates repeat within the same list is given an
 * occurrence suffix. That preserves LazyColumn's "keys must be unique" contract
 * while still keying on content rather than on position.
 */
internal fun newestFirstKeyed(history: List<LocationDto>): List<KeyedLocation> {
    val seen = HashMap<String, Int>()
    return List(history.size) { index ->
        val point = history[history.size - 1 - index]
        // Include both the display string and the additive epoch: either may be
        // absent (old server omits timestamp_unix; a new one still sends the
        // string), and the key must stay content-based either way.
        val base = "${point.timestamp.orEmpty()}|${point.timestampUnix ?: ""}|${point.lat}|${point.lon}"
        val occurrence = seen.getOrDefault(base, 0)
        seen[base] = occurrence + 1
        KeyedLocation(if (occurrence == 0) base else "$base#$occurrence", point)
    }
}

@Composable
private fun HistoryRow(
    key: String,
    point: LocationDto,
    placeName: String?,
    loadPlaceName: (LocationDto) -> Unit,
) {
    // One lookup per row identity: the key is content-based (timestamp +
    // coordinates), so a refresh returning the same points does not re-query,
    // while a genuinely new point gets its own lookup when it scrolls in.
    LaunchedEffect(key) { loadPlaceName(point) }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(Format.serverTime(point.timestamp), style = MaterialTheme.typography.titleSmall)
            placeName?.let { name ->
                Text(name, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "%.5f, %.5f".format(point.lat, point.lon),
                style = MaterialTheme.typography.bodyMedium,
            )
            val battery = when {
                point.batt == null -> "battery unknown"
                point.isCharging == true -> "${point.batt}% (charging)"
                else -> "${point.batt}%"
            }
            Text(battery, style = MaterialTheme.typography.bodySmall)
        }
    }
}
