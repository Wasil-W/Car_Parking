package dev.wasil.permit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.wasil.permit.R
import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.ui.theme.HandoffShapes
import dev.wasil.permit.ui.theme.LocalHandoffColors

/**
 * Placing, sizing, listing and editing the two kinds of zone.
 *
 * Split out of `MapScreen.kt` in v0.6.8, when this stopped being one card with
 * a slider on it. The screen owns the map and the state; this file owns what a
 * zone looks like while you are working on it.
 *
 * The standing complaint behind the whole file, raised more than once: placing
 * and editing zones is awkward. Three specific things were true, and each one
 * has a composable here answering it — the radius could only be dragged, an
 * existing zone could only be reached by finding its circle on the map, and
 * there was no list of what you had.
 */

/** Which kind of zone the map is currently placing a candidate point for. */
internal enum class ZoneKind { HOME, FREE }

internal fun ZoneKind.title(): String = if (this == ZoneKind.HOME) "Home zone" else "Free zone"

/**
 * A radius in metres, three ways: typed, stepped, dragged.
 *
 * **Why all three.** Wasil wants placing and sizing to be more exact than
 * dragging a slider over a map, and he is right that a slider cannot be told a
 * number — it can only be pushed until the label happens to read one. But the
 * slider is still the fastest way to get roughly right, and it is the only one
 * of the three that shows the circle growing under your finger while you decide.
 * So the field is for "eighty metres", the steppers are for "a bit more", and
 * the slider is for "about like that".
 *
 * The field holds its own text rather than reformatting on every keystroke:
 * rewriting what someone is halfway through typing is how typed inputs get
 * abandoned. It is re-synced whenever the value changes from the outside — a
 * stepper press or a slider drag — and clamped once, on the way out.
 */
@Composable
internal fun RadiusControl(
    radiusM: Float,
    onRadiusChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the value so a stepper press or a slider drag rewrites the field,
    // while typing into the field does not fight itself.
    var text by remember(radiusM) { mutableStateOf(radiusFieldText(radiusM.toDouble())) }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton("−", "Smaller") {
                onRadiusChange(stepZoneRadius(radiusM.toDouble(), -1).toFloat())
            }
            OutlinedTextField(
                value = text,
                onValueChange = { typed ->
                    // Digits and one separator only. Filtering here rather than
                    // rejecting on commit means the field can never hold
                    // something it will refuse later.
                    text = typed.filter { it.isDigit() || it == '.' || it == ',' }.take(4)
                    parseZoneRadius(text)?.let { onRadiusChange(it.toFloat()) }
                },
                label = { Text("Radius") },
                suffix = { Text("m") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            StepButton("+", "Bigger") {
                onRadiusChange(stepZoneRadius(radiusM.toDouble(), 1).toFloat())
            }
        }
        // Explicit colours: the stock inactive track resolves to a neutral that
        // is invisible against these cards' own neutral surface, so the slider
        // rendered as a thumb floating next to a stray dot with no track
        // between them. Found on screen in v0.6.4; do not delete the colours.
        Slider(
            value = radiusM.coerceIn(ZONE_RADIUS_MIN_M.toFloat(), ZONE_RADIUS_MAX_M.toFloat()),
            onValueChange = onRadiusChange,
            valueRange = ZONE_RADIUS_MIN_M.toFloat()..ZONE_RADIUS_MAX_M.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = MaterialTheme.colorScheme.onSurface,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            ),
        )
    }
}

/**
 * A square −/+ button, sized to the text field beside it so the row reads as one
 * control rather than three. Its own surface, like every other control in this
 * app that sits over or beside the map.
 */
@Composable
private fun StepButton(glyph: String, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        // 56dp is OutlinedTextField's own height. Matching it is what makes the
        // three read as one control instead of three things in a row.
        //
        // The glyph is the label for anyone reading the screen; the semantics
        // are the label for anyone hearing it — "−" and "+" announce as
        // punctuation.
        modifier = Modifier.size(56.dp).semantics { contentDescription = label },
        shape = HandoffShapes.Control,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(glyph, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/**
 * "Tap the map to place it" — plus the two points the app already knows exactly.
 *
 * Offered before the first tap rather than after it, because that is when they
 * save the tap. A home zone is placed while standing at home and a free zone is
 * almost always placed having just parked in one, so "here" and "where the car
 * is" are the two answers most often wanted, and both are exact where a map tap
 * is worth about ten metres.
 */
@Composable
internal fun ZonePlaceHintCard(
    kind: ZoneKind,
    onUseMyPosition: (() -> Unit)?,
    onUseCarSpot: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (kind == ZoneKind.HOME) {
                        "Tap the map to place home"
                    } else {
                        "Tap the map to place the zone"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            if (onUseMyPosition != null || onUseCarSpot != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    onUseMyPosition?.let { TextButton(onClick = it) { Text("Use my position") } }
                    onUseCarSpot?.let { TextButton(onClick = it) { Text("Use the car's spot") } }
                }
            }
        }
    }
}

