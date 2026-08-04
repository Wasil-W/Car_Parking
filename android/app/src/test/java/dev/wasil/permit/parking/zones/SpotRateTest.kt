package dev.wasil.permit.parking.zones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotRateTest {

    private fun area(vararg windows: TariffWindow) = TariffArea(
        code = "T11V",
        name = "Basistarief",
        tariffText = "€8,05/h",
        polygons = listOf(ZonePolygon(outer = emptyList(), holes = emptyList())),
        windows = windows.toList(),
    )

    private val weekdays9to19 =
        TariffWindow("€3,01/h", 9 * 60, 19 * 60, setOf(0, 1, 2, 3, 4), rateCents = 301)

    private val tuesdayNoon = 1 to 12 * 60
    private val tuesdayDawn = 1 to 6 * 60

    @Test
    fun `inside a charging window the spot costs that rate`() {
        val (day, minute) = tuesdayNoon
        assertEquals(301, spotRateCents(ZoneInfo.Paid(area(weekdays9to19)), day, minute))
    }

    @Test
    fun `a paid zone outside its hours costs nothing right now`() {
        val (day, minute) = tuesdayDawn
        assertEquals(0, spotRateCents(ZoneInfo.Paid(area(weekdays9to19)), day, minute))
    }

    @Test
    fun `an area with no windows at all costs nothing`() {
        val (day, minute) = tuesdayNoon
        assertEquals(0, spotRateCents(ZoneInfo.Paid(area()), day, minute))
    }

    /** Home, a hand-marked zone and open street all genuinely cost nothing. */
    @Test
    fun `the free zones cost nothing`() {
        val (day, minute) = tuesdayNoon
        assertEquals(0, spotRateCents(ZoneInfo.Home, day, minute))
        assertEquals(0, spotRateCents(ZoneInfo.ManualFree("Sarphatipark"), day, minute))
        assertEquals(0, spotRateCents(ZoneInfo.FreeStreet, day, minute))
    }

    /**
     * Missing tariff data reads as unknown, never as free — the same bias
     * ZoneResolver already applies when it returns Paid(null).
     */
    @Test
    fun `a paid zone with no tariff data has no price rather than a free one`() {
        val (day, minute) = tuesdayNoon
        assertNull(spotRateCents(ZoneInfo.Paid(null), day, minute))
    }

    /** A window whose rate never parsed must not report as free either. */
    @Test
    fun `a charging window with an unreadable rate has no price`() {
        val unreadable = TariffWindow("€?/h", 0, 1440, setOf(0, 1, 2, 3, 4, 5, 6), rateCents = null)
        val (day, minute) = tuesdayNoon
        assertNull(spotRateCents(ZoneInfo.Paid(area(unreadable)), day, minute))
    }

    /**
     * End to end on the real asset. Every one of the 29 areas must price to
     * *something* — a null here would mean a real Amsterdam street the
     * comparison cannot judge, which is the failure this whole path exists to
     * avoid. 27 of them charge at Wednesday noon; T17N and T18P are evening-
     * and-weekend areas and are correctly free at that moment.
     */
    @Test
    fun `every bundled amsterdam area prices to a number`() {
        val areas = TariffAreas.parse(
            java.io.File("src/main/assets/amsterdam_tarieven.json").readText(),
        )
        assertEquals(29, areas.size)
        val rates = areas.map { spotRateCents(ZoneInfo.Paid(it), 2, 12 * 60) }
        assertEquals("an area priced as unknown", 0, rates.count { it == null })
        assertEquals(27, rates.count { it != null && it > 0 })
    }

    /** The real rate card, as numbers: €0,10 through €8,05. */
    @Test
    fun `the bundled rates parse to the prices amsterdam publishes`() {
        val areas = TariffAreas.parse(
            java.io.File("src/main/assets/amsterdam_tarieven.json").readText(),
        )
        val cents = areas.flatMap { it.windows }.mapNotNull { it.rateCents }.toSortedSet()
        assertEquals(setOf(10, 129, 172, 301, 419, 537, 698, 805), cents.toSet())
    }
}
