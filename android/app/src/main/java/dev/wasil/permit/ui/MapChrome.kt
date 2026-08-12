package dev.wasil.permit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import dev.wasil.permit.parking.facilities.Facility
import dev.wasil.permit.parking.facilities.FacilityKind
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.TariffNext
import dev.wasil.permit.parking.zones.tariffNext
import dev.wasil.permit.parking.zones.tariffNow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wasil.permit.R
import dev.wasil.permit.ui.theme.HandoffShapes

/**
 * The map's chrome: everything that floats *over* the tiles rather than taking
 * a row of layout above or below them.
 *
 * Why this file exists at all. Handoff was spending a header row, a
 * three-button card, a full-width button and an attribution line on chrome, and
 * the map — the whole point of the screen — got what was left. The reference
 * apps in `docs/inspo/` spend a floating pill and two circles. Moving the same
 * controls onto the map costs nothing and roughly doubles the tiles on screen.
 *
 * One rule governs every colour below: **the tiles stay light whatever the app
 * theme is doing.** Nothing here may be a bare label or a transparent-container
 * button, because in dark mode its near-white content would render straight
 * onto pale streets and vanish — which is exactly the bug v0.5.2 fixed and
 * exactly what this pass could reintroduce. Every control gets a surface of its
 * own, and the surface is what carries the contrast.
 */

/** Mockup geometry: 40dp circles, 9dp apart, 10dp in from the right edge. */
private val CONTROL_SIZE = 40.dp
private val CONTROL_GAP = 9.dp

/**
 * How opaque a backing over the tiles has to be.
 *
 * Measured on a device, not chosen. 0.86 looked right in dark mode — a dark
 * backing on pale streets separates itself — and was visibly wrong in light
 * mode, where the pale backing has almost no contrast with the tiles to begin
 * with and street labels read straight through the header text. 0.92 is where
 * both modes hold, and it is still translucent enough that the map is legibly
 * continuous behind the header rather than sliced by it.
 */
internal const val OVER_TILES_ALPHA = 0.92f

/**
 * How the tariff panel opens and closes.
 *
 * Written out rather than taken from a motion scheme because the Material 3 in
 * use here has none. Two families, and the split is the point: **size** may
 * overshoot a little, because a panel that arrives with a touch of weight reads
 * as physical; **effects** — colour, elevation — may not, because an overshoot
 * on a channel that clamps at 0 or 1 shows as a flat spot in the middle of the
 * fade.
 *
 * `visibilityThreshold` is mandatory on the size springs. Without it the
 * threshold falls back to a hundredth of a pixel and the animation runs on long
 * after it has visually finished, which is exactly why the library's own
 * default passes one.
 *
 * Opening is springier and slower than closing on purpose: this panel is opened
 * deliberately and dismissed impatiently, and an exit that lingers reads as lag.
 */
private val OPEN_SIZE: FiniteAnimationSpec<IntSize> =
    spring(dampingRatio = 0.9f, stiffness = 700f, visibilityThreshold = IntSize.VisibilityThreshold)
private val CLOSE_SIZE: FiniteAnimationSpec<IntSize> =
    spring(dampingRatio = 1f, stiffness = 1800f, visibilityThreshold = IntSize.VisibilityThreshold)
private val EFFECTS_COLOR: FiniteAnimationSpec<Color> = spring(dampingRatio = 1f, stiffness = 1600f)
private val EFFECTS_DP: FiniteAnimationSpec<Dp> =
    spring(dampingRatio = 1f, stiffness = 1600f, visibilityThreshold = Dp.VisibilityThreshold)

/**
 * The one place a visible overshoot is free: 2.8% of 180° is about five
 * degrees, on an 18dp glyph that cannot clip or reflow anything.
 */
private val CHEVRON_SPIN: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.75f, stiffness = 700f)

/**
 * Whether tapping this area's chip opens anything.
 *
 * One function, two callers, and that is the point. The chip decides whether to
 * draw a chevron and whether to become a panel; `MapHeaderOverlay` decides
 * whether to hand the chip two thirds of the header row. In v0.7.0 those were
 * two different expressions of the same idea, and they disagreed: the header
 * still asked "is an area selected and is the week open", so expanding a normal
 * area and then tapping Centrum gave the chip panel width while it stayed a
 * collapsed chip, squeezing "Parked 6 Aug 17:06" into a third of the row and
 * wrapping it. Worse, it could not be undone — tapping such a chip is a no-op
 * by design, so the flag had no way back down.
 *
 * That is the same desync v0.7.0 fixed *inside* the chip, missed one level up.
 * Sharing the predicate is what stops it happening a third time.
 *
 * Asks about content rather than counting rows, which is what keeps T18P out of
 * it: its week is a single row, but it has a next span worth reading.
 */
