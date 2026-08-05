package dev.wasil.permit.ui

import android.graphics.DashPathEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.wasil.permit.R
import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.distanceMeters
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.ZonePolygon
import dev.wasil.permit.ui.theme.LocalHandoffColors
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint as OsmGeoPoint

/**
 * Far enough out that tariff-area boundaries are on screen.
 *
 * The area covering Waterlooplein is 3.1 km by 2.7 km; at the map's normal
 * zoom the entire viewport sits inside one polygon, so the overlay renders a
 * uniform 7% tint and looks broken. Found by looking at it (v0.5.1).
 */
private const val OVERVIEW_ZOOM = 12.5

/**
 * Below this the car and your own position are close enough that fitting a box
 * around them would zoom absurdly far in — at which point centring on one of
 * them at the normal zoom is what you actually want.
 */
private const val MIN_SPREAD_M = 40.0

/** Breathing room around the fitted box, so neither pin sits on the edge. */
private const val FRAME_PADDING_PX = 150

/** Amsterdam centre, used only when there is nothing else to show. */
private val FALLBACK = GeoPoint(52.3702, 4.8952, 0f)

/**
 * The map, shared by the preview card on the main screen and the full map tab,
 * so both use the same tiles and the same markers.
 *
 * [interactive] false gives a still preview: no panning, no zoom buttons, so a
 * tap on the card reaches the card's own click handler instead of the map
 * swallowing it.
 *
 * [homeZone], [freeZones] and [candidateZone] are drawn as circles — the same
 * geographic things Settings used to show only as rows of text. [onMapTap]
 * fires with the coordinates of a tap that lands on bare map (not a marker or
 * a zone circle, both of which consume the tap themselves); panning and
 * zooming are untouched, osmdroid still handles those itself.
 */
