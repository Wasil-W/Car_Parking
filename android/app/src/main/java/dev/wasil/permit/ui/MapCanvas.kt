package dev.wasil.permit.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import dev.wasil.permit.R
import dev.wasil.permit.parking.GeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** Amsterdam centre, used only when there is nothing else to show. */
private val FALLBACK = GeoPoint(52.3702, 4.8952, 0f)

/**
 * The map, shared by the preview card on the main screen and the full map tab,
 * so both use the same tiles and the same markers.
 *
 * [interactive] false gives a still preview: no panning, no zoom buttons, so a
 * tap on the card reaches the card's own click handler instead of the map
 * swallowing it.
 */
@Composable
fun MapCanvas(
    car: GeoPoint?,
    me: GeoPoint?,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    zoom: Double = 16.5,
) {
    val context = LocalContext.current
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
            val centre = car ?: me ?: FALLBACK
            map.controller.setCenter(org.osmdroid.util.GeoPoint(centre.lat, centre.lng))
            map.overlays.removeAll { it is Marker }
            car?.let {
                map.overlays.add(Marker(map).apply {
                    position = org.osmdroid.util.GeoPoint(it.lat, it.lng)
                    icon = ContextCompat.getDrawable(map.context, R.drawable.ic_marker_car)
                    title = "Your car"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            me?.let {
                map.overlays.add(Marker(map).apply {
                    position = org.osmdroid.util.GeoPoint(it.lat, it.lng)
                    icon = ContextCompat.getDrawable(map.context, R.drawable.ic_marker_me)
                    title = "You"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                })
            }
            map.invalidate()
        },
    )
}
