package dev.wasil.permit.parking.zones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingReminderTest {

    private val freeSoonCharging = TariffNow.Free(startsInMin = 20)
    private val chargingSoonFree = TariffNow.Charging(rateText = "€5,37/h", endsInMin = 5)

    private fun reminder(
        parked: Boolean = true,
        inPaidArea: Boolean = true,
        permitSettles: Boolean = false,
        now: TariffNow = freeSoonCharging,
    ) = reminderFor(parked, inPaidArea, permitSettles, now)

    // ---- the four conditions, each proved to be load-bearing ---------------

    @Test
    fun `it warns before charging starts`() {
        val r = reminder()
        assertTrue(r is Reminder.StartsOwing)
        assertEquals(20, r!!.inMin)
    }

    @Test
    fun `nothing is owed about a car that is not parked`() {
        assertNull(reminder(parked = false))
    }

    @Test
    fun `a spot outside a paid area never owes anything`() {
        // Home, a marked free zone, a free street, and everywhere outside
        // Amsterdam's published data all land here.
        assertNull(reminder(inPaidArea = false))
    }

    /**
     * The condition easiest to get wrong, and the reason it matters is B6: the
     * app claims the permit on geometry alone, regardless of the hour. So a
     * permit holder parked overnight in a `09-19` zone already holds it and
     * owes nothing at 09:00 — reminding them would be noise about a problem the
     * app solved on arrival.
     */
    @Test
    fun `a permit that already settles this spot silences the reminder`() {
        assertNull(reminder(permitSettles = true))
        assertNull(reminder(permitSettles = true, now = chargingSoonFree))
    }

    // ---- the boundaries ----------------------------------------------------

    @Test
    fun `a boundary further out than the lead time is not yet worth saying`() {
        assertNull(reminder(now = TariffNow.Free(startsInMin = OWING_LEAD_MIN + 1)))
        assertTrue(reminder(now = TariffNow.Free(startsInMin = OWING_LEAD_MIN)) is Reminder.StartsOwing)
    }

    @Test
    fun `becoming free warns later than starting to owe, and deliberately`() {
        // Being told late that you owe costs a fine; being told late that it is
        // free costs a few minutes of meter. The leads are not the same number
        // by accident.
        assertTrue(FREE_LEAD_MIN < OWING_LEAD_MIN)
        assertNull(reminder(now = TariffNow.Charging("€5,37/h", endsInMin = FREE_LEAD_MIN + 1)))
        val r = reminder(now = chargingSoonFree)
        assertTrue(r is Reminder.BecomesFree)
        assertEquals(5, r!!.inMin)
    }

    @Test
    fun `an area with no schedule at all has no boundary to warn about`() {
        assertNull(reminder(now = TariffNow.Free(startsInMin = null)))
    }

    @Test
    fun `an area that charges every minute never becomes free, so says nothing`() {
        // T11V, T12V, T13V — endsInMin is null precisely because there is no end.
        assertNull(reminder(now = TariffNow.Charging("€8,05/h", endsInMin = null)))
    }

    @Test
    fun `a boundary happening right now still fires`() {
        // Zero is inside the window, not outside it: a reminder that arrives as
        // the meter starts is late but not useless, and dropping it would make
        // the most urgent case the one case that says nothing.
        assertTrue(reminder(now = TariffNow.Free(startsInMin = 0)) is Reminder.StartsOwing)
    }

    // ---- the rate, which may be absent ------------------------------------

    @Test
    fun `the rate comes from the next span and its absence never blocks the reminder`() {
        val withRate = reminderFor(
            parked = true, inPaidArea = true, permitSettles = false,
            now = freeSoonCharging,
            next = TariffNext(charging = true, rateText = "€5,37/h", endsInMin = 600),
        )
        assertEquals("€5,37/h", (withRate as Reminder.StartsOwing).rateText)

        // No next span known: still sent, just shorter. A reminder that says
        // *when* without *how much* is worth having; an invented figure is not.
        val withoutRate = reminderFor(
            parked = true, inPaidArea = true, permitSettles = false,
            now = freeSoonCharging, next = null,
        )
        assertTrue(withoutRate is Reminder.StartsOwing)
        assertEquals("", (withoutRate as Reminder.StartsOwing).rateText)
    }

    // ---- the chain of wake-ups ---------------------------------------------

    @Test
    fun `the wait is the lead time short of the boundary`() {
        // Free, charging in 400 minutes: wake 30 minutes before it, not at it.
        assertEquals(
            400 - OWING_LEAD_MIN,
            nextCheckInMin(TariffNow.Free(400), TariffNext(true, "€3,01/h", 1000)),
        )
        // Charging, stopping in 400: wake 10 minutes before.
        assertEquals(
            400 - FREE_LEAD_MIN,
            nextCheckInMin(TariffNow.Charging("€3,01/h", 400), TariffNext(false, null, 1000)),
        )
    }

    @Test
    fun `a boundary already inside its lead window is not booked again`() {
        // startsInMin 20 is inside the 30-minute window, so the caller has just
        // notified about it. Booking it again would wake the phone immediately
        // to repeat itself; the next span's end is the real next thing.
        assertEquals(
            900 - FREE_LEAD_MIN,
            nextCheckInMin(freeSoonCharging, TariffNext(true, "€3,01/h", 900)),
        )
    }

    @Test
    fun `an area that never changes gives nothing to wait for`() {
        // T11V, T12V, T13V: charging every minute of the week, so tariffNext is
        // null and endsInMin is null. A chain here would wake forever to
        // rediscover that nothing has happened.
        assertNull(nextCheckInMin(TariffNow.Charging("€8,05/h", null), null))
        assertNull(nextCheckInMin(TariffNow.Free(null), null))
    }

    /**
     * The property the whole chain exists for, checked against the city's own
     * data rather than against an example I chose.
     *
     * Park at Monday 00:00 in each of the 29 bundled areas, follow the chain of
     * wake-ups for a week, and require that **every** moment those areas start
     * charging was preceded by a reminder inside its lead window. A missed
     * transition here is a fine in real life, and an off-by-one in
     * [nextCheckInMin] would show up as exactly that.
     */
    @Test
    fun `a week-long park is reminded before every charging start, in every real area`() {
        val areas = TariffAreas.parse(
            java.io.File("src/main/assets/amsterdam_tarieven.json").readText(),
        )
        val roundTheClock = mutableSetOf<String>()

        areas.filter { it.windows.isNotEmpty() }.forEach { area ->
            val starts = chargeRuns(area.windows).map { it.start % WEEK_MINUTES }.toSet()
            val warned = mutableSetOf<Int>()
            var at = 0
            var steps = 0
            while (at < WEEK_MINUTES && steps++ < 200) {
                val day = (at / 1440) % 7
                val minute = at % 1440
                val now = tariffNow(area.windows, day, minute)
                val next = tariffNext(area.windows, day, minute)
                val reminder = reminderFor(
                    parked = true, inPaidArea = true, permitSettles = false, now = now,
                )
                if (reminder is Reminder.StartsOwing) {
                    warned += (at + reminder.inMin) % WEEK_MINUTES
                }
                val wait = nextCheckInMin(now, next)
                if (wait == null) { roundTheClock += area.code; break }
                at += wait
            }
            if (area.code in roundTheClock) return@forEach
            assertTrue(
                "${area.code}: chain stalled after $steps steps at minute $at",
                steps < 200,
            )
            // Sunday-evening runs that wrap into Monday begin before this park
            // did, so their start is not reachable from a Monday-00:00 arrival.
            val reachable = starts.filter { it >= OWING_LEAD_MIN }
            assertEquals(
                "${area.code} missed a charging start",
                emptySet<Int>(),
                reachable.toSet() - warned,
            )
        }
        assertEquals(setOf("T11V", "T12V", "T13V"), roundTheClock)
    }
}