@Composable
fun MapCanvas(
    car: GeoPoint?,
    me: GeoPoint?,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    zoom: Double = 16.5,
    homeZone: FreeZone? = null,
    freeZones: List<FreeZone> = emptyList(),
    candidateZone: FreeZone? = null,
    /** Every tariff area to draw as a boundary. Empty means the overlay is off. */
    tariffAreas: List<TariffArea> = emptyList(),
    /**
     * The single part outlined above the rest — the piece the car is in, or the
     * piece that was tapped. Deliberately one ring and not a whole
     * [TariffArea]: most areas are many disjoint pieces, and highlighting all
     * of them lit up half the city at once.
     */
    highlightRing: ZonePolygon? = null,
    /** The proposed new car position while a correction is being confirmed. */
    ghostCar: GeoPoint? = null,
    /** Tapping the car marker; null leaves the marker inert as before. */
    onCarTap: (() -> Unit)? = null,
    /** The walking line back to the car, drawn above everything else. */
    walkRoute: List<GeoPoint> = emptyList(),
    /**
     * Bumped by the caller to ask for a pull-back to [OVERVIEW_ZOOM]. A counter
     * rather than a zoom value so that repeated requests still fire, and so a
     * user who zooms back in afterwards is never fought on recomposition — the
     * same reasoning as the framing guard below.
     */
    overviewRequest: Int = 0,
    /**
     * Bumped to centre on [me]. Counters for the same reason as
     * [overviewRequest]: the second tap of "locate me" has to move the map
     * again even though nothing about the state has changed.
     */
    locateRequest: Int = 0,
    /** Bumped to re-run the car-and-me fit that otherwise happens only once. */
    frameRequest: Int = 0,
    onMapTap: ((GeoPoint) -> Unit)? = null,
) {
    val context = LocalContext.current
    val colors = LocalHandoffColors.current
    // The tariff polygons are 170 rings and ~17,500 vertices. Everything else on
    // this canvas is rebuilt on every update pass, deliberately, so that a stale
    // factory-time closure cannot keep answering taps with the first
    // composition's parameters — but these never change, and rebuilding them
    // would be the one genuinely expensive thing on the screen. Built once and
    // re-added. Identity comparison suffices: PermitApp holds a single list.
    val tariffCache = remember { mutableStateOf<Pair<List<TariffArea>, List<Polygon>>?>(null) }
    val lastOverviewRequest = remember { mutableStateOf(overviewRequest) }
    val lastLocateRequest = remember { mutableStateOf(locateRequest) }
    val lastFrameRequest = remember { mutableStateOf(frameRequest) }
    val lastFraming = remember { mutableStateOf<Pair<List<GeoPoint>, Boolean>?>(null) }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(interactive)
                isClickable = interactive
                setOnTouchListener { _, _ -> !interactive }
                zoomController.setVisibility(
                    org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER,
                )
                controller.setZoom(zoom)
            }
        },
        update = { map ->
            // Every pending request is consumed whether or not it is the one
            // that wins, so a losing bump can never surface a pass later and
            // move the map out from under the user.
            val overviewPending = overviewRequest != lastOverviewRequest.value
            val locatePending = locateRequest != lastLocateRequest.value
            val framePending = frameRequest != lastFrameRequest.value
            lastOverviewRequest.value = overviewRequest
            lastLocateRequest.value = locateRequest
            lastFrameRequest.value = frameRequest

            // lastFraming tracks only the framing WE last set, never the map's
            // live (possibly user-panned) position — otherwise re-framing on
            // every recomposition would fight a pan made while placing a zone
            // candidate or dragging the radius slider, snapping the map back
            // under the user's finger.
            val focus = listOfNotNull(car, me)
            val framing = focus to interactive
            val autoFramePending = lastFraming.value != framing
            lastFraming.value = framing

            when (
                mapCameraCommand(
                    overviewPending = overviewPending,
                    locatePending = locatePending,
                    haveMyPosition = me != null,
                    framePending = framePending,
                    autoFramePending = autoFramePending,
                )
            ) {
                // Keeps the centre deliberately: switching the tariff layer on
                // is about seeing boundaries around where you already are.
                MapCameraCommand.OVERVIEW -> map.controller.setZoom(OVERVIEW_ZOOM)
                MapCameraCommand.LOCATE -> me?.let {
                    map.controller.setCenter(OsmGeoPoint(it.lat, it.lng))
                    map.controller.setZoom(zoom)
                }
                MapCameraCommand.FRAME -> frameOn(map, focus, zoom)
                MapCameraCommand.NONE -> Unit
            }

            // Rebuilt every update from the latest parameters, same as the
            // markers below — a stale factory-time closure would otherwise
            // keep answering onMapTap with whatever was passed in on the very
            // first composition.
            map.overlays.removeAll {
                it is Marker || it is Polygon || it is Polyline || it is MapEventsOverlay
            }
            map.overlays.add(
                MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: OsmGeoPoint): Boolean {
                        onMapTap?.invoke(GeoPoint(p.latitude, p.longitude, 0f))
                        return false
                    }
                    override fun longPressHelper(p: OsmGeoPoint) = false
                }),
            )

            // Underneath the zone circles and markers: these are the ambient
            // layer, and nothing placed by hand should ever be hidden by them.
            if (tariffAreas.isNotEmpty()) {
                val cached = tariffCache.value
                val polys = if (cached != null && cached.first === tariffAreas) {
                    cached.second
                } else {
                    tariffAreas.flatMap { area ->
                        area.polygons.map { ring ->
                            tariffPolygon(map, ring, colors.tariffBoundary, fillAlpha = 0.07f, strokeWidthPx = 2f)
                        }
                    }.also { tariffCache.value = tariffAreas to it }
                }
                polys.forEach { map.overlays.add(it) }
            }
            highlightRing?.let { ring ->
                map.overlays.add(
                    tariffPolygon(map, ring, colors.tariffSelected, fillAlpha = 0.16f, strokeWidthPx = 7f),
                )
            }

            homeZone?.let {
                map.overlays.add(
                    zoneCircle(map, it, colors.zoneHome, dashed = false, fillAlpha = 0.18f, strokeWidthPx = 4f),
                )
            }
            freeZones.forEach {
                map.overlays.add(
                    zoneCircle(map, it, colors.zoneFree, dashed = true, fillAlpha = 0.12f, strokeWidthPx = 4f),
                )
            }
            candidateZone?.let {
                map.overlays.add(
                    zoneCircle(map, it, colors.zoneCandidate, dashed = true, fillAlpha = 0.24f, strokeWidthPx = 6f),
                )
            }

            // Above the zones but below the markers, so neither end of the
            // route is ever hidden by the line that leads to it.
            if (walkRoute.size >= 2) {
                map.overlays.add(
                    Polyline(map).apply {
                        setPoints(walkRoute.map { OsmGeoPoint(it.lat, it.lng) })
                        outlinePaint.color = colors.walkRoute.toArgb()
                        outlinePaint.strokeWidth = 12f
                        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(26f, 18f), 0f)
                        infoWindow = null
                    },
                )
            }

            car?.let {
                map.overlays.add(Marker(map).apply {
                    position = OsmGeoPoint(it.lat, it.lng)
                    icon = ContextCompat.getDrawable(map.context, R.drawable.ic_marker_car)
                    title = "Your car"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    // Consumes the tap either way: osmdroid's stock InfoWindow
                    // bubble is off-centre and just repeats the title, so it is
                    // suppressed whether or not anyone is listening. When
                    // someone is, this is the entry point to moving the pin.
                    setOnMarkerClickListener { _, _ ->
                        onCarTap?.invoke()
                        true
                    }
                })
            }
            ghostCar?.let {
                map.overlays.add(Marker(map).apply {
                    position = OsmGeoPoint(it.lat, it.lng)
                    icon = ContextCompat.getDrawable(map.context, R.drawable.ic_marker_car)
                    // Half strength so it reads as "proposed", not "there are
                    // two cars" — the same unsaved-candidate idea as the zone
                    // candidate circle, which says it with weight instead.
                    alpha = 0.5f
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ -> true }
                })
            }
            me?.let {
                map.overlays.add(Marker(map).apply {
                    position = OsmGeoPoint(it.lat, it.lng)
                    icon = ContextCompat.getDrawable(map.context, R.drawable.ic_marker_me)
                    title = "You"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> true }
                })
            }
            map.invalidate()
        },
    )
}

