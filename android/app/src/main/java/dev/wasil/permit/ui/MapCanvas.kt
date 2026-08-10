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
    /**
     * Where an area-backed free zone gets its outline. A zone whose
     * neighbourhood cannot be found here is not drawn at all rather than drawn
     * as the circle it is not — see [dev.wasil.permit.parking.FreeZone.buurt].
     */
    areaShape: (String) -> List<ZonePolygon>? = { null },
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
    /** Bumped to centre on the parked car. */
    carRequest: Int = 0,
    /**
     * Bumped to centre on [spot] — a zone picked from the list, which is on
     * screen only by coincidence. Same counter reasoning as the rest: picking
     * the same row twice has to move the map back both times.
     */
    spotRequest: Int = 0,
    spot: GeoPoint? = null,
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
    val lastCarRequest = remember { mutableStateOf(carRequest) }
    val lastSpotRequest = remember { mutableStateOf(spotRequest) }
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
            val carPending = carRequest != lastCarRequest.value
            val spotPending = spotRequest != lastSpotRequest.value
            lastOverviewRequest.value = overviewRequest
            lastLocateRequest.value = locateRequest
            lastFrameRequest.value = frameRequest
            lastCarRequest.value = carRequest
            lastSpotRequest.value = spotRequest

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
                    carPending = carPending,
                    haveCar = car != null,
                    autoFramePending = autoFramePending,
                    spotPending = spotPending,
                    haveSpot = spot != null,
                )
            ) {
                // Keeps the centre deliberately: switching the tariff layer on
                // is about seeing boundaries around where you already are.
                MapCameraCommand.OVERVIEW -> map.controller.setZoom(OVERVIEW_ZOOM)
                MapCameraCommand.LOCATE -> me?.let {
                    map.controller.setCenter(OsmGeoPoint(it.lat, it.lng))
                    map.controller.setZoom(zoom)
                }
                MapCameraCommand.CAR -> car?.let {
                    map.controller.setCenter(OsmGeoPoint(it.lat, it.lng))
                    map.controller.setZoom(zoom)
                }
                MapCameraCommand.SPOT -> spot?.let {
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
                            tariffPolygon(
                                map,
                                ring,
                                colors.tariffBoundary,
                                fillAlpha = 0f,
                                strokeWidthDp = Line.AMBIENT_DP,
                                dotted = true,
                            )
                        }
                    }.also { tariffCache.value = tariffAreas to it }
                }
                polys.forEach { map.overlays.add(it) }
            }
            highlightRing?.let { ring ->
                map.overlays.add(
                    tariffPolygon(map, ring, colors.tariffSelected, fillAlpha = 0.16f, strokeWidthDp = Line.PROMOTED_DP),
                )
            }

            homeZone?.let {
                map.overlays.add(
                    zoneCircle(map, it, colors.zoneHome, dashed = false, fillAlpha = 0.18f, strokeWidthDp = Line.YOURS_CLOSED_DP),
                )
            }
            // Circle or outline, same colour and same dashes either way.
            // `zoneFree` is the app's one free-zone hue and there is no second
            // one here on purpose: the *shape* already says which kind it is,
            // and adding a hue would mean inventing a colour whose only job is
            // to repeat what is plainly visible. Dashed in both cases, which is
            // what Color.kt says distinguishes free from home when colour does
            // not read — over tiles, at a glance, in either theme.
            // Heavier than the 4f a circle takes, and the difference is not
            // taste. `ZoneFree` (#8C8676) was chosen for a small closed circle,
            // where a light tint reads fine because the *shape* carries it. A
            // neighbourhood boundary is a long thin line threading dense street
            // detail, and at 4f it vanished outright — the zone was saved,
            // listed and editable, and simply was not on the map. Seen on the
            // emulator in light mode, which is the only way this was ever going
            // to be caught. Same hue, same dashes, more of them.
            freeZones.forEach { zone ->
                zoneShapes(map, zone, areaShape, colors.zoneFree, fillAlpha = 0.16f, strokeWidthDp = Line.YOURS_OPEN_DP)
                    .forEach { map.overlays.add(it) }
            }
            candidateZone?.let { zone ->
                zoneShapes(map, zone, areaShape, colors.zoneCandidate, fillAlpha = 0.24f, strokeWidthDp = Line.UNSAVED_DP)
                    .forEach { map.overlays.add(it) }
            }

            // Above the zones but below the markers, so neither end of the
            // route is ever hidden by the line that leads to it.
            if (walkRoute.size >= 2) {
                map.overlays.add(
                    Polyline(map).apply {
                        setPoints(walkRoute.map { OsmGeoPoint(it.lat, it.lng) })
                        outlinePaint.color = colors.walkRoute.toArgb()
                        outlinePaint.strokeWidth = map.px(Line.ROUTE_DP)
                        // BUTT, not ROUND. v0.7.0 set ROUND here and undid the
                        // dash it was setting one line below: a round cap
                        // extends every run by half a width at *each* end, so
                        // [2.0w on, 1.5w off] paints as 3.0w on and 0.5w off —
                        // six parts ink to one, a line with nicks in it rather
                        // than a dashed line. The mechanism is spelled out in
                        // Line.dot's own comment, where it is the whole point;
                        // it was applied to dash without being accounted for.
                        //
                        // Explicit rather than left to the default, because
                        // osmdroid hands out a bare Paint and a default is not
                        // a decision.
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.BUTT
                        outlinePaint.pathEffect =
                            DashPathEffect(Line.dash(map.px(Line.ROUTE_DP)), 0f)
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
 * The map's line hierarchy, in **dp**.
 *
 * Every stroke width and dash interval in this file used to be a raw device
 * pixel — `strokeWidth = 9f`, `DashPathEffect(floatArrayOf(18f, 14f))` — handed
 * straight to a `Paint`, which takes pixels. So the whole hierarchy moved with
 * the device: the `9f` that v0.6.8 finally made a free-zone boundary visible
 * with is 3.4dp at density 2.625 and 2.25dp at density 4, which is back under
 * the 6px that had already failed to show up. The relationships between these
 * lines were tuned by eye on one emulator and were not reproducible anywhere
 * else. That is fixed first, because nothing else here is meaningful until it
 * is.
 *
 * **What the three channels mean**, so a new line has somewhere to go:
 *
 * - **Weight says whose line it is.** The city's published lines are thin; the
 *   ones you placed are thick. A city line you promoted by tapping sits between
 *   — enough to win against the other 169 rings, never enough to reach the top
 *   of your own band, because it is still not yours.
 * - **Texture says how many there are.** Solid means there is exactly one (the
 *   home zone; the part you tapped). Dashed means one of several you placed.
 *   Dotted means published rather than placed. This keeps v0.6.8's existing
 *   home-solid / free-dashed meaning rather than redefining it.
 * - **Opacity is spent on fills, never on strokes.** Not taste — arithmetic.
 *   `TariffBoundary` measures 3.61:1 against MAPNIK's building fill, the worst
 *   realistic tile background. At alpha 0.75 that is 2.50:1, under the 3:1
 *   floor for a non-text graphic, in light mode, which the map cannot escape.
 *   There is about 0.6:1 of headroom in the entire channel.
 *
 * **The tariff line goes up, not down.** Wasil asked for something thinner —
 * *"a really thin dotted line for when we use the tariff layer"* — and the
 * instinct is right while the word is the wrong half. At 2f it was already
 * 0.67dp at density 3, and below about 1dp the rasteriser takes over: a
 * diagonal anti-aliases into grey mush whose darkness varies with zoom. So the
 * mark gets *wider* (1.0dp, surviving anti-aliasing) and *intermittent* — 22%
 * ink coverage. Net, about a third of the ink from a mark half again as wide.
 * Intermittency is the only demotion that does not cost the legibility of the
 * individual mark.
 *
 * Patterns are multiples of the line's own width so their rhythm survives every
 * density and every weight. That was already latent in the code: the free zone
 * (9px, 18/14) and the walk route (12px, 26/18) both sat at roughly 2.0w/1.5w.
 */
private object Line {
    const val AMBIENT_DP = 1.0f
    const val PROMOTED_DP = 2.5f
    const val YOURS_CLOSED_DP = 2.5f
    const val YOURS_OPEN_DP = 3.0f
    const val UNSAVED_DP = 3.5f
    const val ROUTE_DP = 4.0f

    fun dash(widthPx: Float) = floatArrayOf(widthPx * 2.0f, widthPx * 1.5f)

    /**
     * A round cap extends each run by half the width at both ends, so an "on"
     * of 0.1w paints a near-circular dot of diameter w with a 4w gap. Never
     * zero — Skia drops zero-length segments on some versions.
     */
    fun dot(widthPx: Float) = floatArrayOf(widthPx * 0.1f, widthPx * 4.9f)
}

/** dp is the spec; Paint takes pixels. */
private fun MapView.px(dp: Float): Float = dp * resources.displayMetrics.density

/**
 * One ring of a tariff area — ambient, so dotted and unfilled.
 *
 * Holes are ignored. osmdroid's Polygon does support them, and the app's own
 * point-in-polygon test — which does honour holes — is what actually decides
 * anything.
 *
 * **The fill is gone**, down from 0.07. With 29 areas tiling the city the tint
 * was answering "is this ground inside some tariff area", and the chip answers
 * that in words for the area that matters. This is the one number here most
 * worth looking at rather than reasoning about: if the wash reads as *paid
 * parking country starts here*, 0.04 brings it back.
 */
private fun tariffPolygon(
    map: MapView,
    ring: ZonePolygon,
    color: Color,
    fillAlpha: Float,
    strokeWidthDp: Float,
    dotted: Boolean = false,
): Polygon = Polygon(map).apply {
    val width = map.px(strokeWidthDp)
    points = ring.outer.map { OsmGeoPoint(it.lat, it.lng) }
    fillColor = color.copy(alpha = fillAlpha).toArgb()
    strokeColor = color.toArgb()
    strokeWidth = width
    if (dotted) {
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.pathEffect = DashPathEffect(Line.dot(width), 0f)
    }
    setOnClickListener { _, _, _ -> false }
    infoWindow = null
}

/**
 * One free zone, drawn as whatever it actually is.
 *
 * A neighbourhood is many rings — some buurten are split by water — so this
 * returns a list rather than one polygon. An area-backed zone whose boundary is
 * missing draws **nothing**: falling back to its stored radius would put a 60 m
 * circle on the map in the middle of a neighbourhood the app is not actually
 * treating as free, and a shape that disagrees with the decision is worse than
 * no shape. The zone is still listed in "Your zones", where it can be removed.
 */
private fun zoneShapes(
    map: MapView,
    zone: FreeZone,
    areaShape: (String) -> List<ZonePolygon>?,
    color: Color,
    fillAlpha: Float,
    strokeWidthDp: Float,
): List<Polygon> {
    val buurt = zone.buurt
        ?: return listOf(zoneCircle(map, zone, color, dashed = true, fillAlpha, strokeWidthDp))
    val rings = areaShape(buurt) ?: return emptyList()
    return rings.map { ring ->
        Polygon(map).apply {
            points = ring.outer.map { OsmGeoPoint(it.lat, it.lng) }
            // Holes drawn as well as tested. A buurt with a park or a dock
            // punched out of it is common, and the app's own point-in-polygon
            // honours them — a fill that ignored them would show the map
            // claiming ground the decision does not.
            if (ring.holes.isNotEmpty()) {
                holes = ring.holes.map { hole -> hole.map { OsmGeoPoint(it.lat, it.lng) } }
            }
            val width = map.px(strokeWidthDp)
            fillColor = color.copy(alpha = fillAlpha).toArgb()
            strokeColor = color.toArgb()
            strokeWidth = width
            // Stated, not inherited: a dash only keeps its rhythm under a BUTT
            // cap, and osmdroid hands out a bare Paint whose default is not a
            // decision anyone here made. See the walk route for what a ROUND
            // cap does to a dash.
            outlinePaint.strokeCap = android.graphics.Paint.Cap.BUTT
            outlinePaint.pathEffect = DashPathEffect(Line.dash(width), 0f)
            setOnClickListener { _, _, _ -> false }
            infoWindow = null
        }
    }
}

private fun zoneCircle(
    map: MapView,
    zone: FreeZone,
    color: Color,
    dashed: Boolean,
    fillAlpha: Float,
    strokeWidthDp: Float,
): Polygon = Polygon(map).apply {
    val width = map.px(strokeWidthDp)
    points = Polygon.pointsAsCircle(OsmGeoPoint(zone.lat, zone.lng), zone.radiusM)
    fillColor = color.copy(alpha = fillAlpha).toArgb()
    strokeColor = color.toArgb()
    strokeWidth = width
    if (dashed) {
        outlinePaint.strokeCap = android.graphics.Paint.Cap.BUTT
        outlinePaint.pathEffect = DashPathEffect(Line.dash(width), 0f)
    }
    setOnClickListener { _, _, _ -> false }
    infoWindow = null
}
