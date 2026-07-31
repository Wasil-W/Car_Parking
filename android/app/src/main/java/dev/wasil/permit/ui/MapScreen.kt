package dev.wasil.permit.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.FreeZoneStore
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.ParkStateStore
import dev.wasil.permit.parking.android.PlayServicesSignals
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which kind of zone the map is currently placing a candidate point for. */
private enum class ZoneKind { HOME, FREE }

/**
 * Your car's last parked spot, your own position, and both kinds of zone —
 * home and free — drawn as circles instead of buried as Settings rows you
 * can't see against the street. Nothing about your own position is shared
 * with the other phone — deliberately.
 */
@SuppressLint("MissingPermission")
@Composable
fun MapScreen(stateStore: ParkStateStore, freeZoneStore: FreeZoneStore) {
    val context = LocalContext.current
    var me by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(Unit) {
        // One-shot read while the screen is open; no tracking.
        me = PlayServicesSignals(context).currentLocation()
    }
    val car = stateStore.lastParkLocation
    val parkedAt = stateStore.parkedAtMs.takeIf { it > 0 && stateStore.parked }
        ?.let { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(it)) }

    var homeZone by remember { mutableStateOf(stateStore.homeZone) }
    var freeZones by remember { mutableStateOf(freeZoneStore.all()) }

    var addingKind by remember { mutableStateOf<ZoneKind?>(null) }
    var candidatePoint by remember { mutableStateOf<GeoPoint?>(null) }
    var candidateRadius by remember { mutableFloatStateOf(ZONE_RADIUS_DEFAULT_M.toFloat()) }
    var removalTarget by remember { mutableStateOf<ZoneRef?>(null) }

    val candidateZone = candidatePoint?.let { FreeZone(it.lat, it.lng, candidateRadius.toDouble()) }

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

        Box(Modifier.fillMaxWidth().weight(1f)) {
            MapCanvas(
                car = car,
                me = me,
                homeZone = homeZone,
                freeZones = freeZones,
                candidateZone = candidateZone,
                onMapTap = { point ->
                    if (addingKind != null) {
                        candidatePoint = point
                    } else {
                        removalTarget = zoneHitAt(point, homeZone, freeZones)
                    }
                },
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp, start = 4.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    candidatePoint != null -> ZoneCandidateCard(
                        kind = addingKind ?: ZoneKind.FREE,
                        radiusM = candidateRadius,
                        onRadiusChange = { candidateRadius = it },
                        onCancel = {
                            addingKind = null
                            candidatePoint = null
                        },
                        onConfirm = {
                            candidatePoint?.let { point ->
                                val radius = clampZoneRadius(candidateRadius.toDouble())
                                when (addingKind) {
                                    ZoneKind.HOME -> {
                                        val zone = FreeZone(point.lat, point.lng, radius, "Home")
                                        stateStore.homeZone = zone
                                        homeZone = zone
                                    }
                                    ZoneKind.FREE, null -> {
                                        freeZoneStore.add(FreeZone(point.lat, point.lng, radius))
                                        freeZones = freeZoneStore.all()
                                    }
                                }
                            }
                            addingKind = null
                            candidatePoint = null
                        },
                    )
                    addingKind != null -> ZoneHintCard(
                        text = if (addingKind == ZoneKind.HOME) {
                            "Tap the map to place home"
                        } else {
                            "Tap the map to place the zone"
                        },
                        onCancel = { addingKind = null },
                    )
                    // These float over map tiles, which stay light whatever the
                    // app theme is doing. An OutlinedButton has a transparent
                    // container, so in dark mode its near-white label rendered
                    // straight onto pale streets and vanished. They need a
                    // surface of their own to sit on.
                    else -> Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { addingKind = ZoneKind.HOME },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (homeZone == null) "Set home zone" else "Move home zone") }
                            OutlinedButton(
                                onClick = { addingKind = ZoneKind.FREE },
                                modifier = Modifier.weight(1f),
                            ) { Text("Add free zone") }
                        }
                    }
                }

                if (car != null && addingKind == null && candidatePoint == null) {
                    Button(
                        onClick = { openWalkingDirections(context, car) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text("Walk to car") }
                }
            }
        }

        Text(
            "© OpenStreetMap contributors",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }

    removalTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removalTarget = null },
            title = { Text("Remove this zone?") },
            text = {
                Text(
                    when (target) {
                        ZoneRef.Home ->
                            "This clears your home zone. Parking there will start claiming the permit again."
                        is ZoneRef.Free -> "This free zone will no longer be recognised."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (target) {
                        ZoneRef.Home -> {
                            stateStore.homeZone = null
                            homeZone = null
                        }
                        is ZoneRef.Free -> {
                            freeZoneStore.removeAt(target.index)
                            freeZones = freeZoneStore.all()
                        }
                    }
                    removalTarget = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removalTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ZoneHintCard(text: String, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun ZoneCandidateCard(
    kind: ZoneKind,
    radiusM: Float,
    onRadiusChange: (Float) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                if (kind == ZoneKind.HOME) "Home zone" else "Free zone",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "%.0f m radius".format(radiusM),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Explicit colours: the stock inactive track resolves to a neutral
            // that is invisible against this card's own neutral surface, so the
            // slider rendered as a thumb floating next to a stray dot with no
            // track between them.
            Slider(
                value = radiusM,
                onValueChange = onRadiusChange,
                valueRange = ZONE_RADIUS_MIN_M.toFloat()..ZONE_RADIUS_MAX_M.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurface,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = 0.4f),
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onConfirm) { Text("Confirm") }
            }
        }
    }
}

/**
 * Hands off to whichever maps app is installed for a walking route back to
 * the car — this app deliberately has no in-app navigation, and deliberately
 * avoids a Maps SDK dependency (the API key and billing account that would
 * need). `google.navigation` opens turn-by-turn walking directions directly
 * in Google Maps; `geo:` is the fallback any maps app understands.
 */
private fun openWalkingDirections(context: Context, point: GeoPoint) {
    val primary = Intent(Intent.ACTION_VIEW, Uri.parse(walkingDirectionsUri(point)))
        .setPackage("com.google.android.apps.maps")
    try {
        context.startActivity(primary)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geoFallbackUri(point))))
        } catch (e2: ActivityNotFoundException) {
            // No maps app at all — nothing more to do without building in-app
            // navigation, which is explicitly out of scope.
        }
    }
}
