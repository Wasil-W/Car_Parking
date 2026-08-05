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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
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
import dev.wasil.permit.parking.zones.tariffNow
import dev.wasil.permit.parking.route.WalkRoute
import dev.wasil.permit.parking.route.fetchWalkRoute
import dev.wasil.permit.parking.route.walkSummary
import dev.wasil.permit.parking.zones.ZoneResolver
import dev.wasil.permit.parking.android.reverseGeocodeAddress
import dev.wasil.permit.parking.android.reverseGeocodePlace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Which kind of zone the map is currently placing a candidate point for. */
private enum class ZoneKind { HOME, FREE }

/** Mockup geometry: the control stack sits 10dp in from the right edge. */
private val CHROME_SIDE_INSET = 10.dp

/**
 * How far the controls and the walk pill float above the bottom of the map.
 *
 * The map already ends at the top of the tab bar — it is inside the Scaffold's
 * content padding — so this is clearance from that, not from the system bar.
 * 28dp rather than 12dp because the OpenStreetMap credit sits below it, and the
 * credit is not optional.
 */
private val CHROME_BOTTOM_INSET = 28.dp

/** Long enough to read, short enough not to become part of the furniture. */
private const val NOTICE_MS = 4000L

/**
 * Said when the phone cannot tell us where it is. It states the failure rather
 * than moving the map somewhere plausible — the rule this project pays for.
 */
private const val NO_POSITION = "Couldn't read your position"

/**
 * The line under "Map" that says what the pin — or the absence of one — means.
 *
 * The parked-without-a-position case is the one worth spelling out. It used to
 * read "No parked location recorded yet", which was true about the database and
 * false about the app: the app had decided you were parked, was acting on that,
 * and was telling you nothing had happened. Being contradicted by your own
 * phone is worse than being told the awkward thing, so now it says the awkward
 * thing. A screen may be short of an answer; it may not disagree with the rest
 * of the app about what it believes.
 *
 * Pure and separate from the composable so the wording is unit-testable — the
 * contradiction this fixes was a wording bug, and wording bugs are exactly what
 * a test can hold still.
 */
internal fun carPositionLine(
    car: GeoPoint?,
    parked: Boolean,
    parkedAtText: String?,
    driving: Boolean = false,
): String = when {
    // While the car's Bluetooth is connected the car is wherever you are, so a
    // pin would point at the spot you just left. The position is still held —
    // it simply is not the answer to "where is the car" right now.
    driving -> "You're in the car."
    car != null && parkedAtText != null -> "Car parked $parkedAtText."
    car != null -> "Last known car position."
    parked -> "Parked — but the location is unknown."
    else -> "No parked location recorded yet."
}

/**
 * Your car's last parked spot, your own position, and both kinds of zone —
 * home and free — drawn as circles instead of buried as Settings rows you
 * can't see against the street. Nothing about your own position is shared
 * with the other phone — deliberately.
 *
 * The map is the screen. Everything else floats over it (see `MapChrome.kt`)
 * rather than taking a row of layout of its own; the one exception is the
 * placing flow — the hint and candidate cards — which is a mode rather than
 * chrome, and keeps the bottom of the screen while it is running.
 */
