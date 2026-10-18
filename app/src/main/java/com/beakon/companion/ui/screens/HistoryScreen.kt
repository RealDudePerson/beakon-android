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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beakon.companion.net.LocationDto
import com.beakon.companion.ui.AppViewModel
import com.beakon.companion.ui.Format

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
            IconButton(onClick = { viewModel.loadHistory() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }

        viewModel.historyMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }

        if (viewModel.historyLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(viewModel.history.asReversed()) { point ->
                HistoryRow(point)
            }
        }
    }
}

@Composable
private fun HistoryRow(point: LocationDto) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(Format.serverTime(point.timestamp), style = MaterialTheme.typography.titleSmall)
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
