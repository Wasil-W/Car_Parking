package dev.wasil.permit.ui

import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.TariffWindow

/**
 * The whole week for one tariff area, for the panel that opens when you tap it.
 *
 * v0.6.0 replaced the raw timetable with a single live line, on the grounds
 * that `ma-wo,vrij,za 09-19 · do 09-21` is a puzzle rather than an answer. That
 * was right for the header and wrong as a deletion: the full view was removed
 * instead of moved, so *"what does this cost tomorrow"* stopped having an
 * answer anywhere. Wasil, 2026-08-06: *"maybe i am curious about tommorows rate
 * then i dont see that."*
 *
 * So: the header keeps saying one true thing about now, and this says
 * everything — but only when asked for. *"I dont want to always see it, just
 * when i press it."*
 *
 * No new data. Every band is already parsed into [TariffWindow] at app start.
 */
data class ScheduleRow(
    /** "ma–za", "do", "zo" — the days this line covers. */
    val days: String,
    /** "09:00–19:00", or null when nothing is charged on those days. */
    val hours: String?,
    /** "€5,37/h", or null on a free line. */
    val rate: String?,
    /** The days behind [days], kept so "is this the line that applies now" is answerable. */
    val dayIndices: Set<Int> = emptySet(),
    /** Minutes from midnight behind [hours]; both zero on a free line. */
    val startMin: Int = 0,
    val endMin: Int = 0,
) {
    val free: Boolean get() = hours == null

    /**
     * Whether this is the line in force at this moment.
     *
     * Wasil, looking at an area with three lines: *"i see 3 prices i dont know
     * which one is the correct one."* A timetable that does not say which row
     * is now makes the reader do the lookup the app already did for the header.
     */
    fun appliesAt(dayOfWeek: Int, minuteOfDay: Int): Boolean =
        dayOfWeek in dayIndices && !free && minuteOfDay >= startMin && minuteOfDay < endMin
}

/**
 * The row in force now, or the free row when nothing is charging today.
 *
 * Returns null only when the day is not mentioned at all, which cannot happen
 * for a real area — [weekSchedule] always emits a free line covering the days
 * no band does.
 */
fun activeRow(rows: List<ScheduleRow>, dayOfWeek: Int, minuteOfDay: Int): ScheduleRow? =
    rows.firstOrNull { it.appliesAt(dayOfWeek, minuteOfDay) }
        ?: rows.firstOrNull { it.free && dayOfWeek in it.dayIndices }

private val DAY_NAMES = listOf("ma", "di", "wo", "do", "vr", "za", "zo")

/** `540` becomes `"09:00"`; `1440` is the end of the day, not the start of the next. */
private fun hhmm(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

/**
 * `{0,1,2,3,4}` becomes `"ma–vr"`; `{0,2,4}` becomes `"ma, wo, vr"`.
 *
 * Runs of three or more collapse to a range because that is how the source
 * writes them and how a person reads them; two adjacent days stay listed, since
 * "ma, di" is no longer than "ma–di" and reads more plainly.
 */
internal fun dayLabel(days: Set<Int>): String {
    val sorted = days.filter { it in 0..6 }.sorted()
    if (sorted.isEmpty()) return ""
    val parts = mutableListOf<String>()
    var runStart = sorted.first()
    var previous = runStart
    fun close() {
        val length = previous - runStart + 1
        parts += when {
            length >= 3 -> "${DAY_NAMES[runStart]}–${DAY_NAMES[previous]}"
            length == 2 -> "${DAY_NAMES[runStart]}, ${DAY_NAMES[previous]}"
            else -> DAY_NAMES[runStart]
        }
    }
    sorted.drop(1).forEach { day ->
        if (day == previous + 1) previous = day else { close(); runStart = day; previous = day }
    }
    close()
    return parts.joinToString(", ")
}

/**
 * Every charging band, plus one line for the days nothing is charged at all.
 *
 * The free line is the point of the panel. A timetable that lists only the
 * bands makes you work out the gaps yourself, and the gaps are what you were
 * curious about — Sunday, and after seven in the evening.
 *
 * Bands that share a rate and a span are merged, so an area written as five
 * separate weekday entries reads as one line rather than five.
 */
fun weekSchedule(area: TariffArea): List<ScheduleRow> {
    if (area.windows.isEmpty()) return emptyList()

    val merged = area.windows
        .groupBy { Triple(it.rateText, it.startMin, it.endMin) }
        .map { (key, group) ->
            val (rate, start, end) = key
            val days = group.flatMap { it.days }.toSet()
            ScheduleRow(
                days = dayLabel(days),
                hours = "${hhmm(start)}–${if (end >= 1440) "24:00" else hhmm(end)}",
                rate = rate,
                dayIndices = days,
                startMin = start,
                endMin = end,
                // Sorted below by the earliest day, then the earliest hour, so
                // the week reads Monday-first rather than in JSON order.
            ) to Pair(days.minOrNull() ?: 7, start)
        }
        .sortedWith(compareBy({ it.second.first }, { it.second.second }))
        .map { it.first }

    val charged = area.windows.flatMap { it.days }.toSet()
    val freeDays = (0..6).toSet() - charged
    if (freeDays.isEmpty()) return merged
    return merged + ScheduleRow(
        days = dayLabel(freeDays),
        hours = null,
        rate = null,
        dayIndices = freeDays,
    )
}
