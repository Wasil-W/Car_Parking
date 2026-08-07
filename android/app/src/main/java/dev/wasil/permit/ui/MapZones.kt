package dev.wasil.permit.ui

import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.distanceMeters
import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.TariffNow
import dev.wasil.permit.parking.zones.ZonePolygon
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
}

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
 * Which existing zone circle, if any, contains [point] — used to let a tap on
 * the map remove a zone instead of needing a separate list. When circles
 * overlap, the one whose centre is nearest to the tap wins, so a tap near the
 * shared edge of a big zone and a small zone inside it hits the small one.
 */
fun zoneHitAt(point: GeoPoint, home: FreeZone?, freeZones: List<FreeZone>): ZoneRef? {
    val hits = buildList {
        home?.let { zone -> if (withinZone(point, zone)) add(ZoneRef.Home to zone) }
        freeZones.forEachIndexed { index, zone ->
            if (withinZone(point, zone)) add(ZoneRef.Free(index) to zone)
        }
    }
    return hits.minByOrNull { (_, zone) -> distanceMeters(point, zone.centre()) }?.first
}

private fun withinZone(point: GeoPoint, zone: FreeZone): Boolean =
    distanceMeters(point, zone.centre()) <= zone.radiusM

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
): MapHit? {
    zoneHitAt(point, home, freeZones)?.let { return MapHit.Zone(it) }
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

/**
 * What this spot costs *right now*, in one line: "€3,01/h · until 19:00", or
 * "Free · from 09:00".
 *
 * This is the whole point of the schedule engine. Standing in the street, the
 * question is never "what is this area's timetable" — it is "am I paying, and
 * for how long". The timetable made you decode "ma-wo,vrij,za 09-19 · do 09-21"
 * yourself, in the rain.
 */
fun tariffNowText(now: TariffNow, minuteOfDay: Int): String = when (now) {
    is TariffNow.Charging -> when (val ends = now.endsInMin) {
        null -> "${now.rateText} · all day"
        else -> "${now.rateText} · until ${clock(minuteOfDay + ends)}"
    }
    is TariffNow.Free -> when (val starts = now.startsInMin) {
        null -> "Free · no paid hours"
        else -> "Free · from ${clock(minuteOfDay + starts)}"
    }
}