/**
 * The zone's own ring, at list size.
 *
 * Solid for the home zone (singular, permanent), dashed for a free one (plural,
 * more casual) — **the same two treatments the circles carry on the map**, so a
 * row and a circle read as the same object rather than the list becoming a
 * second inventory.
 *
 * **Not the map's zone colours, though**, and the exception is worth stating.
 * `ZoneHome` and `ZoneFree` are deliberately mode-independent because the tiles
 * they sit on never change with the theme. This sheet is app surface, not
 * tiles: on it, `ZoneHome` (#4A463D) against `SurfaceDark` (#171715) measures
 * about 1.9:1 and simply is not there. Seen in dark mode on the emulator. So
 * the ring takes the theme's own secondary neutral, and the **shape** carries
 * the difference — which is what solid-versus-dashed was already there for,
 * per `Color.kt`: a distinction that still reads when colour does not.
 */
@Composable
private fun ZoneRing(home: Boolean, area: Boolean = false) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(18.dp)) {
        val stroke = 2.dp.toPx()
        val dashes = PathEffect.dashPathEffect(floatArrayOf(stroke * 1.6f, stroke * 1.3f))
        if (area) {
            // A blob rather than a circle, dashed like every other free zone.
            // A neighbourhood is not round and the row should not imply it is —
            // this is the same distinction the map draws, at 18dp.
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.10f, h * 0.36f)
                lineTo(w * 0.42f, h * 0.08f)
                lineTo(w * 0.90f, h * 0.26f)
                lineTo(w * 0.80f, h * 0.78f)
                lineTo(w * 0.34f, h * 0.92f)
                close()
            }
            drawPath(path, color = color, style = Stroke(width = stroke, pathEffect = dashes))
            return@Canvas
        }
        drawCircle(
            color = color,
            radius = (size.minDimension - stroke) / 2f,
            style = Stroke(width = stroke, pathEffect = if (home) null else dashes),
        )
    }
}

