package com.beakon.companion.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.beakon.companion.ui.AppViewModel
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun MapScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { viewModel.loadHistory() }

    val points = viewModel.history
    // Plain holder (not Compose state): the camera must be centred once, then
    // left alone so a refresh cannot snap it away from where the user panned.
    val cameraState = remember { MapCameraState() }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(14.0)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.overlays.clear()
                val geoPoints = points.map { GeoPoint(it.lat, it.lon) }
                if (geoPoints.isNotEmpty()) {
                    view.overlays.add(
                        Polyline(view).apply {
                            setPoints(geoPoints)
                            outlinePaint.color = android.graphics.Color.RED
                            outlinePaint.strokeWidth = 8f
                        },
                    )
                    // The API's `latest` field wins over list order.
                    val latest = latestMarkerPoint(viewModel.latest, points)
                    val latestGeo = latest?.let { GeoPoint(it.lat, it.lon) } ?: geoPoints.last()
                    view.overlays.add(
                        Marker(view).apply {
                            position = latestGeo
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "Latest"
                        },
                    )
                    if (MapCamera.shouldCentre(cameraState.hasCentred, geoPoints.isNotEmpty())) {
                        view.controller.setCenter(latestGeo)
                        cameraState.hasCentred = true
                    }
                }
                view.invalidate()
            },
        )

        if (viewModel.historyLoading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        if (emptyPointsState(points, viewModel.historyLoading, viewModel.historyMessage) ==
            PointsEmptyState.NoPoints
        ) {
            Text(
                "No points yet. Location points will appear here after Beakon reports one.",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        viewModel.historyMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }
}
