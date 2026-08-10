package dev.wasil.permit.parking.zones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TariffScheduleTest {

    private val weekdays9to19 = TariffWindow("€3,01/h", 9 * 60, 19 * 60, setOf(0, 1, 2, 3, 4))
    private val thursdayLate = TariffWindow("€3,01/h", 9 * 60, 21 * 60, setOf(3))

    private fun at(day: Int, hour: Int, minute: Int = 0) = day to (hour * 60 + minute)

    @Test
    fun `HHMM becomes minutes, and midnight at the end of the day is 1440`() {
        assertEquals(540, parseHhmm("900"))
        assertEquals(0, parseHhmm("0"))
        assertEquals(1440, parseHhmm("2400"))
        assertEquals(120, parseHhmm("200"))
    }

    @Test
    fun `day ranges and lists expand, and Tuesday only ever arrives inside a range`() {
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6), parseDays("ma-zo"))
        assertEquals(setOf(0, 1, 2, 4, 5), parseDays("ma-wo,vrij,za"))
        assertEquals(setOf(3), parseDays("do"))
        // Friday is spelled "vrij" in this field, never "vr".
        assertEquals(setOf(0, 1, 2, 3, 4), parseDays("ma-vrij"))
    }

    @Test
    fun `an unknown day token is skipped rather than guessed at`() {
        assertEquals(setOf(5), parseDays("zaterdagavond,za"))
    }

    @Test
    fun `inside a window it charges and says how long is left`() {
        val (day, minute) = at(1, 10, 30) // Tuesday 10:30
        val now = tariffNow(listOf(weekdays9to19), day, minute) as TariffNow.Charging
        assertEquals("€3,01/h", now.rateText)
        assertEquals(8 * 60 + 30, now.endsInMin) // until 19:00
    }

    @Test
    fun `before a window it is free and says when charging starts`() {
        val (day, minute) = at(1, 7, 0) // Tuesday 07:00
        val now = tariffNow(listOf(weekdays9to19), day, minute) as TariffNow.Free
        assertEquals(2 * 60, now.startsInMin)
    }

    @Test
    fun `after the last window it waits for tomorrow, not for today`() {
        val (day, minute) = at(1, 20, 0) // Tuesday 20:00, next is Wednesday 09:00
        val now = tariffNow(listOf(weekdays9to19), day, minute) as TariffNow.Free
        assertEquals(13 * 60, now.startsInMin)
    }

    @Test
    fun `on a day with no window at all it waits until the next working day`() {
        val (day, minute) = at(6, 12, 0) // Sunday noon, next is Monday 09:00
        val now = tariffNow(listOf(weekdays9to19), day, minute) as TariffNow.Free
        assertEquals(21 * 60, now.startsInMin)
    }

    @Test
    fun `a day with its own later window uses that window, not the general one`() {
        val (day, minute) = at(3, 20, 0) // Thursday 20:00
        val now = tariffNow(listOf(thursdayLate), day, minute) as TariffNow.Charging
        assertEquals(60, now.endsInMin)
    }

    @Test
    fun `charging every minute of the week has no end to report`() {
        val allDay = TariffWindow("€8,05/h", 0, 1440, setOf(0, 1, 2, 3, 4, 5, 6))
        val now = tariffNow(listOf(allDay), 2, 13 * 60) as TariffNow.Charging
        assertEquals(null, now.endsInMin)
    }

    // ── The overnight areas ────────────────────────────────────────────────
    //
    // T17N as the city writes it: charging 00:00–06:00 Monday to Saturday, and
    // again 19:00–24:00 on Sunday and Monday to Friday. Two windows, two
    // different day-sets, and the join happens across midnight — which is the
    // shape that produced both of the defects below.

    private val overnightEarly = TariffWindow("€1,72/h", 0, 6 * 60, setOf(0, 1, 2, 3, 4, 5))
    private val overnightLate = TariffWindow("€1,72/h", 19 * 60, 1440, setOf(6, 0, 1, 2, 3, 4))
    private val overnight = listOf(overnightEarly, overnightLate)

    @Test
    fun `a window ending at midnight is not all day, and its run crosses into tomorrow`() {
        // Monday 20:00. The old engine returned null here — "no end to report" —
        // which rendered as "· all day" for a street that had been free since
        // six that morning. The run really ends at 06:00 on Tuesday.
        val now = tariffNow(overnight, 0, 20 * 60) as TariffNow.Charging
        assertEquals(10 * 60, now.endsInMin)
    }

    @Test
    fun `the small hours belong to the run that started last night`() {
        // Tuesday 02:00 — still inside the run that began Monday 19:00.
        val now = tariffNow(overnight, 1, 2 * 60) as TariffNow.Charging
        assertEquals(4 * 60, now.endsInMin)
    }

    @Test
    fun `the gap Saturday leaves is twenty-nine hours, not five`() {
        // Saturday 14:00. Saturday is excluded from the evening window and
        // Sunday from the morning one — "19-06, niet za op zo" — so the next
        // charge is Sunday 19:00. The old engine found the same 1740 minutes;
        // it was the *rendering* that dropped the day. Pinned here so the
        // engine half cannot regress underneath the formatter.
        val now = tariffNow(overnight, 5, 14 * 60) as TariffNow.Free
        assertEquals(29 * 60, now.startsInMin)
    }

    @Test
    fun `a run wrapping from Sunday night into Monday morning is one run`() {
        // Sunday 23:00: charging began at 19:00 Sunday and continues to 06:00
        // Monday, across the end of the week as well as the end of the day.
        val now = tariffNow(overnight, 6, 23 * 60) as TariffNow.Charging
        assertEquals(7 * 60, now.endsInMin)
    }

    // ── The span after this one (v0.7.0) ──────────────────────────────────
    //
    // The panel's one new line. Every case below crosses a midnight, because
    // the row layout this replaces could not express that — it had one day
    // column and two bare clock times, so T17N on a Friday evening rendered
    // "za Free 06:00 → 19:00" for a gap that really runs to Sunday.

    @Test
    fun `while charging, the next span is the free gap and it ends at the next charge`() {
        // T17N, Friday 20:00. Charging until Saturday 06:00; then free until
        // Sunday 19:00, because Saturday evening is in neither day-set.
        val next = tariffNext(overnight, 4, 20 * 60)!!
        assertEquals(false, next.charging)
        assertNull(next.rateText)
        // Fri 20:00 -> Sun 19:00 is 47 hours.
        assertEquals(47 * 60, next.endsInMin)
    }

    @Test
    fun `while free, the next span is the charge and it ends where the charge ends`() {
        // T17N, Saturday 14:00. Next charge Sunday 19:00, running to Monday
        // 06:00 — so the span ends 40 hours out, not 29.
        val next = tariffNext(overnight, 5, 14 * 60)!!
        assertEquals(true, next.charging)
        assertEquals("€1,72/h", next.rateText)
        assertEquals(40 * 60, next.endsInMin)
    }

    @Test
    fun `a simple weekday area hands back tomorrow morning, not tonight`() {
        // ma-vr 09:00-19:00, Tuesday 15:00: free from 19:00 until Wednesday
        // 09:00. The gap ends 18 hours out.
        val next = tariffNext(listOf(weekdays9to19), 1, 15 * 60)!!
        assertEquals(false, next.charging)
        assertEquals(18 * 60, next.endsInMin)
    }

    @Test
    fun `a one-day-a-week area still has a next span at the minute it opens`() {
        // No bundled area looks like this — all 29 charge on five days or more
        // — but the data is the city's and gets refreshed. Read at exactly
        // 09:00 the only run start is this one, at delta zero, and the guard
        // that skips it used to leave nothing, which every caller reads as
        // "never changes".
        val marketDay = listOf(TariffWindow("€3,01/h", 9 * 60, 19 * 60, setOf(0)))
        val atOpen = tariffNext(marketDay, 0, 9 * 60)!!
        assertEquals(false, atOpen.charging)
        assertEquals(WEEK_MINUTES, atOpen.endsInMin)

        // A minute either side was already right; pinned so the fix cannot
        // regress into a special case that only handles the boundary.
        assertEquals(10 * 60 + 1, tariffNext(marketDay, 0, 8 * 60 + 59)!!.endsInMin)
        assertEquals(false, tariffNext(marketDay, 0, 9 * 60 + 1)!!.charging)
    }

    @Test
    fun `an area that never stops charging has no next span`() {
        val allDay = TariffWindow("€8,05/h", 0, 1440, setOf(0, 1, 2, 3, 4, 5, 6))
        assertNull(tariffNext(listOf(allDay), 2, 13 * 60))
        assertNull(tariffNext(emptyList(), 0, 600))
    }

    @Test
    fun `every bundled area either never changes or names a real next span`() {
        val areas = TariffAreas.parse(
            java.io.File("src/main/assets/amsterdam_tarieven.json").readText(),
        )
        val neverChanges = mutableSetOf<String>()
        areas.forEach { area ->
            (0..6).forEach { day ->
                (0 until 1440 step 15).forEach { minute ->
                    val next = tariffNext(area.windows, day, minute)
                    if (next == null) {
                        neverChanges += area.code
                    } else {
                        // A span that ends now, or more than a week out, is a
                        // wrap bug rather than a schedule.
                        assertTrue(
                            "${area.code} day $day min $minute -> ${next.endsInMin}",
                            next.endsInMin in 1..WEEK_MINUTES,
                        )
                        assertEquals(next.charging, next.rateText != null)
                    }
                }
            }
        }
        assertEquals(setOf("T11V", "T12V", "T13V"), neverChanges)
    }

    @Test
    fun `the run merge does not swallow the week when charging never stops`() {
        val allDay = TariffWindow("€8,05/h", 0, 1440, setOf(0, 1, 2, 3, 4, 5, 6))
        assertEquals(1, chargeRuns(listOf(allDay)).size)
        assertEquals(0, chargeRuns(listOf(allDay)).first().start)
        assertEquals(WEEK_MINUTES, chargeRuns(listOf(allDay)).first().end)
    }

    @Test
    fun `every bundled area that ends a window at midnight reports a real end`() {
        val areas = TariffAreas.parse(
            java.io.File("src/main/assets/amsterdam_tarieven.json").readText(),
        )
        // The seven areas whose windows start after midnight and end at 24:00.
        // None of them may say "all day" — only the round-the-clock three may,
        // and those are the ones covering the whole week.
        val roundTheClock = areas.filter { area ->
            chargeRuns(area.windows).sumOf { it.end - it.start } >= WEEK_MINUTES
        }.map { it.code }.toSet()
        assertEquals(setOf("T11V", "T12V", "T13V"), roundTheClock)

        areas.filter { it.code !in roundTheClock && it.windows.isNotEmpty() }.forEach { area ->
            (0..6).forEach { day ->
                (0 until 1440 step 30).forEach { minute ->
                    val now = tariffNow(area.windows, day, minute)
                    if (now is TariffNow.Charging) {
                        assertTrue(
                            "${area.code} reported no end at day $day minute $minute",
                            now.endsInMin != null,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `no windows at all reads as free with nothing scheduled`() {
        val now = tariffNow(emptyList(), 0, 600) as TariffNow.Free
        assertEquals(null, now.startsInMin)
    }

    @Test
    fun `the real bundled data produces windows for every priced area`() {
        val areas = TariffAreas.parse(
            java.io.File("src/main/assets/amsterdam_tarieven.json").readText(),
        )
        val withWindows = areas.count { it.windows.isNotEmpty() }
        assertTrue("only $withWindows of ${areas.size} areas parsed windows", withWindows >= 27)
        // Every window must be a real span on real days, or the engine silently
        // reports "free" for a paid street.
        areas.flatMap { it.windows }.forEach {
            assertTrue(it.startMin < it.endMin)
            assertTrue(it.days.isNotEmpty())
            assertTrue(it.days.all { d -> d in 0..6 })
            // "€3,01/h", or "from €1,72/h" on the two stepped areas. The point
            // of the check is that a price is named at all — a window whose
            // label lost its number reads as a free street.
            assertTrue("no price in '${it.rateText}'", it.rateText.contains("€"))
        }
    }
}