@SuppressLint("MissingPermission")
@Composable
fun MapScreen(
    stateStore: ParkStateStore,
    freeZoneStore: FreeZoneStore,
    me: GeoPoint?,
    tariffAreas: List<TariffArea>,
    // A factory, not an instance: the resolver closes over the home zone and
    // the free-zone list, both of which this very screen can change.
    zoneResolver: () -> ZoneResolver,
    routeClient: OkHttpClient,
    /**
     * Re-reads where the phone is, right now, and returns null if it cannot.
     *
     * Deliberately a fresh read rather than the one-shot [me] taken at app
     * start: "locate me" exists because you have panned away, and by then that
     * first fix can be an hour old and a mile off. The caller owns [me], so
     * this both answers here and updates it there.
     */
    onLocate: suspend () -> GeoPoint?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Held through the drive, hidden during it. Deleting it was how a failed
    // park could leave no car location at all — see CarBluetoothReceiver.
    val driving = stateStore.carLinkConnected
    val storedCar = stateStore.lastParkLocation
    val car = storedCar.takeUnless { driving }
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
    var selectedHit by remember { mutableStateOf<TariffHit?>(null) }
    // The point inside the highlighted part to ask the geocoder about: the tap
    // itself, or the car. A ring's centroid can fall outside a concave shape,
    // and these shapes are very concave.
    var namePoint by remember { mutableStateOf<GeoPoint?>(null) }
    var place by remember { mutableStateOf<PlaceLabel?>(null) }
    // Distinguishes "still looking" from "looked and found nothing". Without
    // it the code was shown as a placeholder and then swapped for the name,
    // which read as a glitch — Wasil: "there is still a small frame where the
    // T11V is visible before it changes".
    var placeResolved by remember { mutableStateOf(false) }

    // Moving the car pin: tap the marker, tap the true spot, confirm.
    var movingPin by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<CorrectionResult.Ok?>(null) }
    var tooFarM by remember { mutableStateOf<Double?>(null) }
    var flipToConfirm by remember { mutableStateOf<Flip?>(null) }

    val candidateZone = candidatePoint?.let { FreeZone(it.lat, it.lng, candidateRadius.toDouble()) }

    // Off by default: the car's own area is named in the header and outlined on
    // the map, without 29 filled polygons burying the zone circles. The button
    // reveals the other 28, and pulls the zoom out far enough for their
    // boundaries to exist on screen at all.
    val visibleTariffAreas = if (showTariff) tariffAreas else emptyList()
    val carHit = car?.let { tariffHitAt(it, tariffAreas) }
    val highlight = selectedHit ?: carHit
    val highlightArea = highlight?.area

    // Bumped whenever the overlay is switched on, to pull the map out to a
    // zoom where boundaries actually exist on screen. Amsterdam's tariff areas
    // are neighbourhood-sized — the one containing Waterlooplein is 3.1 km by
    // 2.7 km — so at parking zoom the whole viewport sits inside a single
    // polygon and the overlay looks like it did nothing at all.
    var overviewRequest by remember { mutableIntStateOf(0) }

    // The two camera controls the map never had. Counters, not booleans: the
    // second tap has to move the map again even though nothing else changed.
    var locateRequest by remember { mutableIntStateOf(0) }
    var frameRequest by remember { mutableIntStateOf(0) }
    var locating by remember { mutableStateOf(false) }

    // The one thing these controls can fail at, said out loud and then gone.
    // A failed position read means "we do not know", never "you are here".
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }

    // Recomputed whenever the highlighted area changes: the header answers
    // "what does this cost right now", not "what is the timetable".
    val clockNow = remember(highlight) { java.util.Calendar.getInstance() }
    val dayIndex = (clockNow.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
    val minuteOfDay = clockNow.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
        clockNow.get(java.util.Calendar.MINUTE)

    // Walking route, drawn in the app rather than by handing off to Google
    // Maps. Cleared whenever the car moves so a stale line can never point at
    // where the car used to be.
    var route by remember { mutableStateOf<WalkRoute?>(null) }
    var routing by remember { mutableStateOf(false) }
    LaunchedEffect(car) { route = null }

    // Codes like "T14B" mean nothing to anyone. The tariff file has no place
    // names in it at all, so the name is geocoded from a point inside the
    // highlighted part. Falls back to the code when the lookup fails or there
    // is no network — a code beats a blank.
    val lookupPoint = namePoint.takeIf { selectedHit != null } ?: car
    LaunchedEffect(highlight, lookupPoint) {
        place = null
        placeResolved = false
        val at = lookupPoint
        if (at == null || highlight == null) {
            placeResolved = true
            return@LaunchedEffect
        }
        place = reverseGeocodePlace(context, at)
        placeResolved = true
    }

    // While a zone is being placed or the pin moved, every tap is a placement
    // and the bottom of the screen belongs to that flow. The floating controls
    // stand down rather than compete with it — which is what the old layout did
    // too, by swapping the button card for the hint card in the same slot.
    val placing = addingKind != null || candidatePoint != null || movingPin || pending != null

    Box(Modifier.fillMaxSize()) {
        MapCanvas(
            car = car,
            me = me,
            homeZone = homeZone,
            freeZones = freeZones,
            candidateZone = candidateZone,
            tariffAreas = visibleTariffAreas,
            highlightRing = highlight?.ring,
            ghostCar = pending?.point,
            walkRoute = route?.points.orEmpty(),
            overviewRequest = overviewRequest,
            locateRequest = locateRequest,
            frameRequest = frameRequest,
            onCarTap = {
                if (car != null && stateStore.parked) {
                    movingPin = true
                    selectedHit = null
                }
            },
            // Placing modes come first and win outright: while a candidate
            // is being placed or the pin moved, every tap is a placement.
            // Below them, mapHitAt is the single precedence rule.
            onMapTap = { point ->
                when {
                    addingKind != null -> candidatePoint = point
                    movingPin && car != null -> {
                        // Anchored to where detection put the car, not to
                        // the current pin: measuring from the pin let a
                        // confirmed move become the origin of the next one.
                        val anchor = stateStore.detectedParkLocation ?: car
                        when (val r = correctionFor(anchor, point, stateStore.parkedOutside, zoneResolver())) {
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
                        is MapHit.Tariff -> {
                            selectedHit = hit.hit
                            namePoint = point
                        }
                        null -> selectedHit = null
                    }
                }
            },
            // Edge to edge, square corners. The inset rounded card spent a
            // border of screen announcing "this is a card", which is the one
            // thing about a full-screen map nobody needs telling.
            //
            // clipToBounds is load-bearing, not tidiness. A View hosted by
            // AndroidView is not clipped to its composable's bounds, and
            // osmdroid draws well past them — with the old rounded `clip` gone,
            // tiles rendered up behind the status bar and put white system
            // icons on pale streets. Caught on screen; nothing else would have.
            modifier = Modifier.fillMaxSize().clipToBounds(),
        )

        MapHeaderOverlay(
            title = "Map",
            subtitle = carPositionLine(car, stateStore.parked, parkedAt, driving),
            modifier = Modifier.align(Alignment.TopStart),
            // Unchanged from v0.6.3 apart from its backing. The chip works, it
            // is recent, and it was never what made this screen crowded.
            chip = highlightArea?.let { area ->
                @Composable {
                    TariffChip(
                        area = area,
                        place = place,
                        placeResolved = placeResolved,
                        dayIndex = dayIndex,
                        minuteOfDay = minuteOfDay,
                    )
                }
            },
        )

        // Required by the tile licence, so it survives the move onto the map
        // rather than being dropped with the layout row it used to own.
        MapAttribution(
            Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 6.dp),
        )

        if (placing) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // Same clearance as the floating controls, and for the same
                    // reason: at 12dp these cards sat on top of the
                    // OpenStreetMap credit, which the tile licence does not let
                    // us hide for the duration of a mode. Seen on screen.
                    .padding(bottom = CHROME_BOTTOM_INSET, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    pending != null -> MovePinCard(
                        distanceM = (stateStore.detectedParkLocation ?: car)
                            ?.let { distanceMeters(it, pending!!.point) } ?: 0.0,
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
                }
            }
        } else {
            MapControlStack(
                homeZoneSet = homeZone != null,
                tariffShowing = showTariff,
                tariffEnabled = tariffAreas.isNotEmpty(),
                locating = locating,
                onLocate = {
                    // Guarded rather than queued: two reads in flight would
                    // race to be the one that moves the map.
                    if (!locating) {
                        locating = true
                        scope.launch {
                            val fix = onLocate()
                            locating = false
                            // A failed read is "we do not know", not "you are
                            // still where you were an hour ago". Nothing moves.
                            if (fix != null) locateRequest++ else notice = NO_POSITION
                        }
                    }
                },
                onFrame = { frameRequest++ },
                // Unchanged from the old text button, zoom-out included.
                onToggleTariff = {
                    showTariff = !showTariff
                    if (showTariff) overviewRequest++ else selectedHit = null
                },
                onSetHomeZone = { addingKind = ZoneKind.HOME },
                onAddFreeZone = { addingKind = ZoneKind.FREE },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = CHROME_SIDE_INSET, bottom = CHROME_BOTTOM_INSET),
            )

            if (car != null) {
                val here = me
                WalkPill(
                    label = walkPillText(routing, route?.let(::walkSummary), here != null),
                    enabled = !routing,
                    onClick = {
                        when {
                            route != null -> route = null
                            here == null -> openWalkingDirections(context, car)
                            else -> scope.launch {
                                routing = true
                                route = fetchWalkRoute(routeClient, here, car)
                                routing = false
                            }
                        }
                    },
                    // Symmetric side padding wide enough for the control stack,
                    // so the pill stays centred on the screen and still cannot
                    // reach the circles however long its label gets.
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = CHROME_BOTTOM_INSET, start = 60.dp, end = 60.dp),
                )
            }
        }

        notice?.let {
            MapNotice(
                it,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = CHROME_BOTTOM_INSET + 52.dp, start = 16.dp, end = 16.dp),
            )
        }
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
 * What the highlighted area is called and what it costs right now.
 *
 * Lifted out of the header row unchanged when the header became an overlay —
 * same content, same alignment, same blank-while-resolving rule. The only
 * difference is the container, which is now translucent so the map underneath
 * is still a map rather than a strip of colour behind a card.
 */
@Composable
private fun TariffChip(
    area: TariffArea,
    place: PlaceLabel?,
    placeResolved: Boolean,
    dayIndex: Int,
    minuteOfDay: Int,
) {
    Card(
        shape = HandoffShapes.Control,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = OVER_TILES_ALPHA),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.End,
        ) {
            // While the lookup is in flight this stays blank rather than
            // showing the code and swapping it a moment later. The code appears
            // only once we know no name is coming.
            val heading = place?.district ?: area.code.takeIf { placeResolved }
            if (heading != null) {
                Text(heading, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End)
            }
            place?.detail?.let { street ->
                Text(
                    street,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
            Text(
                tariffNowText(tariffNow(area.windows, dayIndex, minuteOfDay), minuteOfDay),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
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
