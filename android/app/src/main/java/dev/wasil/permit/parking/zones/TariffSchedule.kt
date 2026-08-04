package dev.wasil.permit.parking.zones

/**
 * One charging window: a rate, a span of the day, and the days it applies to.
 *
 * Amsterdam writes these as `{"900-1900": "ma-wo,vrij,za"}` — times as HHMM
 * integers, days as a comma-separated mix of single tokens and ranges. Note
 * the day names here are **not** the ones in an area's description: the
 * schedule field spells Friday `vrij` where the description says `vr`, and
 * never writes `di` alone — Tuesday only ever appears inside a range like
 * `ma-wo`. Two vocabularies in one file, so they get two parsers.
 *
 * [rateCents] is the same price as [rateText], as a whole number of cents per
 * hour. It is carried from the parse rather than re-derived from the text
 * later, because [rateText] is a display string — it has a currency symbol, a
 * Dutch comma decimal and a `/h` suffix, and re-parsing a string we formatted
 * ourselves is a round trip that only ever loses. Null when the source rate
 * could not be read as a number, which is *not* the same as free: see
 * [dev.wasil.permit.parking.shared.preferredPermitHolder], where an unreadable
 * rate is treated as possibly-expensive rather than as zero.
 */
data class TariffWindow(
    val rateText: String,
    val startMin: Int,
    val endMin: Int,
    val days: Set<Int>,
    val rateCents: Int? = null,
)

/** Monday is 0, Sunday is 6 — the order Amsterdam's own day ranges assume. */
private val DAY_INDEX = mapOf(
    "ma" to 0, "di" to 1, "wo" to 2, "do" to 3, "vrij" to 4, "za" to 5, "zo" to 6,
)

/** `"900"` is 09:00, `"2400"` is midnight at the end of the day. */
internal fun parseHhmm(raw: String): Int? {
    val value = raw.trim().toIntOrNull() ?: return null
    val minutes = (value / 100) * 60 + (value % 100)
    return minutes.takeIf { it in 0..1440 }
}

/**
 * `"8,05"` becomes 805 cents. Dutch decimals use a comma, so a naive
 * `toDouble()` on this data silently returns null and every paid street reads
 * as free — the exact failure that costs a fine. A dot is accepted too, purely
 * so a hand-edited asset does not have to know which convention was used;
 * neither separator is ambiguous here because an hourly parking rate never
 * carries a thousands separator.
 *
 * Stepped rates arrive as `"1,72[0-180];4,19[180-999999]"` — a first-three-hours
 * price and an after-that price. Only the first band is taken, matching the
 * label [TariffAreas] already shows, because the comparison this feeds asks
 * "what is this spot costing me now", and now is always inside the first band
 * at the moment of parking.
 *
 * Null rather than zero when nothing parses: a rate we cannot read is not a
 * rate of nothing.
 */
internal fun parseRateCents(raw: String): Int? {
    val head = raw.substringBefore('[').trim().replace(',', '.')
    val euros = head.toDoubleOrNull() ?: return null
    if (euros < 0) return null
    return Math.round(euros * 100).toInt()
}

/** `"ma-wo,vrij,za"` becomes {0,1,2,4,5}. Unknown tokens are skipped, not guessed. */
internal fun parseDays(spec: String): Set<Int> = buildSet {
    spec.split(',').forEach { part ->
        val piece = part.trim()
        if (piece.isEmpty()) return@forEach
        val range = piece.split('-')
        if (range.size == 2) {
            val from = DAY_INDEX[range[0].trim()]
            val to = DAY_INDEX[range[1].trim()]
            if (from != null && to != null && from <= to) addAll(from..to)
        } else {
            DAY_INDEX[piece]?.let { add(it) }
        }
    }
}

/** What an area costs at one moment, rather than what its whole timetable says. */
sealed interface TariffNow {
    /**
     * Charging now. [endsInMin] is null when the window runs to midnight.
     * [rateCents] is [rateText] as a number, for comparing two spots without
     * either phone learning where the other is — null when the source rate was
     * unreadable, which must not be read as free.
     */
    data class Charging(
        val rateText: String,
        val endsInMin: Int?,
        val rateCents: Int? = null,
    ) : TariffNow

    /** Not charging now. [startsInMin] is null when nothing is scheduled at all. */
    data class Free(val startsInMin: Int?) : TariffNow
}

private const val MINUTES_PER_DAY = 1440
private const val WEEK_MINUTES = 7 * MINUTES_PER_DAY

/**
 * Whether [windows] charge at [dayOfWeek] (Monday 0) and [minuteOfDay], and how
 * long that state lasts.
 *
 * The point of this over showing the raw timetable: standing in the street you
 * want "€3,01/h until 19:00", not "ma-wo,vrij,za 09-19 · do 09-21" to decode
 * yourself.
 */
fun tariffNow(windows: List<TariffWindow>, dayOfWeek: Int, minuteOfDay: Int): TariffNow {
    if (windows.isEmpty()) return TariffNow.Free(null)

    windows.firstOrNull { dayOfWeek in it.days && minuteOfDay >= it.startMin && minuteOfDay < it.endMin }
        ?.let { active ->
            val endsIn = (active.endMin - minuteOfDay).takeIf { active.endMin < MINUTES_PER_DAY }
            return TariffNow.Charging(active.rateText, endsIn, active.rateCents)
        }

    // Nothing now: find the soonest start within a week. A week is enough —
    // any window recurs weekly by construction, so failing to find one here
    // means there genuinely is none.
    val nowAbsolute = dayOfWeek * MINUTES_PER_DAY + minuteOfDay
    val soonest = windows
        .flatMap { window -> window.days.map { day -> day * MINUTES_PER_DAY + window.startMin } }
        .minOfOrNull { start ->
            val delta = start - nowAbsolute
            if (delta >= 0) delta else delta + WEEK_MINUTES
        }
    return TariffNow.Free(soonest)
}