/**
 * Frame everything worth seeing — the car and you — rather than centring on one
 * and leaving the other off screen to be hunted for. With only one of them, or
 * with the two effectively on top of each other, falls back to centring at the
 * normal zoom.
 */
private fun frameOn(map: MapView, focus: List<GeoPoint>, zoom: Double) {
    when {
        focus.size == 2 && distanceMeters(focus[0], focus[1]) > MIN_SPREAD_M -> {
            val box = BoundingBox(
                maxOf(focus[0].lat, focus[1].lat),
                maxOf(focus[0].lng, focus[1].lng),
                minOf(focus[0].lat, focus[1].lat),
                minOf(focus[0].lng, focus[1].lng),
            )
            // osmdroid cannot fit a box before the view has been measured, and
            // silently does nothing if asked early.
            map.post {
                runCatching { map.zoomToBoundingBox(box, false, FRAME_PADDING_PX) }
            }
        }
        else -> {
            val centre = focus.firstOrNull() ?: FALLBACK
            map.controller.setCenter(OsmGeoPoint(centre.lat, centre.lng))
            map.controller.setZoom(zoom)
        }
    }
}

/**
 * A filled translucent circle with a ring at the zone's true radius.
 * [dashed] gives free zones (plural, more casual) a ring texture distinct
 * from the home zone's solid ring (singular, permanent) — a difference that
 * still reads if colour alone is hard to perceive. The click listener always
 * returns false so a tap on the circle still reaches [MapEventsOverlay]:
 * `onMapTap` plus the tested [zoneHitAt] is the single place that decides
 * which zone a tap hit, rather than osmdroid's own polygon geometry.
 */
/**
 * One ring of a tariff area.
 *
 * Holes are ignored. osmdroid's Polygon does support them, but a boundary drawn
 * at 0.07 alpha reads the same either way, and the app's own point-in-polygon
 * test — which does honour holes — is what actually decides anything. Returns
 * false from its click listener for the same reason [zoneCircle] does:
 * [mapHitAt] is the single place that decides what a tap hit.
 */
private fun tariffPolygon(
    map: MapView,
    ring: ZonePolygon,
    color: Color,
    fillAlpha: Float,
    strokeWidthPx: Float,
): Polygon = Polygon(map).apply {
    points = ring.outer.map { OsmGeoPoint(it.lat, it.lng) }
    fillColor = color.copy(alpha = fillAlpha).toArgb()
    strokeColor = color.toArgb()
    strokeWidth = strokeWidthPx
    setOnClickListener { _, _, _ -> false }
    infoWindow = null
}

private fun zoneCircle(
    map: MapView,
    zone: FreeZone,
    color: Color,
    dashed: Boolean,
    fillAlpha: Float,
    strokeWidthPx: Float,
): Polygon = Polygon(map).apply {
    points = Polygon.pointsAsCircle(OsmGeoPoint(zone.lat, zone.lng), zone.radiusM)
    fillColor = color.copy(alpha = fillAlpha).toArgb()
    strokeColor = color.toArgb()
    strokeWidth = strokeWidthPx
    if (dashed) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
    setOnClickListener { _, _, _ -> false }
    infoWindow = null
}