internal fun tariffChipOpens(area: TariffArea, dayIndex: Int, minuteOfDay: Int): Boolean =
    tariffNext(area.windows, dayIndex, minuteOfDay) != null ||
        area.windows.any { it.stepNote != null } ||
        weekSchedule(area).size > 1

/** What the tariff-areas button announces it will do, not what it is showing. */
fun tariffToggleLabel(showing: Boolean): String =
    if (showing) "Hide tariff areas" else "Show tariff areas"

/**
 * What tapping the header chip will do. Same "announce the outcome" voice as
 * [tariffToggleLabel] and the focus button: the chevron pictures it, this is
 * what a screen reader says and what a long press shows.
 *
 * **It no longer says "the whole week"**, because the chevron no longer opens
 * one. v0.7.0 moved the week into a sheet behind its own "Whole week" row and
 * left this label describing the old behaviour — so a screen reader was
 * promised a week, and on the areas whose week is a single row (T18P) the panel
 * it opened had no route to one at all. What the chevron actually reveals is
 * what happens after now, and for two areas the rate step.
 *
 * The voice is unchanged: it says the outcome of the next tap, not the current
 * state.
 */
fun weekToggleLabel(expanded: Boolean): String =
    if (expanded) "Hide what happens next" else "Show what happens next"

/**
 * "Set" for the first home zone, "Move" once there is one. The old button said
 * both with a null check inline; here the wording is the whole item, so it is
 * worth being able to hold still.
 */
fun homeZoneMenuLabel(homeZoneSet: Boolean): String =
    if (homeZoneSet) "Move home zone" else "Set home zone"

/**
 * The one label on the walk pill, in the three states it has.
 *
 * **It used to have four**, and the fourth was a guess dressed as a fact. When
 * the last known position was null the pill relabelled itself "Open walk in
 * Maps" — announcing a hand-off to another app on the strength of a fix that had
 * failed, possibly hours earlier, and possibly for a reason that no longer
 * applied. Wasil, 2026-08-08: *"why does it now say walk with google maps even
 * though it always was within the app."* Nothing had been removed; the app had
 * simply stopped being able to see itself, and said so in the wrong tense.
 *
 * The app cannot know whether it has a position until it asks, so the label no
 * longer pretends to. The pill says what it is for, the tap asks, and a read
 * that genuinely fails then hands off to a maps app *and says why* — see
 * `MapScreen`. Announcing the fallback before attempting the thing is the same
 * mistake as publishing a guess, pointed at a button.
 */
fun walkPillText(routing: Boolean, routeSummary: String?): String = when {
    routing -> "Finding the way…"
    routeSummary != null -> "Hide route · $routeSummary"
    else -> "Walk to car"
}

/**
 * One circular control on the map.
 *
 * `surfaceContainer` with a hairline and a shadow, per the mockup. The shadow
 * is not decoration: in light mode a pale circle on pale tiles is nearly a
 * hairline's worth of difference, and the drop shadow is what separates the two
 * when the border alone does not.
 */
