package dev.wasil.permit.ui

import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.areaSizeText
import dev.wasil.permit.parking.areaSqKm
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.distanceMeters
import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.TariffNow
import dev.wasil.permit.parking.zones.ZonePolygon
import dev.wasil.permit.parking.containsPoint
import dev.wasil.permit.parking.zones.pointInPolygon
import kotlin.math.ceil
import kotlin.math.floor

/** The smallest and largest a zone circle can be, and the size a fresh one starts at.
 *
 * Matches the existing Home zone slider range (see docs/BACKLOG.md's locked
 * decision: "Home zone is a small circle (30-200 m), deliberately not an
 * Amsterdam neighbourhood polygon"). Free zones get the same range for the
 * same reason — a marked-free spot that sprawls stops meaning "here".
 */
const val ZONE_RADIUS_MIN_M = 30.0
const val ZONE_RADIUS_MAX_M = 200.0
const val ZONE_RADIUS_DEFAULT_M = 60.0

/**
 * How much one press of − or + moves the radius.
 *
 * Five metres because that is roughly a parked car: the step a person means
 * when they say "a bit bigger". A 1 m step would need forty presses to cross
 * the range and would be a slower slider; a 10 m step cannot express the
 * difference between one side of a street and both.
 */
const val ZONE_RADIUS_STEP_M = 5.0

fun clampZoneRadius(radiusM: Double): Double = radiusM.coerceIn(ZONE_RADIUS_MIN_M, ZONE_RADIUS_MAX_M)

/**
 * The radius [steps] presses away, on the 5 m grid.
 *
 * A value left off the grid by a slider drag snaps **towards the press**: from
 * 63.4 m, + gives 65 and − gives 60. Rounding to the nearest multiple first
 * would make the first + jump to 70 — two steps for one press, from a number
 * the user never chose. Clamped, so holding + at the top of the range does
 * nothing rather than storing a value the slider cannot show.
 */
fun stepZoneRadius(radiusM: Double, steps: Int): Double {
    val gridsteps = radiusM / ZONE_RADIUS_STEP_M
    val from = if (steps >= 0) floor(gridsteps) else ceil(gridsteps)
    return clampZoneRadius((from + steps) * ZONE_RADIUS_STEP_M)
}

/**
 * A radius typed into the field, or null while the text is not yet a number.
 *
 * **Deliberately not clamped.** "3" on the way to "30" must not snap the circle
 * to the minimum under the finger, and a field that rewrites what you are
 * typing is the reason people give up on typed inputs. Clamping happens once,
 * when the value is committed — see [clampZoneRadius].
 *
 * A comma is accepted because this is a Dutch app and a Dutch keyboard offers
 * one; a trailing "m" because the field says "m" beside it and people type it
 * anyway.
 */
fun parseZoneRadius(text: String): Double? =
    text.trim().removeSuffix("m").trim().replace(',', '.')
        .toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0 }

/** What the radius field shows: whole metres, no unit — the unit is beside it. */
fun radiusFieldText(radiusM: Double): String = Math.round(radiusM).toString()

/** Identifies one zone circle on the map: the single home zone, or one entry in
 * the free-zone list (by index, matching [dev.wasil.permit.parking.FreeZoneStore]'s
 * own indexing). */
sealed interface ZoneRef {
    data object Home : ZoneRef
    data class Free(val index: Int) : ZoneRef
}

/**
 * One row of "Your zones": which zone it is, what to call it, and how big.
 *
 * The list exists because until now the only way back to a zone was to find its
 * circle on the map and hit it — which needs you to remember where you put it
 * and to be looking at that part of the city. A zone placed at your mother's
 * street six weeks ago was, in practice, unreachable.
 *
 * Pure so the ordering and the fallback naming can be held still by a test: the
 * indices in [ZoneRef.Free] address the store directly, so a list that reordered
 * itself would rename or delete the wrong zone.
 */
data class ZoneEntry(val ref: ZoneRef, val label: String, val zone: FreeZone) {
    val radiusM: Double get() = zone.radiusM
    /** True when this row is a neighbourhood rather than a circle. */
    val isArea: Boolean get() = zone.isArea
}

/**
 * The size column in "Your zones": "60 m" for a circle, "0.6 km²" for a
 * neighbourhood, and the bare word when a neighbourhood's boundary is missing.
 *
 * The two units are the honest ones rather than a formatting preference. A
 * radius is the only number a circle has; a radius means nothing for a buurt,
 * and a buurt is described by how much ground it covers. Forcing both into one
 * unit would make one of them a lie.
 */
