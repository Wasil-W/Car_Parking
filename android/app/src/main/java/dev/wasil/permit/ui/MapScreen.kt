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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wasil.permit.ui.theme.HandoffShapes
import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.FreeZoneStore
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.ParkStateStore
import dev.wasil.permit.parking.distanceMeters
import dev.wasil.permit.parking.android.ParkActionReceiver
import dev.wasil.permit.parking.android.SharedSync
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.ZoneResolver
import dev.wasil.permit.parking.android.PlayServicesSignals
import dev.wasil.permit.parking.android.reverseGeocodeAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

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
fun MapScreen(
    stateStore: ParkStateStore,
    freeZoneStore: FreeZoneStore,
    tariffAreas: List<TariffArea>,
    // A factory, not an instance: the resolver closes over the home zone and
    // the free-zone list, both of which this very screen can change.
    zoneResolver: () -> ZoneResolver,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    // Tapping an existing zone opens the rename/remove dialog below.
    var zoneDialogTarget by remember { mutableStateOf<ZoneRef?>(null) }

    var showTariff by remember { mutableStateOf(false) }
    var selectedArea by remember { mutableStateOf<TariffArea?>(null) }

    // Moving the car pin: tap the marker, tap the true spot, confirm.
    var movingPin by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<CorrectionResult.Ok?>(null) }
    var tooFarM by remember { mutableStateOf<Double?>(null) }
    var flipToConfirm by remember { mutableStateOf<Flip?>(null) }

    val candidateZone = candidatePoint?.let { FreeZone(it.lat, it.lng, candidateRadius.toDouble()) }

    // Off by default: the car's own area is named in a card below and outlined
    // on the map, without 29 filled polygons burying the zone circles. The
    // button reveals the other 28, and pulls the zoom out far enough for their
    // boundaries to exist on screen at all.
    val visibleTariffAreas = if (showTariff) tariffAreas else emptyList()
    val carArea = car?.let { tariffAreaAt(it, tariffAreas) }
    val highlightArea = selectedArea ?: carArea

    // Bumped whenever the overlay is switched on, to pull the map out to a
    // zoom where boundaries actually exist on screen. Amsterdam's tariff areas
    // are neighbourhood-sized — the one containing Waterlooplein is 3.1 km by
    // 2.7 km — so at parking zoom the whole viewport sits inside a single
    // polygon and the overlay looks like it did nothing at all.
    var overviewRequest by remember { mutableIntStateOf(0) }

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
                tariffAreas = visibleTariffAreas,
                highlightArea = highlightArea,
                ghostCar = pending?.point,
                overviewRequest = overviewRequest,
                onCarTap = {
                    if (car != null && stateStore.parked) {
                        movingPin = true
                        selectedArea = null
                    }
                },
                // Placing modes come first and win outright: while a candidate
                // is being placed or the pin moved, every tap is a placement.
                // Below them, mapHitAt is the single precedence rule.
                onMapTap = { point ->
                    when {
                        addingKind != null -> candidatePoint = point
                        movingPin && car != null -> {
                            when (val r = correctionFor(car, point, stateStore.parkedOutside, zoneResolver())) {
                                is CorrectionResult.TooFar -> {
                                    tooFarM = r.distanceM
                                    pending = null
                                }
                                is CorrectionResult.Ok -> {
                                    pending = r
                                    tooFarM = null
                                }
                            }
                        }
                        else -> when (val hit = mapHitAt(point, homeZone, freeZones, visibleTariffAreas)) {
                            is MapHit.Zone -> zoneDialogTarget = hit.ref
                            is MapHit.Tariff -> selectedArea = hit.area
                            null -> selectedArea = null
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().clip(HandoffShapes.Card),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp, start = 4.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    pending != null -> MovePinCard(
                        distanceM = car?.let { distanceMeters(it, pending!!.point) } ?: 0.0,
                        flip = pending!!.flip,
                        onCancel = {
                            pending = null
                            movingPin = false
                        },
                        onConfirm = {
                            val result = pending!!
                            stateStore.lastParkLocation = result.point
                            stateStore.lastZoneCode = result.zoneCode
                            stateStore.parkedOutside = result.parkedOutside
                            // The other phone learns the corrected position via
                            // the path that already exists — SyncStateWorker
                            // reads exactly these three fields.
                            SharedSync.requestSync(context)
                            flipToConfirm = result.flip.takeIf { it != Flip.NONE }
                            pending = null
                            movingPin = false
                        },
                    )
                    movingPin -> ZoneHintCard(
                        text = tooFarM?.let { "%.0f m away — too far to be a correction".format(it) }
                            ?: "Tap where the car really is",
                        onCancel = {
                            movingPin = false
                            tooFarM = null
                        },
                    )
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
                                // Save immediately with the coordinates as a
                                // placeholder name — geocoding must never
                                // delay or risk the save itself — then
                                // upgrade it to a real address in the
                                // background once (if) one comes back.
                                val placeholder = formatCoordinates(point.lat, point.lng)
                                when (addingKind) {
                                    ZoneKind.HOME -> {
                                        val zone = FreeZone(point.lat, point.lng, radius, placeholder)
                                        stateStore.homeZone = zone
                                        homeZone = zone
                                        scope.launch {
                                            val address = reverseGeocodeAddress(context, point) ?: return@launch
                                            // Only apply if nothing else changed the home zone meanwhile.
                                            if (stateStore.homeZone == zone) {
                                                val named = zone.copy(label = address)
                                                stateStore.homeZone = named
                                                homeZone = named
                                            }
                                        }
                                    }
                                    ZoneKind.FREE, null -> {
                                        val zone = FreeZone(point.lat, point.lng, radius, placeholder)
                                        freeZoneStore.add(zone)
                                        freeZones = freeZoneStore.all()
                                        val index = freeZones.lastIndex
                                        scope.launch {
                                            val address = reverseGeocodeAddress(context, point) ?: return@launch
                                            // Only apply if that slot still holds this same placeholder zone.
                                            val current = freeZoneStore.all()
                                            if (index in current.indices && current[index] == zone) {
                                                freeZoneStore.updateLabel(index, address)
                                                freeZones = freeZoneStore.all()
                                            }
                                        }
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
                        shape = HandoffShapes.Control,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
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
                            // Its own row rather than a third button above:
                            // three of these labels side by side on a phone
                            // wrap to two lines each and stop being readable.
                            OutlinedButton(
                                onClick = {
                                    showTariff = !showTariff
                                    if (showTariff) overviewRequest++ else selectedArea = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = tariffAreas.isNotEmpty(),
                            ) {
                                Text(
                                    if (showTariff) "Hide tariff areas" else "Show tariff areas",
                                )
                            }
                        }
                    }
                }

                // Shown for the tapped area, and — with no tap — for whichever
                // area the car is standing in. The outline alone cannot carry
                // this: a 3 km boundary is never on screen at parking zoom, so
                // "why did it claim here?" is answered by the label, not the
                // geometry. That was the wrong assumption in the v0.5.0 design.
                if (addingKind == null && candidatePoint == null && !movingPin) {
                    highlightArea?.let { area ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = HandoffShapes.Control,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(
                                    if (selectedArea == null) {
                                        "Your car is in ${area.code}"
                                    } else {
                                        area.code
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    tariffSummary(area),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (car != null && addingKind == null && candidatePoint == null && !movingPin) {
                    Button(
                        onClick = { openWalkingDirections(context, car) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = HandoffShapes.Control,
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

    zoneDialogTarget?.let { target ->
        val currentZone = when (target) {
            ZoneRef.Home -> homeZone
            is ZoneRef.Free -> freeZones.getOrNull(target.index)
        }
        if (currentZone == null) {
            // Removed some other way (e.g. Settings) between the tap and now.
            zoneDialogTarget = null
        } else {
            ZoneEditDialog(
                target = target,
                zone = currentZone,
                onDismiss = { zoneDialogTarget = null },
                onSave = { newLabel ->
                    when (target) {
                        ZoneRef.Home -> {
                            val updated = currentZone.copy(label = newLabel)
                            stateStore.homeZone = updated
                            homeZone = updated
                        }
                        is ZoneRef.Free -> {
                            freeZoneStore.updateLabel(target.index, newLabel)
                            freeZones = freeZoneStore.all()
                        }
                    }
                    zoneDialogTarget = null
                },
                onRemove = {
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
                    zoneDialogTarget = null
                },
            )
        }
    }

    // Never automatic. Detection auto-switches because the app is the only
    // party that noticed anything happened; a correction is the opposite — the
    // user is standing next to the car and has just told the app something it
    // did not know. Asking costs one tap and removes the whole class of "it
    // did something because I mis-tapped".
    flipToConfirm?.let { flip ->
        AlertDialog(
            onDismissRequest = { flipToConfirm = null },
            title = {
                Text(if (flip == Flip.NOW_PAID) "This spot is paid parking" else "This spot is free")
            },
            text = {
                Text(
                    if (flip == Flip.NOW_PAID) {
                        "The corrected position is in a paid area. Claim the permit for your car?"
                    } else {
                        "The corrected position is not in a paid area. Hand the permit back?"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ParkActionReceiver.perform(
                        context,
                        if (flip == Flip.NOW_PAID) {
                            ParkActionReceiver.ACTION_CLAIM
                        } else {
                            ParkActionReceiver.ACTION_GIVE_BACK
                        },
                    )
                    flipToConfirm = null
                }) { Text(if (flip == Flip.NOW_PAID) "Claim" else "Hand back") }
            },
            dismissButton = {
                TextButton(onClick = { flipToConfirm = null }) { Text("Not now") }
            },
        )
    }
}

/**
 * Confirms a pin correction, and says plainly when it would change the answer
 * to "is this paid parking?" — because that answer is what decides whether the
 * permit should be here at all.
 */
@Composable
private fun MovePinCard(
    distanceM: Double,
    flip: Flip,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = HandoffShapes.Control,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Move the car pin", style = MaterialTheme.typography.bodyLarge)
            Text(
                "%.0f m from where it was detected".format(distanceM),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (flip != Flip.NONE) {
                Text(
                    when (flip) {
                        Flip.NOW_PAID -> "This spot is paid parking. You will be asked about the permit."
                        Flip.NOW_FREE -> "This spot is not paid parking. You will be asked about the permit."
                        Flip.NONE -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onConfirm) { Text("Confirm") }
            }
        }
    }
}

/**
 * Rename or remove a tapped zone. A hand-typed name (e.g. "Mum's street")
 * beats any geocoded address, so this is offered right where the zone lives
 * on the map rather than buried in Settings. Saving a blank name falls back
 * to the zone's coordinates rather than leaving it empty.
 */
@Composable
private fun ZoneEditDialog(
    target: ZoneRef,
    zone: FreeZone,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var name by remember(target) { mutableStateOf(zone.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target == ZoneRef.Home) "Home zone" else "Free zone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    when (target) {
                        ZoneRef.Home ->
                            "Removing clears your home zone — parking there will start claiming the permit again."
                        is ZoneRef.Free -> "Removing means this spot is no longer recognised as free."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(name.trim().ifBlank { formatCoordinates(zone.lat, zone.lng) })
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
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