@Composable
private fun MapControlButton(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(CONTROL_SIZE),
        shape = CircleShape,
        // The "on" state is a step up the neutral ramp, not a fill. One filled
        // control per screen and it is the walk pill — the thing that starts
        // something, rather than the things that change the view.
        // Active inverts rather than shading. surfaceContainerHighest against
        // surfaceContainer is two neighbouring steps of one warm neutral ramp —
        // a real difference in the palette and almost none on a screen over map
        // tiles. Wasil: "these icons need some change in color when pressed on
        // because the difference is very little and is hardly visible."
        // Inverting is the one change that cannot be missed, needs no new
        // token, and stays out of identity colour, which may never mean
        // "selected".
        color = if (active) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            active -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.onSurface
        },
        border = BorderStroke(
            1.dp,
            if (active) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
        shadowElevation = 3.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * The four map controls, stacked bottom-right.
 *
 * They are not equals, and the stack says so by what is in it. Three are
 * instant and reversible — centre on me, frame both, toggle the tariff layer —
 * and get a circle each. Zone editing is configuration that happens a few times
 * a year; it goes behind the fourth circle rather than sitting at the same
 * weight as a control you tap standing in the street.
 *
 * A menu rather than a bottom sheet: there are two items, both one line, and a
 * sheet that covers a third of the map to offer two lines is a worse trade than
 * the extra millimetre of aim it saves.
 */
@Composable
fun MapControlStack(
    homeZoneSet: Boolean,
    tariffShowing: Boolean,
    tariffEnabled: Boolean,
    facilitiesShowing: Boolean,
    facilitiesEnabled: Boolean,
    locating: Boolean,
    /** How many zones exist — home plus free. Zero hides the list item. */
    zoneCount: Int,
    /** What the next tap of the focus button will centre on. */
    nextFocus: MapFocus,
    onFocus: () -> Unit,
    onToggleTariff: () -> Unit,
    onToggleFacilities: () -> Unit,
    onSetHomeZone: () -> Unit,
    onAddFreeZone: () -> Unit,
    onOpenZoneList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var layersOpen by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(CONTROL_GAP)) {
        // One button, cycling, rather than the two v0.6.4 shipped. Its icon and
        // label say where the *next* tap goes, so it is never a guess: the
        // control announces its outcome rather than its category.
        MapControlButton(
            icon = painterResource(focusIcon(nextFocus)),
            label = focusLabel(nextFocus),
            onClick = onFocus,
            busy = locating,
        )
        // Two layers, so this stopped being a boolean in v0.7.5. It opens rather
        // than toggles, and the reason is that the alternative is worse: one
        // button cycling four combinations is the control nobody can predict,
        // and the stack has no room for a fifth circle.
        //
        // Same DropdownMenu as the zones button below rather than a second kind
        // of panel. The stack already taught that a control here either does a
        // thing or opens a list; adding a third grammar would be the expensive
        // part, not the row.
        Box {
            MapControlButton(
                icon = painterResource(R.drawable.ic_map_layers),
                label = "Layers",
                onClick = { layersOpen = true },
                active = layersOpen || tariffShowing || facilitiesShowing,
                enabled = tariffEnabled || facilitiesEnabled,
            )
            DropdownMenu(
                expanded = layersOpen,
                onDismissRequest = { layersOpen = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                shape = HandoffShapes.Control,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                // The menu stays open on a tap. Layers are compared — on, off,
                // both — and a menu that shut after each one would make trying
                // a combination three round trips.
                LayerToggleItem(
                    label = "Tariff areas",
                    icon = R.drawable.ic_map_layers,
                    checked = tariffShowing,
                    enabled = tariffEnabled,
                    onClick = onToggleTariff,
                )
                LayerToggleItem(
                    label = "Garages & P+R",
                    icon = R.drawable.ic_map_facility,
                    checked = facilitiesShowing,
                    enabled = facilitiesEnabled,
                    onClick = onToggleFacilities,
                )
            }
        }
        Box {
            MapControlButton(
                icon = painterResource(R.drawable.ic_map_more),
                label = "Zones",
                onClick = { menuOpen = true },
                active = menuOpen,
            )
            // Shaped and coloured like the rest of the app. Left stock, it was
            // the one thing on this screen drawn by Material rather than by us —
            // Wasil: "the menu it opens doesnt match the style".
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                shape = HandoffShapes.Control,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                DropdownMenuItem(
                    text = { Text(homeZoneMenuLabel(homeZoneSet)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_zone_home), contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onSetHomeZone()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Add free zone") },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_zone_free), contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onAddFreeZone()
                    },
                )
                // Only once there is something to list. An item that opens an
                // empty room is worse than no item: it makes the menu longer
                // for everyone and answers nobody.
                //
                // Below a separator because the two items above start something
                // new and this one goes to what already exists — the same
                // distinction the stack itself makes between a circle you tap
                // in the street and configuration you touch twice a year.
                if (zoneCount > 0) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline,
                    )
                    DropdownMenuItem(
                        text = { Text(zoneListMenuLabel(zoneCount)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_zone_list), contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onOpenZoneList()
                        },
                    )
                }
            }
        }
    }
}