fun zoneSizeText(zone: FreeZone, areaShape: (String) -> List<ZonePolygon>?): String {
    val buurt = zone.buurt ?: return "${radiusFieldText(zone.radiusM)} m"
    return areaShape(buurt)?.let { areaSizeText(areaSqKm(it)) } ?: "area"
}

/**
 * What the app says when no published area covers the spot that was tapped.
 *
 * **The scope of the app catching up with itself.** The tariff data is
 * Amsterdam's, the 518 neighbourhoods are Amsterdam's, and the permit is an
 * Amsterdam visitor permit. Free zones were the one feature that happened to
 * work anywhere, and only because a circle does not need to know what city it is
 * in. Losing that is not a regression — it is the app no longer implying a reach
 * it never had. Wasil on the rest of the country, 2026-08-08: *"Utrecht will get
 * its own update hahaha."*
 *
 * **The wording is about this spot, not about a city, and that is deliberate.**
 * Coverage is the extent of the bundled polygons — *"just where the amsterdam
 * jurisdiction goes over. so all the places on the map we got from amsterdam
 * vergunning parkeren"* — so the honest sentence is that nothing is published
 * here. "Only works in Amsterdam" would be vaguer *and*, at the edges, false:
 * Weesp is in the municipality and in this data while not taking the
 * "Amsterdam-" prefix, which is the same trap the zone-registry work hit when it
 * declined to synthesise that prefix. Scope is answered by containment, never by
 * a name.
 */
const val NO_NEIGHBOURHOOD_HERE: String =
    "A free zone is one of the neighbourhoods the city publishes, and none of them covers " +
        "this spot. Tap inside a published area, or cancel."

/**
 * Home first, then the free zones **in store order** — never sorted. The index
 * inside [ZoneRef.Free] is the store's own index, so any reordering here would
 * point the editor at a different zone than the row the user tapped.
 *
 * A blank label falls back to coordinates rather than to an empty row, matching
 * what the map's own editor does when you save a nameless zone.
 */
fun zoneEntries(home: FreeZone?, freeZones: List<FreeZone>): List<ZoneEntry> = buildList {
    home?.let { add(ZoneEntry(ZoneRef.Home, it.displayLabel(), it)) }
    freeZones.forEachIndexed { index, zone ->
        add(ZoneEntry(ZoneRef.Free(index), zone.displayLabel(), zone))
    }
}

private fun FreeZone.displayLabel(): String =
    label.trim().ifBlank { formatCoordinates(lat, lng) }

/** "Your zones · 3", or the bare noun when there is nothing to count. */
fun zoneListMenuLabel(count: Int): String =
    if (count > 0) "Your zones · $count" else "Your zones"

/**
 * "Mark Molenwijk as free?" — the whole free-zone flow in one question.
 *
 * Phrased as a question rather than as a label, because it is one: the app has
 * worked out what the place is called and is asking whether that is what was
 * meant. Against the old flow — drop a pin, drag a radius, hope — this is one
 * tap and one confirmation, and the boundary is the city's rather than a guess.
 */
fun zoneAreaOffer(name: String): String = "Mark $name as free?"

/**
 * The line under it, and the size is the point of it.
 *
 * Marking an area free is a standing instruction never to claim the permit
 * anywhere inside it, and Amsterdam's buurten run from a few streets to most of
 * a district. Being wrong costs a fine, so the number goes in front of the
 * person choosing. Null when the size could not be worked out — which says
 * nothing about the boundary itself, so the offer stands without it rather than
 * inventing a figure.
 */
fun zoneAreaOfferDetail(sizeText: String?): String =
    listOfNotNull(
        "The whole neighbourhood, with the city's own boundary",
        sizeText?.let { "about $it" },
    ).joinToString(" · ") + "."

/**
 * How much ground is about to be marked free — at full strength, not as a
 * footnote.
 *
 * **This is the one moment a person can tell a street corner from half a
 * district.** Marking a buurt free is a standing instruction never to claim the
 * permit anywhere inside it, and Amsterdam's buurten run from a few streets to
 * most of a stadsdeel. Get it wrong on a large one and protection is switched
 * off across a whole neighbourhood — and the failure is *silent*, because what
 * goes wrong is that nothing happens. Nothing happening is exactly what a fine
 * looks like beforehand.
 *
 * Confirmed as a requirement by Wasil, 2026-08-08: *"last point was good that
 * some buurten are large and that a small indicator is good for it."*
 *
 * The number is stated and never editorialised on. An unusually large buurt
 * shows up as a large number, which is the whole job; adding "that's big!" would
 * be the app guessing at what somebody meant to do.
 */
fun zoneAreaSizeLine(sizeText: String?): String =
    sizeText?.let { "$it · the whole neighbourhood" } ?: "The whole neighbourhood"

