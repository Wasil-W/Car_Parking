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
    fun `a window running to midnight has no end to report`() {
        val allDay = TariffWindow("€8,05/h", 0, 1440, setOf(0, 1, 2, 3, 4, 5, 6))
        val now = tariffNow(listOf(allDay), 2, 13 * 60) as TariffNow.Charging
        assertEquals(null, now.endsInMin)
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
            assertTrue(it.rateText.startsWith("€"))
        }
    }
}