/**
 * What a facility says when you tap its plate.
 *
 * Four facts the data actually carries — name, kind, address, the council's own
 * page — and, when there is no published rate, one admission.
 *
 * **The admission is the load-bearing part.** Twenty of the thirty-seven have no
 * rate in the register, and the tempting move is to leave the line off and let
 * the absence pass unremarked. A parking sheet with no price reads as *free* to
 * anyone moving quickly, and this app's whole failure history is confident
 * wrongness. So it says what it does not know and points at where the answer
 * lives, which is the same shape the project already chose for paying: state
 * what you know, then hand off.
 *
 * Rates are the operator's own Dutch, unedited — see [RateLine].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FacilitySheet(
    facility: Facility,
    onOpenPage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(facility.name, style = MaterialTheme.typography.titleMedium)
            facility.address?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FacilityTag(
                    when (facility.kind) {
                        FacilityKind.PARK_AND_RIDE -> "P+R"
                        FacilityKind.GARAGE -> "Garage"
                    },
                )
                FacilityTag("Gemeente")
            }

            if (facility.rates.isNotEmpty()) {
                Column(
                    Modifier.padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    facility.rates.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Only when it is not every day — a "ma–vr" on every
                            // row would be noise, and the exception is the point.
                            if (line.days.isNotEmpty()) {
                                Text(
                                    weekdayRange(line.days),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(46.dp),
                                )
                            }
                            Text(line.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(
                    "Published by the operator. Check the page before paying.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            } else {
                Text(
                    when (facility.kind) {
                        FacilityKind.PARK_AND_RIDE ->
                            "Handoff has no published rate for this P+R. Most charge a flat " +
                                "day rate if you travel on by public transport."
                        FacilityKind.GARAGE ->
                            "Handoff has no published rate for this garage. Garages charge by " +
                                "how long you stay, and the app only reads street tariffs."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            facility.url?.let { url ->
                Button(
                    onClick = { onOpenPage(url) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    shape = HandoffShapes.Control,
                ) {
                    Text(
                        if (facility.rates.isEmpty()) "Rates on amsterdam.nl" else "Open on amsterdam.nl",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun FacilityTag(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, HandoffShapes.Control)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * "ma–vr" for a contiguous run, "ma, wo, vr" otherwise. Dutch abbreviations,
 * matching the tariff timetable this app already renders.
 */
internal fun weekdayRange(days: Set<Int>): String {
    val names = listOf("ma", "di", "wo", "do", "vr", "za", "zo")
    val sorted = days.sorted()
    if (sorted.isEmpty()) return ""
    val contiguous = sorted.zipWithNext().all { (a, b) -> b == a + 1 }
    return if (contiguous && sorted.size > 2) {
        "${names[sorted.first() - 1]}–${names[sorted.last() - 1]}"
    } else {
        sorted.joinToString(", ") { names[it - 1] }
    }
}

/**
 * One row of the layers menu: an icon, a name, and a box that says whether the
 * layer is on.
 *
 * The tick is drawn as a filled or hollow square rather than a `Checkbox`,
 * because a stock checkbox pulls its colour from the generic `primary` slot,
 * and this app's `primary` is a near-white neutral by rule — identity colour
 * may never enter a generic slot. A filled square in `onSurface` says the same
 * thing and stays inside the palette.
 *
 * Disabled when the data behind the layer is missing, rather than hidden: a row
 * that disappears makes the menu a different shape on a broken install, and
 * "Garages & P+R" greyed out is a truer statement than no row at all.
 */