/** Why the size above matters, in one sentence, under it. */
const val ZONE_AREA_CONSEQUENCE: String =
    "The permit will never be claimed anywhere inside it."

/**
 * Which existing zone circle, if any, contains [point] — used to let a tap on
 * the map remove a zone instead of needing a separate list. When circles
 * overlap, the one whose centre is nearest to the tap wins, so a tap near the
 * shared edge of a big zone and a small zone inside it hits the small one.
 */
fun zoneHitAt(
    point: GeoPoint,
    home: FreeZone?,
    freeZones: List<FreeZone>,
    /** Boundaries for the area-backed zones; see [FreeZone.buurt]. */
    areaShape: (String) -> List<ZonePolygon>? = { null },
): ZoneRef? {
    val hits = buildList {
        // The home zone is tested as the circle it is, and the free zones as the
        // neighbourhoods they are. Two rules because there are two kinds of
        // thing — a home is a point you own, a free zone is an area the city has
        // drawn — and running the area rule over the home zone made it
        // untappable, since a home zone has no neighbourhood on it.
        home?.let { zone ->
            if (distanceMeters(point, zone.centre()) <= zone.radiusM) add(ZoneRef.Home to zone)
        }
        freeZones.forEachIndexed { index, zone ->
            if (containsPoint(zone, point, areaShape)) add(ZoneRef.Free(index) to zone)
        }
    }
    // Nearest centre still breaks a tie, and an area-backed zone still has one:
    // the point that was tapped when it was made. It is a worse centre than a
    // circle's — a neighbourhood is not radially symmetrical — but this only
    // ever decides *which* of two overlapping zones a tap opens, and both are
    // then editable. Precision here would be geometry for its own sake.
    return hits.minByOrNull { (_, zone) -> distanceMeters(point, zone.centre()) }?.first
}


private fun FreeZone.centre(): GeoPoint = GeoPoint(lat, lng, 0f)

/**
 * What a tap on bare map landed on.
 *
 * The car marker is absent on purpose: osmdroid hit-tests markers itself, and
 * the car marker's own click listener consumes the tap before the map events
 * overlay ever sees it.
 */
sealed interface MapHit {
    data class Zone(val ref: ZoneRef) : MapHit
    data class Tariff(val hit: TariffHit) : MapHit
}

/**
 * A tariff area *and the single part of it* the point fell in.
 *
 * The part matters. 21 of Amsterdam's 29 tariff areas are multi-part — T13B
 * alone is 16 disjoint pieces scattered across the city — so highlighting a
 * whole [TariffArea] lit up every piece sharing its code at once. Reported
 * 2026-08-02: "when i press 1 section multiple other sections light up".
 */
data class TariffHit(val area: TariffArea, val ring: ZonePolygon)

/**
 * The single precedence rule for a tap on bare map:
 *
 *  1. Zone circles — home and free — by the existing nearest-centre rule.
 *  2. Tariff areas, and only the ones passed in.
 *  3. Nothing.
 *
 * Zones beat tariff areas because a zone is something placed by hand, while a
 * tariff area is neighbourhood-sized and will almost always be under the tap as
 * well: the specific thing beats the ambient thing.
 *
 * Callers pass an empty [tariffAreas] when the overlay is switched off, so the
 * toggle needs no branch of its own. Callers must not call this at all while
 * placing a zone candidate or moving the car pin — in those modes every tap is
 * a placement, and that is decided before this is reached.
 */
fun mapHitAt(
    point: GeoPoint,
    home: FreeZone?,
    freeZones: List<FreeZone>,
    tariffAreas: List<TariffArea>,
    areaShape: (String) -> List<ZonePolygon>? = { null },
): MapHit? {
    zoneHitAt(point, home, freeZones, areaShape)?.let { return MapHit.Zone(it) }
    return tariffHitAt(point, tariffAreas)?.let { MapHit.Tariff(it) }
}

/** The area *and part* containing [point]. */
fun tariffHitAt(point: GeoPoint, areas: List<TariffArea>): TariffHit? {
    val p = LatLng(point.lat, point.lng)
    for (area in areas) {
        area.polygons.firstOrNull { pointInPolygon(p, it) }
            ?.let { return TariffHit(area, it) }
    }
    return null
}

/**
 * The first tariff area containing [point] — matching how
 * [dev.wasil.permit.parking.zones.ZoneResolver] picks one, so the map can never
 * disagree with the claim decision about which area you are in.
 */
fun tariffAreaAt(point: GeoPoint, areas: List<TariffArea>): TariffArea? =
    tariffHitAt(point, areas)?.area

/**
 * Rate first, then the description that carries the hours — the rate is the
 * part worth reading at a glance. Amsterdam's own comma decimal is kept.
 */
