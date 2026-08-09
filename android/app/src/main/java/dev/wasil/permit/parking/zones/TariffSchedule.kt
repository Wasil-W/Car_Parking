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
 */
data class TariffWindow(
    val rateText: String,
    val startMin: Int,
    val endMin: Int,
    val days: Set<Int>,
    /**
     * How the rate changes with how long you stay, when it does.
     *
     * Null for the 26 areas that charge one rate. The other three write their
     * key as `1,72[0-180];4,19[180-999999]` — €1,72/h for the first three hours,
     * then €4,19/h — and until v0.7.0 everything after the bracket was thrown
     * away, so the app showed the opening rate as though it were the only one.
     * See [dev.wasil.permit.parking.zones.TariffAreas] for the parse and for
     * what that cost.
     */
    val stepNote: String? = null,
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
     * Charging now. [endsInMin] counts to the end of the whole charging **run**,
     * not to the end of the calendar day, and is null only when charging never
     * stops at all.
     *
     * That distinction is the v0.7.0 fix. It used to be null whenever the active
     * window ended at midnight, which [tariffNowText] rendered as "· all day" —
     * true for the three areas that really do charge round the clock, and false
     * for the seven that merely charge *until* midnight. T17N charges
     * 19:00–24:00 and again 00:00–06:00, so at Monday 20:00 the app said "all
     * day" about a street that had been free since six in the morning, and did
     * not mention that the meter runs until Tuesday breakfast.
     */
    data class Charging(val rateText: String, val endsInMin: Int?) : TariffNow

    /** Not charging now. [startsInMin] is null when nothing is scheduled at all. */
    data class Free(val startsInMin: Int?) : TariffNow
}

private const val MINUTES_PER_DAY = 1440
internal const val WEEK_MINUTES = 7 * MINUTES_PER_DAY

/**
 * One unbroken stretch of charging, in minutes from Monday 00:00.
 *
 * [end] may exceed [WEEK_MINUTES]: the week is a circle, so a run that starts
 * Sunday evening and finishes Monday morning is one run, not two.
 */
internal data class ChargeRun(val start: Int, val end: Int)

/**
 * Every window flattened onto the week and merged into runs.
 *
 * The merge is the whole point. Amsterdam writes the overnight areas as two
 * windows with two different day-sets — T17N is `00:00–06:00 ma-za` plus
 * `19:00–24:00 zo,ma-vrij` — and a reader is expected to join them across
 * midnight themselves, noticing on the way that Saturday night is excluded from
 * one set and Sunday morning from the other. Joining them here means the join
 * happens once, in code, instead of every time somebody stands next to a car.
 *
 * Intervals that merely *touch* are merged, not just overlapping ones: a window
 * ending at 1440 and one starting at 0 the next day are contiguous, and treating
 * them as separate is exactly the bug this replaces.
 */
internal fun chargeRuns(windows: List<TariffWindow>): List<ChargeRun> {
    if (windows.isEmpty()) return emptyList()
    val flattened = windows
        .flatMap { window ->
            window.days.map { day ->
                ChargeRun(day * MINUTES_PER_DAY + window.startMin, day * MINUTES_PER_DAY + window.endMin)
            }
        }
        .sortedBy { it.start }

    val merged = mutableListOf<ChargeRun>()
    flattened.forEach { run ->
        val last = merged.lastOrNull()
        if (last != null && run.start <= last.end) {
            if (run.end > last.end) merged[merged.lastIndex] = last.copy(end = run.end)
        } else {
            merged += run
        }
    }

    // The wrap. A run reaching Sunday midnight continues into one starting at
    // Monday 00:00, so the two are folded into a single run whose end runs past
    // the end of the week. Guarded on size, because an area charging every
    // minute of the week is already one run and must not eat itself.
    if (merged.size > 1 && merged.first().start == 0 && merged.last().end >= WEEK_MINUTES) {
        val head = merged.removeAt(0)
        val tail = merged.removeAt(merged.lastIndex)
        merged += ChargeRun(tail.start, WEEK_MINUTES + head.end)
        merged.sortBy { it.start }
    }
    return merged
}

/**
 * Whether [windows] charge at [dayOfWeek] (Monday 0) and [minuteOfDay], and how
 * long that state lasts.
 *
 * The point of this over showing the raw timetable: standing in the street you
 * want "€3,01/h · until di 06:00", not "ma-wo,vrij,za 09-19 · do 09-21" to
 * decode yourself.
 */
fun tariffNow(windows: List<TariffWindow>, dayOfWeek: Int, minuteOfDay: Int): TariffNow {
    if (windows.isEmpty()) return TariffNow.Free(null)
    val runs = chargeRuns(windows)
    if (runs.isEmpty()) return TariffNow.Free(null)

    val now = dayOfWeek * MINUTES_PER_DAY + minuteOfDay
    // A wrapped run is stored past the end of the week, so a Monday-morning
    // moment has to be offered to it in the same terms.
    val active = runs.firstOrNull { now >= it.start && now < it.end }
        ?: runs.firstOrNull { now + WEEK_MINUTES >= it.start && now + WEEK_MINUTES < it.end }

    if (active != null) {
        // The rate is looked up per window rather than per run: a run can be
        // built from more than one window, and it is the one covering this
        // minute that is being charged.
        val rate = windows
            .firstOrNull { dayOfWeek in it.days && minuteOfDay >= it.startMin && minuteOfDay < it.endMin }
            ?.rateText
            ?: windows.first().rateText
        val covered = runs.sumOf { it.end - it.start }
        val endsIn = if (covered >= WEEK_MINUTES) {
            null // Genuinely never stops — the round-the-clock areas.
        } else {
            val reference = if (now >= active.start && now < active.end) now else now + WEEK_MINUTES
            active.end - reference
        }
        return TariffNow.Charging(rate, endsIn)
    }

    // Nothing now: the soonest run start within a week. A week is enough — any
    // window recurs weekly by construction, so failing to find one here means
    // there genuinely is none.
    val soonest = runs.minOf { run ->
        val delta = (run.start % WEEK_MINUTES) - now
        if (delta >= 0) delta else delta + WEEK_MINUTES
    }
    return TariffNow.Free(soonest)
}
