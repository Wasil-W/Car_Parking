package dev.wasil.permit.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.ParkStateStore
import dev.wasil.permit.parking.android.PlayServicesSignals
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Personal map: my car's last parked spot + my current position. Nothing here
 * is shared with the other phone (deliberate — see the Phase 3 spec).
 */
@SuppressLint("MissingPermission")
@Composable
fun MapScreen(stateStore: ParkStateStore, onBack: () -> Unit) {
    val context = LocalContext.current
    var me by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(Unit) {
        // One-shot read while the screen is open; no tracking.
        me = PlayServicesSignals(context).currentLocation()
    }
    val car = stateStore.lastParkLocation
    val parkedAt = stateStore.parkedAtMs.takeIf { it > 0 && stateStore.parked }
        ?.let { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(it)) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Map", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                car == null -> "No parked location recorded yet."
                parkedAt != null -> "Car parked $parkedAt."
                else -> "Last known car position shown."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(16.0)
                    val center = car ?: me
                    controller.setCenter(org.osmdroid.util.GeoPoint(
                        center?.lat ?: 52.3702, center?.lng ?: 4.8952))
                }
            },
            update = { map ->
                map.overlays.removeAll { it is Marker }
                car?.let {
                    map.overlays.add(Marker(map).apply {
                        position = org.osmdroid.util.GeoPoint(it.lat, it.lng)
                        title = "My car"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                }
                me?.let {
                    map.overlays.add(Marker(map).apply {
                        position = org.osmdroid.util.GeoPoint(it.lat, it.lng)
                        title = "Me"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                }
                map.invalidate()
            },
        )
        Text("© OpenStreetMap contributors", style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Back") }
    }
}