@Composable
private fun LayerToggleItem(
    label: String,
    @DrawableRes icon: Int,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        enabled = enabled,
        text = { Text(label) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
        trailingIcon = {
            Box(
                Modifier
                    .size(16.dp)
                    .border(
                        width = 1.5.dp,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(2.dp)
                    .background(
                        color = if (checked) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        },
        onClick = onClick,
    )
}

/**
 * "Walk to car", as a pill rather than the full-width slab it was.
 *
 * Full width made it the loudest thing on the screen and pinned a whole row of
 * map underneath it. A pill says the same thing at the size of the sentence it
 * contains. It keeps the default button colours on purpose — those resolve to
 * the text colour filled with the surface colour, which is the one deliberately
 * assertive control the mockup allows, and it is the one that starts something.
 *
 * The caller gives it side padding wide enough to clear the control stack; the
 * label grows by half its length when a route is drawn ("Hide route · 87 min ·
 * 7.3 km"), and a pill that slid under the circles would be the obvious way to
 * get this wrong. The ellipsis is a floor, not a plan.
 */
@Composable
fun WalkPill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier, shape = CircleShape) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The header, over the tiles instead of above them.
 *
 * Two halves: what screen this is and what the pin means, then the tariff chip.
 * Both get their own translucent backing, because both would otherwise be bare
 * text on streets.
 *
 * [chipExpanded] widens the chip's share of the row, and does nothing else.
 *
 * It used to hide the left half outright, arguing that a week of days, hours and
 * rates needs more than half the width and that the title is the least
 * surprising thing on screen — it says "Map", on the map tab. The first half of
 * that is true; the second was wrong, because the *line underneath* the title is
 * "Car parked 7 aug 23:10", which is how you know the pin is real and when it
 * was set. It vanished exactly when you opened the thing beside it. Wasil,
 * 2026-08-08: *"i like the timetable you did now, so it expands but then you go
 * and remove the parked thing (upper left corner)."*
 *
 * Both halves stay. The chip takes two thirds of the row instead of all of it,
 * and the timetable grows **downward from the chip** rather than appearing at
 * the opposite end of the screen from its own heading — see [TariffChip]. The
 * room below the header is free: the control stack and the walk pill are both
 * anchored to the bottom.
 */
@Composable
fun MapHeaderOverlay(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    chipExpanded: Boolean = false,
    chip: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            // fill = false so the backing wraps its two lines instead of
            // claiming half the width. A translucent slab over map it is not
            // saying anything about is just a smudge on the tiles.
            modifier = Modifier.weight(1f, fill = false),
            shape = HandoffShapes.Control,
            color = MaterialTheme.colorScheme.surface.copy(alpha = OVER_TILES_ALPHA),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (chip != null) {
            Box(
                if (chipExpanded) {
                    // Two thirds, not all of it. Enough for the table's three
                    // columns while the parked line keeps its own corner.
                    Modifier.weight(2f).padding(start = 10.dp)
                } else {
                    Modifier.weight(1f, fill = false).padding(start = 10.dp)
                },
                contentAlignment = Alignment.TopEnd,
            ) {
                chip()
            }
        }
    }
}

/**
 * What the highlighted area is called, what it costs right now, and — when you
 * tap it — the whole week.
 *
 * **Why this is one control and not two.** v0.6.6 put the week in its own
 * floating card below, so the rate appeared twice on the same screen in two
 * shapes. Wasil, 2026-08-07: *"now when you do that you will see the rate 2
 * times, my initial idea was that the small thing expands instead of another
 * one."* He is right, and the merged version is smaller in every state: one
 * heading, one rate, and the timetable folded behind a chevron.
 *
 * **Where it says the code, and where it says the name.** Nowhere and
 * everywhere, respectively. Wasil, 2026-08-08, holding a screenshot whose header
 * read *"Noord / Molenwijk"* over a panel reading *"Noord / T13B"*: *"i need the
 * names back underneath Noord. and also remove the area codes again and replace
 * them with what is written underneath Noord (for every section their own thing
 * ofcourse)."*
 *
 * That is a rule, not a tweak, and it is the same one that removed the zone code
 * from the wire in v0.6.3 and put buurt names on the map header in v0.6.6: **a
 * person reads the name of a place and never the code.** So the expanded view's
 * second line is [placeDetail] — the neighbourhood of the area you actually
 * tapped, resolved per selection, which is what "for every section their own
 * thing" asks for. `T13B` survives in exactly one place: as a heading of last
 * resort when no name resolved at all, because a code still beats a blank.
 *
 * The detail line is expanded-only. Collapsed, the chip carried it and he asked
 * for it gone — *"in the small timetable i see the streetname of where i press
 * (unnecessary for now)"* — and the two asks agree rather than conflict: a
 * collapsed chip is glanced at, an open panel is read.
 *
 * The chevron is not decoration. The week has been in the app for a version and
 * unreachable in practice (see `MapScreen`'s tap handler), so the control that
 * opens it has to say out loud that it opens something.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TariffChip(
    area: TariffArea,
    /** The place name, or null while the lookup is still in flight. */
    placeName: String?,
    /**
     * The neighbourhood inside [placeName] — "Molenwijk" under "Noord" — for the
     * area that is actually selected. Shown only while expanded, and it is what
     * replaced the tariff code there.
     */
    placeDetail: String?,
    /** False while resolving, so the heading can stay blank rather than flicker a code. */
    placeResolved: Boolean,
    dayIndex: Int,
    minuteOfDay: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(area) { weekSchedule(area) }
    val nextSpan = remember(area, dayIndex, minuteOfDay) {
        tariffNext(area.windows, dayIndex, minuteOfDay)
    }
    // The two duration-priced areas. Taken from any window, because a stepped
    // key applies to the whole area rather than to one band.
    val stepNote = remember(area) { area.windows.firstNotNullOfOrNull { it.stepNote } }
    var showWeek by remember { mutableStateOf(false) }

    // A chevron that opens nothing should not be drawn — the same rule the week
    // link follows. T11V, T12V and T13V charge every minute of the week, so
    // they have no next span, and their week is a single row; there is genuinely
    // nothing behind the control for them.
    //
    // Via [tariffChipOpens] so the header row cannot disagree with the chip
    // about it — see that function for what happened when they did.
    val opensSomething = remember(area, dayIndex, minuteOfDay) {
        tariffChipOpens(area, dayIndex, minuteOfDay)
    }

    // One flag for "is this actually a panel right now", used for the body, the
    // fill, the border and the elevation together. They were keyed on
    // `expanded` alone, which is the caller's and outlives a change of area.
    val showingPanel = expanded && opensSomething

    // ── The open, as one movement ─────────────────────────────────────────
    //
    // `animateContentSize()` used to do this, and the brief for v0.7.0 said to
    // swap its tween for a spring. That was wrong twice over, and both halves
    // are worth writing down.
    //
    // First, it was never a tween: `animateContentSize()`'s default is already
    // `spring(dampingRatio = NoBouncy, stiffness = MediumLow)`. Retuning it
    // would have been a release spent on nothing.
    //
    // Second, the reason it looked cheap is not the curve. The factory is
    // `clipToBounds() then SizeAnimationModifier`, and it was applied to
    // `modifier` — *outside* the Surface. So the Surface was measured and drawn
    // at its full open size and then clipped to a growing rectangle anchored
    // top-left: for the whole open, the right and bottom edges were square
    // cuts with no rounded corner, no outline and no shadow, and they snapped
    // round on the final frame.
    //
    // Meanwhile everything that is not size — fill, border, elevation, chevron
    // — changed in a single frame at the start. The card took on its whole new
    // material identity instantly and then slowly grew into it. Four properties
    // at four rates, three of them infinite.
    //
    // So: the size animation moves *inside* the Surface, where it grows a child
    // and the Surface redraws its own corners, border and shadow at the true
    // size every frame; and the other three ride one transition so they arrive
    // with the growth rather than before it.
    val opening = updateTransition(showingPanel, label = "tariffChip")
    val container by opening.animateColor(
        transitionSpec = { EFFECTS_COLOR },
        label = "container",
    ) { open ->
        val base = MaterialTheme.colorScheme.surfaceVariant
        // Translucent as a chip, opaque as a panel — a change of job, not an
        // inconsistency. One line of rate over streets is what OVER_TILES_ALPHA
        // was measured for. A table of days and prices is read rather than
        // glanced at, and at 0.92 the streets run straight through the digits.
        if (open) base else base.copy(alpha = OVER_TILES_ALPHA)
    }
    // Faded rather than switched on: a border that appears takes no layout, so
    // alpha 0 is genuinely nothing on screen and there is no jump.
    val edge by opening.animateColor(transitionSpec = { EFFECTS_COLOR }, label = "edge") { open ->
        MaterialTheme.colorScheme.outline.copy(alpha = if (open) 1f else 0f)
    }
    val lift by opening.animateDp(transitionSpec = { EFFECTS_DP }, label = "lift") { open ->
        if (open) 3.dp else 0.dp
    }
    // Held as State, not unwrapped with `by`: it is read inside graphicsLayer,
    // so the rotation never enters composition and costs nothing per frame.
    val chevron = opening.animateFloat(transitionSpec = { CHEVRON_SPIN }, label = "chevron") { open ->
        if (open) 180f else 0f
    }

    Surface(
        onClick = { if (opensSomething) onToggle() },
        modifier = modifier,
        shape = HandoffShapes.Control,
        color = container,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, edge),
        shadowElevation = lift,
    ) {
        // Bottom padding goes to zero once the week row is present: that row is
        // 40dp of tap target with a 17dp line in it, so it already supplies the
        // breathing room the padding was for, and paying twice is what made the
        // panel look bottom-heavy.
        Column(
            Modifier.padding(
                start = 10.dp,
                end = 10.dp,
                top = 7.dp,
                bottom = if (showingPanel && rows.size > 1) 0.dp else 7.dp,
            ),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f, fill = showingPanel)) {
                    // While the lookup is in flight this stays blank rather than
                    // showing the code and swapping it a moment later. The code
                    // appears only once we know no name is coming.
                    val heading = placeName ?: area.code.takeIf { placeResolved }
                    if (heading != null) {
                        Text(heading, style = MaterialTheme.typography.titleMedium)
                    }
                    // The neighbourhood, where the code used to be. Shown in
                    // both states: it was expanded-only and Wasil lost it —
                    // "i dont see the small area anymore (for example
                    // Molenwijk) before i enlargen the table". It is the line
                    // that says *where*, so hiding it until asked was backwards.
                    // Still suppressed when it only repeats the heading.
                    if (placeDetail != null && placeDetail != heading) {
                        Text(
                            placeDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        tariffNowText(
                            tariffNow(area.windows, dayIndex, minuteOfDay),
                            dayIndex,
                            minuteOfDay,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (opensSomething) {
                    // One glyph rotated, not two glyphs swapped. The swap was a
                    // hard cut in the middle of a soft movement, and the two
                    // arrows are the same centred chevron anyway.
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = weekToggleLabel(showingPanel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 6.dp, top = 1.dp)
                            .size(18.dp)
                            .graphicsLayer { rotationZ = chevron.value },
                    )
                }
            }
            // `expanded` belongs to the caller and survives a change of area, so
            // it has to be gated on there being something to show as well.
            // Without the second half: expand a normal area, then tap a
            // round-the-clock one, and the panel keeps its border and elevation
            // and draws a divider with nothing underneath. Seen on the emulator
            // — Centrum, which charges every minute and has a one-row week.
            // AnimatedVisibility, not animateContentSize on the Surface: the
            // growth happens to a *child*, so the Surface measures itself
            // around it and redraws its rounded corners, border and shadow at
            // the true size on every frame. That is the whole fix for the
            // square-cut edges — see the transition above.
            //
            // Opening is a touch springy and closing is fast and flat: a panel
            // over a map is opened deliberately and dismissed impatiently, and
            // an exit that lingers reads as lag rather than as grace.
            AnimatedVisibility(
                visible = showingPanel,
                enter = expandVertically(animationSpec = OPEN_SIZE) +
                    fadeIn(animationSpec = tween(durationMillis = 90, delayMillis = 30)),
                exit = shrinkVertically(animationSpec = CLOSE_SIZE) +
                    fadeOut(animationSpec = tween(durationMillis = 60)),
            ) {
                Column {
                HorizontalDivider(
                    Modifier.padding(top = 8.dp, bottom = 2.dp),
                    color = MaterialTheme.colorScheme.outline,
                )
                if (rows.isEmpty()) {
                    Text(
                        "No hours published for this area.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // What happens after now — the one fact the collapsed line does
                // not carry, as a sentence rather than a row.
                //
                // It was drawn as a columned row first (day gutter, start time,
                // end time) and that shape cannot tell the truth here: the next
                // span crosses a midnight in 26 of the 29 areas, and *always*
                // in 19 of them, so a single day column made T17N on a Friday
                // evening read "za Free 06:00 → 19:00" for a gap that runs to
                // Sunday. Thirty-seven hours rendered as thirteen — the exact
                // defect [clockAhead] was written to remove, reinstated one
                // line below it.
                //
                // A clause has no such problem, because it hands the boundary to
                // clockAhead, which already names the day and already writes
                // midnight as 24:00. It is also shorter, and it drops a
                // duplication the row could not avoid: the row's start time was
                // always the boundary the line above had just given.
                nextSpan?.let { next ->
                    Text(
                        nextSpanText(next, dayIndex, minuteOfDay),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }

                // Only the two areas that price by duration. Shown here and not
                // on the collapsed line because the line has "from" doing that
                // job in one word, and this is the sentence behind it.
                stepNote?.let { note ->
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }

                // The week, one tap further. Not deleted — Wasil asked for it in
                // v0.6.6 ("maybe i am curious about tommorows rate") and that
                // still holds; it is simply no longer the thing you must read
                // to find out what you are paying now.
                //
                // Its own row, because 22dp inside a Surface whose own click
                // *collapses the panel* means a near miss shuts the thing you
                // were reading. 40dp, not the 46dp first tried: centred text in
                // a 46dp row put 14dp of nothing above and below a 17dp line,
                // and Wasil saw it straight away — "too big of a negative space
                // on whole week". 40dp is what this app's own text buttons use,
                // and the panel's bottom padding is dropped below it because the
                // row is already carrying that space.
                if (rows.size > 1) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clickable { showWeek = true },
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Whole week",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                }
            }
        }
    }

    if (showWeek) {
        ModalBottomSheet(
            onDismissRequest = { showWeek = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            // The stock handle carries 22dp of padding above and below, which on
            // a sheet holding two rows of table is most of what you see. A
            // 4dp bar with 8dp around it says "drag me" just as well and gives
            // the content back about 28dp.
            dragHandle = {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                CircleShape,
                            ),
                    )
                }
            },
        ) {
            WeekSheet(
                heading = placeName ?: area.code,
                detail = placeDetail,
                rows = rows,
                dayIndex = dayIndex,
                minuteOfDay = minuteOfDay,
            )
        }
    }
}

/**
 * The v0.6.9 table, moved rather than rewritten.
 *
 * Same rows from [weekSchedule], same "the line in force is the only one at
 * full strength" treatment. It reads better here than it did in the panel for a
 * reason worth stating: a sheet is read, and the panel is glanced at, and this
 * was always a thing to read.
 */
@Composable
private fun WeekSheet(
    heading: String,
    detail: String?,
    rows: List<ScheduleRow>,
    dayIndex: Int,
    minuteOfDay: Int,
) {
    val now = remember(rows, dayIndex, minuteOfDay) { activeRow(rows, dayIndex, minuteOfDay) }
    // Tight. A sheet holding two lines of table does not need a room around it,
    // and the first pass gave it 28dp below plus 6dp on every row on top of the
    // drag handle's own inset — Wasil: "same for the whole week itself". The
    // sheet already floats; the space between it and the screen edge is the
    // scrim's job, not the content's.
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
        Text(heading, style = MaterialTheme.typography.titleMedium)
        if (detail != null && detail != heading) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(
            Modifier.padding(top = 8.dp, bottom = 2.dp),
            color = MaterialTheme.colorScheme.outline,
        )
        rows.forEach { row ->
            val live = row === now
            val ink = if (live) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            val weight = if (live) FontWeight.Medium else FontWeight.Normal
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.days,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                    fontWeight = weight,
                    modifier = Modifier.width(86.dp),
                )
                Text(
                    row.hours ?: "free all day",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                    fontWeight = weight,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    row.rate.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                    fontWeight = weight,
                )
            }
        }
    }
}