fun tariffSummary(area: TariffArea): String =
    listOf(area.tariffText, area.name).filter { it.isNotBlank() }.joinToString(" · ")

/**
 * Where the schedule starts inside an area's name. Amsterdam prefixes every one
 * with its rate class — "Basistarief TC1 ma-zo 00-24" — which is the part the
 * rate already tells you, and which pushed the map header onto three lines.
 *
 * Matches a day token **or** a time range, whichever comes first. A day token
 * alone was not enough: "Basistarief TC7 19-06, niet za op zo" begins with its
 * hours, so cutting at the first day gave "za op zo" — which states the
 * opposite of the real rule, *not* Saturday to Sunday. Caught on screen in
 * Noord, not by a test.
 */
private val SCHEDULE = Regex("""\b(ma|di|wo|do|vr|za|zo)[- ]|\b\d{1,2}-\d{2}\b""")

/**
 * Just the days and hours: "ma-zo 00-24". Falls back to the whole name when no
 * schedule can be found, since a few areas carry none at all (`T14_UA01` is
 * "Tarief 4 start tarief 7") and showing something beats showing nothing.
 */
fun tariffHours(area: TariffArea): String =
    SCHEDULE.find(area.name)?.let { area.name.substring(it.range.first) } ?: area.name

/** Rate and schedule only — the compact form used in the map header. */
fun tariffShort(area: TariffArea): String =
    listOf(area.tariffText, tariffHours(area)).filter { it.isNotBlank() }.joinToString(" · ")

/** Clock time [minutes] from midnight, as "19:00". */
internal fun clock(minutes: Int): String {
    val wrapped = ((minutes % 1440) + 1440) % 1440
    return "%02d:%02d".format(wrapped / 60, wrapped % 60)
}

private val DAY_SHORT = listOf("ma", "di", "wo", "do", "vr", "za", "zo")

/**
 * A moment [offsetMin] ahead of now, named so it cannot be read as today.
 *
 * "19:00" when it is later today, "zo 19:00" when it is not. The day is the
 * v0.7.0 fix and it is worth two characters: [clock] alone does `% 1440`, while
 * the offset handed to it comes from [tariffNow], which deliberately scans a
 * whole week. On T17N at Saturday 14:00 the next charge is **Sunday 19:00** —
 * twenty-nine hours out — and the app said "Free · from 19:00", which standing
 * at the car reads as tonight at seven. You move it at 18:45 for nothing.
 *
 * Only ever grows the string when the day differs, so the common case — a
 * boundary later today — is unchanged.
 */
internal fun clockAhead(dayOfWeek: Int, minuteOfDay: Int, offsetMin: Int): String {
    val absolute = dayOfWeek * 1440 + minuteOfDay + offsetMin
    // Midnight belongs to the day that is *ending*, written 24:00 — the
    // convention Amsterdam's own data uses ("900-2400") and the one
    // [weekSchedule] already prints. Six areas charge until midnight and stop,
    // and "until ma 00:00" for tonight is a puzzle where "until 24:00" is not.
    val atMidnight = absolute % 1440 == 0
    val owningDay = if (atMidnight) absolute / 1440 - 1 else absolute / 1440
    val day = ((owningDay % 7) + 7) % 7
    val time = if (atMidnight) "24:00" else clock(absolute)
    return if (day == dayOfWeek) time else "${DAY_SHORT[day]} $time"
}

/**
 * What this spot costs *right now*, in one line: "€3,01/h · until 19:00", or
 * "Free · from zo 19:00".
 *
 * This is the whole point of the schedule engine. Standing in the street, the
 * question is never "what is this area's timetable" — it is "am I paying, and
 * for how long". The timetable made you decode "ma-wo,vrij,za 09-19 · do 09-21"
 * yourself, in the rain.
 *
 * **"all day" now means what it says.** It used to appear whenever the active
 * window ended at midnight, which is seven of the twenty-nine areas; it now
 * appears only for the three that charge every minute of the week.
 */
fun tariffNowText(now: TariffNow, dayOfWeek: Int, minuteOfDay: Int): String = when (now) {
    is TariffNow.Charging -> when (val ends = now.endsInMin) {
        null -> "${now.rateText} · all day"
        else -> "${now.rateText} · until ${clockAhead(dayOfWeek, minuteOfDay, ends)}"
    }
    is TariffNow.Free -> when (val starts = now.startsInMin) {
        // Unreachable in practice — all 29 bundled areas parse at least one
        // window — and kept because an area with no hours must never render as
        // a free street on the strength of a parse failure.
        null -> "Free · no paid hours"
        else -> "Free · from ${clockAhead(dayOfWeek, minuteOfDay, starts)}"
    }
}
