package dev.wasil.permit.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.tariffNow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
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

/** What the tariff-areas button announces it will do, not what it is showing. */
fun tariffToggleLabel(showing: Boolean): String =
    if (showing) "Hide tariff areas" else "Show tariff areas"

/**
 * What tapping the header chip will do. Same "announce the outcome" voice as
 * [tariffToggleLabel] and the focus button: the chevron pictures it, this is
 * what a screen reader says and what a long press shows.
 */
fun weekToggleLabel(expanded: Boolean): String =
    if (expanded) "Hide the whole week" else "Show the whole week"

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
    locating: Boolean,
    /** How many zones exist — home plus free. Zero hides the list item. */
    zoneCount: Int,
    /** What the next tap of the focus button will centre on. */
    nextFocus: MapFocus,
    onFocus: () -> Unit,
    onToggleTariff: () -> Unit,
    onSetHomeZone: () -> Unit,
    onAddFreeZone: () -> Unit,
    onOpenZoneList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
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
        MapControlButton(
            icon = painterResource(R.drawable.ic_map_layers),
            label = tariffToggleLabel(tariffShowing),
            onClick = onToggleTariff,
            active = tariffShowing,
            enabled = tariffEnabled,
        )
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
    Surface(
        onClick = onToggle,
        // The grow is animated so the chip reads as the same object getting
        // bigger. Snapping between two sizes in the same place is what made the
        // old pair look like two cards in the first place.
        modifier = modifier.animateContentSize(),
        shape = HandoffShapes.Control,
        // Translucent as a chip, opaque as a panel — and that is a change of
        // job, not an inconsistency. One line of rate over streets is what
        // OVER_TILES_ALPHA was measured for, and seeing the map continue behind
        // a small chip is worth having. A table of days, times and prices is
        // read rather than glanced at, and at 0.92 the streets underneath run
        // straight through the digits: seen on the emulator in dark mode, where
        // Centraal Station was legible through the Sunday row. Nothing on this
        // screen has ever been made harder to read on purpose.
        color = if (expanded) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = OVER_TILES_ALPHA)
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        // The panel needs an edge that the tiles cannot supply, now that it is
        // no longer translucent enough for the map to draw one for it.
        border = if (expanded) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        } else {
            null
        },
        shadowElevation = if (expanded) 3.dp else 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f, fill = expanded)) {
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
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = weekToggleLabel(expanded),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, top = 1.dp).size(18.dp),
                )
            }
            if (expanded) {
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
                // Exactly one line is in force, and it is the one the reader
                // came for. Everything else is dimmed rather than the active
                // line being decorated, so the answer is found by looking
                // rather than by comparing three prices against a clock —
                // Wasil: "i see 3 prices i dont know which one is the correct
                // one." Weight and colour together, so it survives a screen in
                // sunlight and does not depend on colour alone.
                val now = remember(rows, dayIndex, minuteOfDay) {
                    activeRow(rows, dayIndex, minuteOfDay)
                }
                rows.forEach { row ->
                    val live = row === now
                    // No alpha. v0.6.9 dimmed the non-live rows to 0.65 on top
                    // of using a quieter token, and the two together put them
                    // under the readability floor: measured 2.91:1 in dark and
                    // 2.71:1 in light against the 4.5:1 AA needs for body text.
                    // Reaching 4.5 would take alpha 0.93, which is not a dim at
                    // all, so the alpha was never the right instrument.
                    //
                    // The distinction survives without it, because it never
                    // rested on the alpha: live is onSurface at Medium (12.0:1
                    // dark, 15.5:1 light), not-live is onSurfaceVariant at
                    // Normal (5.03:1 / 5.55:1). Two tokens and two weights,
                    // both legible — which is what "i see 3 prices i dont know
                    // which one is the correct one" actually asked for. A row
                    // you cannot read is not a quiet answer, it is a missing
                    // one, and this panel is read outdoors.
                    val ink = when {
                        live -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val weight = if (live) FontWeight.Medium else FontWeight.Normal
                    Row(
                        Modifier.fillMaxWidth().padding(top = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            row.days,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ink,
                            fontWeight = weight,
                            modifier = Modifier.width(78.dp),
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
    }
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

