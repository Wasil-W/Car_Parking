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
import dev.wasil.permit.parking.areaSizeText
import dev.wasil.permit.parking.areaSqKm
import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.ZonePolygon
import dev.wasil.permit.parking.facilities.Facility
import dev.wasil.permit.parking.facilities.FacilityRegistry
import dev.wasil.permit.parking.zones.ZoneRegistry
import dev.wasil.permit.parking.route.WalkRoute
import dev.wasil.permit.parking.route.fetchWalkRoute
import dev.wasil.permit.parking.route.walkSummary
import dev.wasil.permit.parking.zones.ZoneResolver
import dev.wasil.permit.parking.android.placeLabelAt
import dev.wasil.permit.parking.android.reverseGeocodeAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

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
    // "Car parked" and a full stop cost eight characters that pushed the time
    // onto a third line and cut the date in half — Wasil: "why the time gets
    // shoved, poor data you could have kept it." The word "car" is carried by
    // the pin it sits next to.
    car != null && parkedAtText != null -> "Parked $parkedAtText"
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
    /**
     * Amsterdam's neighbourhoods, which is what a free zone is made of since
     * v0.6.8 — Wasil, 2026-08-08, looking at the council's map of Molenwijk:
     * *"we can put those for the free zones, with outline."*
     *
     * Null when the bundled asset is missing, and null is a working state
     * rather than a broken one: the placing flow then offers the circle it
     * always did, which is also what happens anywhere outside the city.
     */
    zoneRegistry: ZoneRegistry?,
    /**
     * The city's garages and P+R sites, or null when the asset is unreadable —
     * in which case the layer never appears and its menu row is disabled rather
     * than hidden, so the menu is the same shape on every install.
     */
    facilityRegistry: FacilityRegistry?,
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

    // How an area-backed zone gets its edges, everywhere on this screen: drawn,
    // hit-tested and resolved from the one bundle, so the outline you tap is the
    // outline the claim decision uses.
    val areaShape: (String) -> List<ZonePolygon>? =
        remember(zoneRegistry) { { name -> zoneRegistry?.shapeOf(name) } }

    var addingKind by remember { mutableStateOf<ZoneKind?>(null) }
    var candidatePoint by remember { mutableStateOf<GeoPoint?>(null) }
    var candidateRadius by remember { mutableFloatStateOf(ZONE_RADIUS_DEFAULT_M.toFloat()) }
    // The neighbourhood under the candidate point, when there is one and the
    // zone is a free one. Home zones never take an area — see ZoneResolver.
    var candidateBuurt by remember { mutableStateOf<String?>(null) }
    // Tapping an existing zone opens the rename/remove dialog below.
    var zoneDialogTarget by remember { mutableStateOf<ZoneRef?>(null) }

    // Every zone you have, opened from the overflow menu. The list is the way
    // back to a zone you cannot see; before it, finding one meant remembering
    // where you put it and panning there.
    var zoneListOpen by remember { mutableStateOf(false) }

    var showTariff by remember { mutableStateOf(false) }
    // Off by default, like the tariff layer and for the same reason. Unlike it,
    // switching this on does NOT move the camera: tariff areas are up to 3 km
    // across so at parking zoom you are inside one and see nothing, but
    // facilities are points and only useful near you. Yanking the map away from
    // where you are standing is the fault v0.6.5 removed from the other layer.
    var showFacilities by remember { mutableStateOf(false) }
    var selectedFacility by remember { mutableStateOf<Facility?>(null) }
    var selectedHit by remember { mutableStateOf<TariffHit?>(null) }
    // The header chip, showing the whole week instead of just now. One control
    // where v0.6.6 had two — see TariffChip for why.
    var weekExpanded by remember { mutableStateOf(false) }
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

    val candidateBuurtInUse = candidateBuurt?.takeIf { addingKind != ZoneKind.HOME }
    val candidateZone = candidatePoint?.let {
        FreeZone(it.lat, it.lng, candidateRadius.toDouble(), buurt = candidateBuurtInUse)
    }
    // The size of what is being offered, worked out once. A whole neighbourhood
    // marked free is a strong claim — the app will never take the permit
    // anywhere inside it — and buurten run from a few streets to most of a
    // district, so the number belongs in front of the person choosing rather
    // than in a document nobody reads.
    val candidateAreaSize = remember(candidateBuurtInUse) {
        candidateBuurtInUse?.let { name -> areaShape(name)?.let { areaSizeText(areaSqKm(it)) } }
    }

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
    var carRequest by remember { mutableIntStateOf(0) }
    // "Show me that one", from a row in the zone list. Separate from the focus
    // cycle because it names a place rather than a category, and because the
    // whole reason for tapping the row is that the zone is off screen.
    var spotRequest by remember { mutableIntStateOf(0) }
    var spotTarget by remember { mutableStateOf<GeoPoint?>(null) }
    // Where the *next* tap of the focus button goes. Starts at BOTH because
    // that is what the screen already does on open, so the first tap moves you
    // on rather than repeating what you are looking at.
    var focus by remember { mutableStateOf(MapFocus.BOTH) }
    // Corrected for what is actually on the map, every pass — the cycle only
    // advances on a tap, so a focus chosen while a car pin existed would
    // otherwise keep offering to frame it after the pin was cleared.
    val shownFocus = focusOrFallback(focus, car != null, me != null)
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

    // The header answers "what does this cost right now", not "what is the
    // timetable" — so the clock behind it has to keep moving. It used to be
    // `remember(highlight) { Calendar.getInstance() }`, and `highlight`
    // recomputes *equal* for a car that is not moving, so it was read once when
    // the tab opened and never again. See [rememberMinuteClock].
    val clockNow by rememberMinuteClock()
    val dayIndex = clockNow.handoffDayIndex()
    val minuteOfDay = clockNow.minuteOfDay()

    // Walking route, drawn in the app rather than by handing off to Google
    // Maps. Cleared whenever the car moves so a stale line can never point at
    // where the car used to be.
    var route by remember { mutableStateOf<WalkRoute?>(null) }
    var routing by remember { mutableStateOf(false) }
    LaunchedEffect(car) { route = null }

    // Codes like "T14B" mean nothing to anyone. The tariff file has no place
    // names in it at all, so the name comes from a point inside the highlighted
    // part — now Amsterdam's own buurt layer rather than the geocoder, which is
    // why it no longer needs a network or a moment to arrive. Falls back to the
    // code when even that has nothing: a code beats a blank.
    val lookupPoint = namePoint.takeIf { selectedHit != null } ?: car
    LaunchedEffect(highlight, lookupPoint) {
        place = null
        placeResolved = false
        val at = lookupPoint
        if (at == null || highlight == null) {
            placeResolved = true
            return@LaunchedEffect
        }
        place = placeLabelAt(context, at)
        placeResolved = true
    }

    // Folded shut when there is no area left to have a week. Retargeting from
    // one area to another keeps it open, deliberately — comparing two spots is
    // exactly the reason to have it open — but a chip that vanishes and returns
    // should return the size it started.
    LaunchedEffect(highlightArea == null) {
        if (highlightArea == null) weekExpanded = false
    }

    // While a zone is being placed or the pin moved, every tap is a placement
    // and the bottom of the screen belongs to that flow. The floating controls
    // stand down rather than compete with it — which is what the old layout did
    // too, by swapping the button card for the hint card in the same slot.
    val placing = addingKind != null || candidatePoint != null || movingPin || pending != null

    /**
     * Drops a candidate, and names the neighbourhood it landed in.
     *
     * The lookup is the whole free-zone flow: tap, and the app already knows the
     * area is called Molenwijk and can offer it. It is a point-in-polygon test
     * against a bundled asset, so it is instant and offline — there is nothing
     * to wait for and no failure to handle beyond "no buurt here", which is what
     * being outside Amsterdam looks like.
     */
    val placeAt: (GeoPoint) -> Unit = { point ->
        candidatePoint = point
        candidateBuurt = zoneRegistry?.resolve(LatLng(point.lat, point.lng))?.neighbourhood
    }

    Box(Modifier.fillMaxSize()) {
        MapCanvas(
            car = car,
            me = me,
            homeZone = homeZone,
            freeZones = freeZones,
            candidateZone = candidateZone,
            areaShape = areaShape,
            tariffAreas = visibleTariffAreas,
            highlightRing = highlight?.ring,
            // Suppressed entirely while the pin is being corrected. That flow
            // asks you to tap the true spot on the map, and 37 tappable plates
            // over it would be 37 ways to miss.
            facilities = if (showFacilities && !movingPin) {
                facilityRegistry?.facilities.orEmpty()
            } else {
                emptyList()
            },
            onFacilityTap = { selectedFacility = it },
            ghostCar = pending?.point,
            walkRoute = route?.points.orEmpty(),
            overviewRequest = overviewRequest,
            locateRequest = locateRequest,
            frameRequest = frameRequest,
            carRequest = carRequest,
            spotRequest = spotRequest,
            spot = spotTarget,
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
                    addingKind != null -> placeAt(point)
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
                    // `visibleTariffAreas` — the drawn ones — which reverses
                    // v0.6.8, and the reversal needs its reason on the record
                    // because the thing it reverses was a real fix.
                    //
                    // v0.6.8 matched against *all* areas because matching the
                    // drawn ones meant the tap did nothing whenever the overlay
                    // was off, and the overlay is off by default: the week
                    // shipped in v0.6.6 and was unreachable without first
                    // finding the layers button.
                    //
                    // That reason has expired. The chip now follows
                    // `selectedHit ?: carHit`, so the area you are parked in is
                    // named and expandable with no tap and no layer at all —
                    // which is the case the v0.6.8 fix existed to rescue. What
                    // was left was a map where every tap on bare ground silently
                    // re-pointed the header at some neighbouring area, with
                    // nothing drawn to say which one had been hit. Wasil,
                    // 2026-08-10: *"make it so that zones are not pressable
                    // (only zone active is current zone) until layers is
                    // active."*
                    //
                    // So selection is now available exactly when the thing being
                    // selected is on screen. Zones are unaffected — they are
                    // always drawn, so they stay tappable either way.
                    else -> when (
                        val hit =
                            mapHitAt(point, homeZone, freeZones, visibleTariffAreas, areaShape)
                    ) {
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
            // Widened only when the chip will actually become a panel. Asking
            // "is the week open and is an area selected" was not the same
            // question, and for the three round-the-clock areas the two
            // answers differed — see [tariffChipOpens].
            chipExpanded = weekExpanded && highlightArea != null &&
                tariffChipOpens(highlightArea, dayIndex, minuteOfDay),
            // The chip is now both halves of what v0.6.6 shipped as two cards:
            // the live rate, and the whole week behind a chevron. The street
            // name that used to sit between them is gone — "in the small
            // timetable i see the streetname of where i press (unnecessary for
            // now)".
            chip = highlightArea?.let { area ->
                @Composable {
                    TariffChip(
                        area = area,
                        placeName = place?.district,
                        // Resolved for the point that was actually tapped (see
                        // `namePoint`), so selecting a different section names
                        // that section's own neighbourhood — "for every section
                        // their own thing ofcourse".
                        placeDetail = place?.detail,
                        placeResolved = placeResolved,
                        dayIndex = dayIndex,
                        minuteOfDay = minuteOfDay,
                        expanded = weekExpanded,
                        onToggle = { weekExpanded = !weekExpanded },
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
                        areaName = candidateBuurtInUse,
                        areaSize = candidateAreaSize,
                        // Both null-checked: an offer that cannot work is worse
                        // than no offer, and "we do not know where you are" is
                        // a state this app is careful never to paper over.
                        onUseMyPosition = me?.let { { placeAt(it) } },
                        onUseCarSpot = storedCar?.let { { placeAt(it) } },
                        onCancel = {
                            addingKind = null
                            candidatePoint = null
                            candidateBuurt = null
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
                                    // A free zone is a neighbourhood or it is
                                    // nothing. Already named by the city, so no
                                    // geocode is wanted: "Molenwijk" is the
                                    // answer, and a street address would be a
                                    // worse one for a whole neighbourhood.
                                    ZoneKind.FREE, null -> candidateBuurtInUse?.let { area ->
                                        freeZoneStore.add(
                                            FreeZone(point.lat, point.lng, radius, label = area, buurt = area),
                                        )
                                        freeZones = freeZoneStore.all()
                                    }
                                }
                            }
                            addingKind = null
                            candidatePoint = null
                            candidateBuurt = null
                        },
                    )
                    addingKind != null -> ZonePlaceHintCard(
                        kind = addingKind!!,
                        onUseMyPosition = me?.let { { placeAt(it) } },
                        onUseCarSpot = storedCar?.let { { placeAt(it) } },
                        onCancel = { addingKind = null },
                    )
                }
            }
        } else {
            MapControlStack(
                homeZoneSet = homeZone != null,
                tariffShowing = showTariff,
                tariffEnabled = tariffAreas.isNotEmpty(),
                facilitiesShowing = showFacilities,
                facilitiesEnabled = facilityRegistry != null,
                locating = locating,
                zoneCount = zoneEntries(homeZone, freeZones).size,
                nextFocus = shownFocus,
                onFocus = {
                    // Guarded rather than queued: two position reads in flight
                    // would race to be the one that moves the map.
                    if (!locating) when (shownFocus) {
                        MapFocus.ME -> {
                            locating = true
                            scope.launch {
                                val fix = onLocate()
                                locating = false
                                // A failed read is "we do not know", not "you
                                // are still where you were an hour ago".
                                // Nothing moves, and the focus does not advance
                                // past a step that never happened.
                                if (fix != null) {
                                    locateRequest++
                                    focus = nextFocus(shownFocus, car != null, true)
                                } else {
                                    notice = NO_POSITION
                                }
                            }
                        }
                        MapFocus.BOTH -> {
                            frameRequest++
                            focus = nextFocus(shownFocus, car != null, me != null)
                        }
                        MapFocus.CAR -> {
                            carRequest++
                            focus = nextFocus(shownFocus, car != null, me != null)
                        }
                    }
                },
                // No camera move. Toggling the overlay used to zoom out to
                // OVERVIEW_ZOOM first, which threw away wherever you had
                // navigated to and meant two taps to see anything — reported
                // across two versions: "it goes back to the standard map...
                // then next tap it will work. (dont want that ofc)". The
                // zoom-out was added in v0.5.1 when the map was small and the
                // overlay was invisible at parking zoom; the map is bigger now
                // and gets navigated, so the reset costs more than it gave.
                //
                // The selection no longer follows the overlay either. It used
                // to be cleared here, back when a tap could only select a
                // *drawn* area — with tapping fixed, switching the layer off
                // and having the header snap back to the car's area would throw
                // away a choice the user made without the overlay's help.
                onToggleTariff = {
                    showTariff = !showTariff
                    // Switching the layer off drops the selection back to the
                    // car's own area. The note above used to argue the opposite
                    // — that clearing it threw away a choice the user made —
                    // and that held while any area could be selected at any
                    // time. Now that selecting requires the layer, keeping a
                    // selection you can no longer change or see is the worse
                    // half of the trade: the header would go on naming a place
                    // with nothing on the map pointing at it.
                    if (!showTariff) selectedHit = null
                },
                // Mirrors the tariff rule directly above: switching a layer off
                // drops the selection that only that layer could have made.
                onToggleFacilities = {
                    showFacilities = !showFacilities
                    if (!showFacilities) selectedFacility = null
                },
                onSetHomeZone = { addingKind = ZoneKind.HOME },
                onAddFreeZone = { addingKind = ZoneKind.FREE },
                onOpenZoneList = { zoneListOpen = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = CHROME_SIDE_INSET, bottom = CHROME_BOTTOM_INSET),
            )

            // No longer hidden while an area is selected: the week has moved
            // into the header, so nothing is standing in the pill's place.
            if (car != null) {
                WalkPill(
                    label = walkPillText(routing, route?.let(::walkSummary)),
                    enabled = !routing,
                    // The route starts from where you are standing when you press
                    // it, and that is now asked for at press time rather than
                    // assumed from `me`. `me` is a fix taken when a tab was
                    // opened; by the time you have panned around looking for the
                    // car it is neither fresh nor, if it failed, final. The
                    // hand-off to a maps app survives as what happens when the
                    // phone genuinely cannot say where it is — with the reason
                    // on screen, rather than as a label chosen in advance.
                    onClick = {
                        if (route != null) {
                            route = null
                        } else scope.launch {
                            routing = true
                            val here = onLocate() ?: me
                            if (here == null) {
                                routing = false
                                notice = NO_POSITION
                                openWalkingDirections(context, car)
                            } else {
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
                areaSize = currentZone.buurt
                    ?.let { name -> areaShape(name)?.let { areaSizeText(areaSqKm(it)) } },
                // Both fields land in one write. Resizing is new in v0.6.8 —
                // before it, a zone that turned out to be too small had to be
                // deleted and placed again, which is why the free-zone store
                // grew replaceAt.
                onSave = { newLabel, newRadius ->
                    val updated = currentZone.copy(label = newLabel, radiusM = newRadius)
                    when (target) {
                        ZoneRef.Home -> {
                            stateStore.homeZone = updated
                            homeZone = updated
                        }
                        is ZoneRef.Free -> {
                            freeZoneStore.replaceAt(target.index, updated)
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

    selectedFacility?.let { facility ->
        FacilitySheet(
            facility = facility,
            onOpenPage = { openFacilityPage(context, it) },
            onDismiss = { selectedFacility = null },
        )
    }

    // Every zone you have, and the way back to one you cannot see. Tapping a
    // row does both halves of "find it": it moves the map there *and* opens the
    // editor, because a list that only scrolled would be a second place to
    // manage zones rather than a way back to the first.
    if (zoneListOpen) {
        ZoneListSheet(
            entries = zoneEntries(homeZone, freeZones),
            onPick = { entry ->
                spotTarget = GeoPoint(entry.zone.lat, entry.zone.lng, 0f)
                spotRequest++
                zoneListOpen = false
                zoneDialogTarget = entry.ref
            },
            onAddFreeZone = {
                zoneListOpen = false
                addingKind = ZoneKind.FREE
            },
            onDismiss = { zoneListOpen = false },
            sizeText = { zoneSizeText(it.zone, areaShape) },
        )
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
 * Hands off to whichever maps app is installed for a walking route back to
 * the car — this app deliberately has no in-app navigation, and deliberately
 * avoids a Maps SDK dependency (the API key and billing account that would
 * need). `google.navigation` opens turn-by-turn walking directions directly
 * in Google Maps; `geo:` is the fallback any maps app understands.
 */
/**
 * The council's page for a facility, in whatever browser the phone has.
 *
 * Swallows the no-browser case the same way [openWalkingDirections] swallows a
 * missing maps app — a device with no browser is not a state this app can fix,
 * and a crash on the way out of a parking sheet is worse than a dead button.
 */
private fun openFacilityPage(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        // No browser. Nothing further to offer.
    }
}

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
