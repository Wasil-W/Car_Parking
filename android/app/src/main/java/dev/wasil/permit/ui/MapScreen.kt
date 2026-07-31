package dev.wasil.permit.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.ParkStateStore
import dev.wasil.permit.parking.android.PlayServicesSignals
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Your car's last parked spot and your own position. Nothing here is shared
 * with the other phone — deliberately.
 */
@SuppressLint("MissingPermission")
@Composable
fun MapScreen(stateStore: ParkStateStore) {
    val context = LocalContext.current
    var me by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(Unit) {
        // One-shot read while the screen is open; no tracking.
        me = PlayServicesSignals(context).currentLocation()
    }
    val car = stateStore.lastParkLocation
    val parkedAt = stateStore.parkedAtMs.takeIf { it > 0 && stateStore.parked }
        ?.let { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(it)) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("Map", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                car == null -> "No parked location recorded yet."
                parkedAt != null -> "Car parked $parkedAt."
                else -> "Last known car position."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        MapCanvas(
            car = car,
            me = me,
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)),
        )
        Text(
            "© OpenStreetMap contributors",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}