/**
 * "then free until zo 19:00", or "then €1,72/h until ma 06:00".
 *
 * Deliberately names only the far edge of the next span. Its near edge is the
 * end of the current state, which the line directly above has already given —
 * saying it twice is what made the row form redundant as well as wrong.
 */
internal fun nextSpanText(next: TariffNext, dayOfWeek: Int, minuteOfDay: Int): String {
    val until = clockAhead(dayOfWeek, minuteOfDay, next.endsInMin)
    return if (next.charging) "then ${next.rateText} until $until" else "then free until $until"
}


/**
 * The OpenStreetMap credit. Required by the tile licence, so it is never
 * conditional and never truncated — but it is a legal line rather than a
 * feature, so it is the smallest thing on the screen and it costs no layout.
 */
@Composable
fun MapAttribution(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = OVER_TILES_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            "© OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/**
 * A transient line over the map, used for the one thing the controls can fail
 * at: asking the phone where it is and being told nothing.
 *
 * It says we do not know, rather than moving the map somewhere plausible. A
 * failed position read is the case this project has a rule about — the
 * expensive direction to be wrong in is the confident one.
 */
@Composable
fun MapNotice(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = HandoffShapes.Control,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * The icon for what the next tap will do — not for what the map is showing now.
 * A control that pictures its outcome needs no explaining.
 */
internal fun focusIcon(next: MapFocus): Int = when (next) {
    MapFocus.ME -> R.drawable.ic_map_locate
    MapFocus.BOTH -> R.drawable.ic_map_frame
    MapFocus.CAR -> R.drawable.ic_map_car
}

/** Screen-reader and long-press label, in the same "what happens next" voice. */
internal fun focusLabel(next: MapFocus): String = when (next) {
    MapFocus.ME -> "Centre on my position"
    MapFocus.BOTH -> "Frame the car and me"
    MapFocus.CAR -> "Centre on the car"
}

