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
}