@Composable
internal fun ZoneHintCard(text: String, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/**
 * The card that stands where a zone is being placed — in one of two modes,
 * chosen by whether the tap landed in a neighbourhood the city has named.
 *
 * **The area mode is the answer to "home zones and free zones feel identical".**
 * They did, because both were a circle with the same slider, and Wasil's own
 * fix — 2026-08-08, looking at the council's map of Molenwijk — was: *"do you
 * see the molenwijk. We could do that we can put those for the free zones, with
 * outline."* So a free zone in Amsterdam is a neighbourhood, offered by name,
 * with the city's boundary. Nothing is dragged, so nothing is approximate, and
 * the sizing complaint stops being a UI problem.
 *
 * The home zone keeps the circle and the slider, and the difference is now
 * meaningful rather than incidental: a home zone is *a point you own* — your
 * street, deliberately 30–200 m — while a free zone is *an area you know about*,
 * and areas have published edges.
 *
 * [areaSize] is shown rather than hidden because marking an area free is a
 * standing instruction never to claim the permit anywhere inside it. Buurten run
 * from a few streets to most of a district, and being wrong here costs a fine,
 * so the person choosing gets the number.
 *
 * [onUseCircleInstead] is the escape from the area, and it is not a
 * formality: a free car park at the edge of a large buurt is a real thing to
 * want, and the neighbourhood would be a far bigger claim than was meant.
 *
 * [onUseMyPosition] and [onUseCarSpot] are the precision that a map tap cannot
 * give. Aiming at a map at parking zoom is worth maybe ten metres on a good
 * day; the phone's own fix and the recorded parked position are the two points
 * the app already knows exactly, and a free zone in particular is almost always
 * discovered by having just parked in one. Null when there is no such point to
 * offer — a button that cannot work is worse than no button.
 */
@Composable
internal fun ZoneCandidateCard(
    kind: ZoneKind,
    radiusM: Float,
    onRadiusChange: (Float) -> Unit,
    onUseMyPosition: (() -> Unit)?,
    onUseCarSpot: (() -> Unit)?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    /** The neighbourhood under the tap, or null when there is none there. */
    areaName: String? = null,
    /** "0.6 km²" — how much ground [areaName] covers. */
    areaSize: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            val offerable = kind == ZoneKind.HOME || areaName != null
            when {
                kind == ZoneKind.HOME -> {
                    Text(kind.title(), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Tap the map to move it, or type a radius.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RadiusControl(radiusM, onRadiusChange, Modifier.padding(top = 10.dp))
                }
                areaName != null -> {
                    // Full strength, like the size under it. The card's own
                    // content colour is onSurfaceVariant, which left the
                    // question paler than its own answer — seen on the emulator,
                    // and it read as an accident rather than a hierarchy.
                    Text(
                        zoneAreaOffer(areaName),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // The size at full strength, not as a footnote. It is the
                    // one moment a person can tell a street corner from half a
                    // district, and the consequence of getting it wrong is
                    // silent — see [zoneAreaSizeLine].
                    Text(
                        zoneAreaSizeLine(areaSize),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        ZONE_AREA_CONSEQUENCE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // No published area covers this point, so there is nothing to
                // mark and no Confirm to offer. Said about *this spot* rather
                // than about a city — see [NO_NEIGHBOURHOOD_HERE].
                else -> {
                    Text("No area published here", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        NO_NEIGHBOURHOOD_HERE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onUseMyPosition != null || onUseCarSpot != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    onUseMyPosition?.let {
                        TextButton(onClick = it) { Text("Use my position") }
                    }
                    onUseCarSpot?.let {
                        TextButton(onClick = it) { Text("Use the car's spot") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                if (offerable) TextButton(onClick = onConfirm) { Text("Confirm") }
            }
        }
    }
}

/**
 * Rename, resize or remove one zone.
 *
 * The resize is v0.6.8's addition and it closes a real hole: a zone that turned
 * out to be thirty metres too small had to be deleted and placed again from
 * scratch, because the slider only ever existed while placing. A hand-typed
 * name still beats any geocoded address ("Mum's street"), so that stays; saving
 * a blank one falls back to the zone's coordinates rather than leaving it empty.
 */
@Composable
internal fun ZoneEditDialog(
    target: ZoneRef,
    zone: FreeZone,
    onDismiss: () -> Unit,
    onSave: (label: String, radiusM: Double) -> Unit,
    onRemove: () -> Unit,
    /** "0.6 km²" for a neighbourhood-backed zone; null for a circle. */
    areaSize: String? = null,
) {
    var name by remember(target) { mutableStateOf(zone.label) }
    var radius by remember(target) { mutableFloatStateOf(zone.radiusM.toFloat()) }
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
                // A neighbourhood has no radius to change, so it is not offered
                // one. Showing a disabled slider would imply the boundary is
                // ours to move; it is the city's, which is the whole reason
                // this kind of zone exists. Removing and re-picking is the way
                // to change which area it is — one tap either way.
                if (zone.isArea) {
                    Text(
                        listOfNotNull(
                            zone.buurt?.let { "The whole of $it, with the city's own boundary" },
                            areaSize?.let { "about $it" },
                        ).joinToString(" · ") + ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    RadiusControl(radius, onRadiusChange = { radius = it })
                }
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
                onSave(
                    name.trim().ifBlank { formatCoordinates(zone.lat, zone.lng) },
                    clampZoneRadius(radius.toDouble()),
                )
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

/**
 * Every zone you have, in one place.
 *
 * **Why it exists.** Until now a zone could only be reached by finding its
 * circle on the map and tapping it, which needs you to remember where you put it
 * and to be looking at that part of the city. One placed at your mother's street
 * six weeks ago was, in practice, gone.
 *
 * Each row carries the same ring treatment the circle has on the map — home
 * solid, free dashed, both in the zone neutrals rather than either brother's
 * colour — so a row and a circle read as the same object. Tapping one centres
 * the map on it and opens the editor, which is the whole point: the list is a
 * way back to the map, not a second place to manage things.
 *
 * A sheet rather than a dialog because it is a list of unknown length over a map
 * that should stay visible behind it, and because tapping a row moves that map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZoneListSheet(
    entries: List<ZoneEntry>,
    onPick: (ZoneEntry) -> Unit,
    onAddFreeZone: () -> Unit,
    onDismiss: () -> Unit,
    /** "60 m" or "0.6 km²" — the row's own unit; see [zoneSizeText]. */
    sizeText: (ZoneEntry) -> String = { "" },
) {
    val colors = LocalHandoffColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("Your zones", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tap one to see it on the map and change it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                Modifier
                    .padding(top = 8.dp)
                    // Bounded rather than unbounded: eight free zones must not
                    // push the sheet past the top of the screen and take the
                    // "Add" row off the bottom with it.
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(entry) }
                            .padding(vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ZoneRing(home = entry.ref == ZoneRef.Home, area = entry.isArea)
                        Text(
                            entry.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            sizeText(entry),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onAddFreeZone) {
                    Icon(
                        painterResource(R.drawable.ic_zone_free),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Add free zone")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}
