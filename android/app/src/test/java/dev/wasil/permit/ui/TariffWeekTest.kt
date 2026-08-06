package dev.wasil.permit.ui

import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.TariffAreas
import dev.wasil.permit.parking.zones.TariffWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TariffWeekTest {

    private fun area(vararg windows: TariffWindow) =
        TariffArea("T00", "test", "€1,00/h", emptyList(), windows.toList())

    @Test
    fun `a run of three or more days collapses to a range`() {
        assertEquals("ma–vr", dayLabel(setOf(0, 1, 2, 3, 4)))
        assertEquals("ma–zo", dayLabel(setOf(0, 1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun `two adjacent days stay listed, because a range of two saves nothing`() {
        assertEquals("za, zo", dayLabel(setOf(5, 6)))
    }

    @Test
    fun `gaps break the range rather than being papered over`() {
        assertEquals("ma–wo, vr", dayLabel(setOf(0, 1, 2, 4)))
        assertEquals("ma, wo, vr", dayLabel(setOf(0, 2, 4)))
    }

    @Test
    fun `a weekday band reads as one line with its hours and rate`() {
        val rows = weekSchedule(area(TariffWindow("€5,37/h", 9 * 60, 19 * 60, setOf(0, 1, 2, 3, 4))))
        assertEquals("ma–vr", rows[0].days)
        assertEquals("09:00–19:00", rows[0].hours)
        assertEquals("€5,37/h", rows[0].rate)
    }

    @Test
    fun `the days nothing is charged get their own line`() {
        // The point of the panel. A timetable listing only the bands makes you
        // work out the gaps, and the gaps are what the question was about.
        val rows = weekSchedule(area(TariffWindow("€5,37/h", 9 * 60, 19 * 60, setOf(0, 1, 2, 3, 4))))
        val free = rows.last()
        assertTrue(free.free)
        assertEquals("za, zo", free.days)
        assertEquals(null, free.rate)
    }

    @Test
    fun `an area charging every day has no free line`() {
        val rows = weekSchedule(area(TariffWindow("€8,05/h", 0, 1440, (0..6).toSet())))
        assertEquals(1, rows.size)
        assertEquals("00:00–24:00", rows[0].hours)
    }

    @Test
    fun `bands sharing a rate and a span are merged into one line`() {
        // Amsterdam writes some areas as several entries that say the same
        // thing; five identical weekday lines would be five lines of noise.
        val rows = weekSchedule(
            area(
                TariffWindow("€3,01/h", 9 * 60, 19 * 60, setOf(0, 1)),
                TariffWindow("€3,01/h", 9 * 60, 19 * 60, setOf(2, 3, 4)),
            ),
        )
        assertEquals(2, rows.size) // the merged band, then the free weekend
        assertEquals("ma–vr", rows[0].days)
    }

    @Test
    fun `a day with its own later closing keeps its own line`() {
        val rows = weekSchedule(
            area(
                TariffWindow("€3,01/h", 9 * 60, 19 * 60, setOf(0, 1, 2, 4, 5)),
                TariffWindow("€3,01/h", 9 * 60, 21 * 60, setOf(3)),
            ),
        )
        assertTrue(rows.any { it.days == "do" && it.hours == "09:00–21:00" })
    }

    @Test
    fun `the week reads Monday first, whatever order the source used`() {
        val rows = weekSchedule(
            area(
                TariffWindow("€1,00/h", 12 * 60, 17 * 60, setOf(5)),
                TariffWindow("€1,00/h", 9 * 60, 19 * 60, setOf(0)),
            ),
        )
        assertEquals("ma", rows[0].days)
        assertEquals("za", rows[1].days)
    }

    @Test
    fun `an area with no parsed windows has nothing to show`() {
        assertEquals(emptyList<ScheduleRow>(), weekSchedule(area()))
    }

    @Test
    fun `every real bundled area produces a readable week`() {
        val areas = TariffAreas.parse(
            java.io.File("src/main/assets/amsterdam_tarieven.json").readText(),
        )
        areas.filter { it.windows.isNotEmpty() }.forEach { a ->
            val rows = weekSchedule(a)
            assertTrue("no rows for ${a.code}", rows.isNotEmpty())
            rows.forEach {
                assertTrue("empty day label in ${a.code}", it.days.isNotBlank())
                // A charging line must carry both, and a free line neither.
                assertEquals("${a.code}: hours/rate disagree", it.hours == null, it.rate == null)
            }
        }
    }
}
