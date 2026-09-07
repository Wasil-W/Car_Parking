package dev.wasil.permit.parking

import dev.wasil.permit.parking.zones.Reminder
import dev.wasil.permit.parking.zones.TariffNow
import dev.wasil.permit.parking.zones.reminderFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The other phone taking the permit must un-cover this park — and the reminder
 * must then start speaking.
 *
 * Found auditing v0.8.0 the hour before its tag, and it was two bugs wearing one
 * coat. [withOpenParkUncovered] existed and was documented for exactly this case
 * — its own comment says *"the permit was handed to the other car while this one
 * was still parked"* — but the only caller was `ClaimPermit`, the path where
 * **this** phone gives the permit away. When the **other** phone took it,
 * `SharedSync.watchForTakeover` raised a notification and touched nothing else.
 *
 * So the open record went on saying [Settlement.PERMIT]:
 *
 * 1. **History badged a park "Permit"** that the permit had already abandoned.
 * 2. **The reminder went silent** — `ParkReminderWorker` reads that exact field
 *    for `permitSettles`, so a takeover permanently suppressed the warning in
 *    the one situation the whole feature was built for: your brother has the
 *    permit and your car is on a paid street. Silence, in the direction that
 *    costs a fine.
 *
 * These tests compose the two halves the way the app now does, so the pair
 * cannot drift apart again without failing here.
 */
class TakeoverUncoversParkTest {

    private val paidSpotFreeUntilNine = TariffNow.Free(startsInMin = 20)

    private fun openParkOnPermit(paid: Boolean?) = listOf(
        ParkRecord(
            startedAtMs = 1_000,
            endedAtMs = null,
            settlement = Settlement.PERMIT,
            holder = WASIL,
            place = "Molenwijk · Computerweg",
            rateText = "€3,01/h · until 19:00",
            paid = paid,
        ),
    )

    /** Reads `permitSettles` the way `ParkReminderWorker` does. */
    private fun settles(log: List<ParkRecord>): Boolean =
        log.lastOrNull()?.let { it.endedAtMs == null && it.settlement == Settlement.PERMIT } == true

    @Test
    fun `before the takeover the permit settles it and nothing is said`() {
        val log = openParkOnPermit(paid = true)
        assertTrue(settles(log))
        assertNull(
            reminderFor(
                parked = true, inPaidArea = true,
                permitSettles = settles(log), now = paidSpotFreeUntilNine,
            ),
        )
    }

    @Test
    fun `after the takeover the same spot warns before it starts charging`() {
        val log = openParkOnPermit(paid = true).withOpenParkUncovered()

        assertEquals(Settlement.UNSETTLED, log.last().settlement)
        assertNull("the holder must go with the permit", log.last().holder)
        assertTrue("history must stop calling this a permit park", !settles(log))

        val reminder = reminderFor(
            parked = true, inPaidArea = true,
            permitSettles = settles(log), now = paidSpotFreeUntilNine,
        )
        assertTrue("a taken-over park must be warned about", reminder is Reminder.StartsOwing)
        assertEquals(20, reminder!!.inMin)
    }

    @Test
    fun `a park whose spot was never resolved becomes unknown, not unsettled`() {
        // "The permit left" is not evidence that anything was owed. This is the
        // same three-way honesty `parkedOutsideKnown` exists for.
        val log = openParkOnPermit(paid = null).withOpenParkUncovered()
        assertEquals(Settlement.UNKNOWN, log.last().settlement)
    }

    @Test
    fun `a park that already ended is finished business`() {
        // A takeover now belongs to the next park, not to one the car has left.
        val ended = openParkOnPermit(paid = true)
            .map { it.copy(endedAtMs = 9_000) }
        assertEquals(ended, ended.withOpenParkUncovered())
    }

    @Test
    fun `a park at home stays a park at home wherever the permit goes`() {
        val home = listOf(
            ParkRecord(startedAtMs = 1_000, settlement = Settlement.HOME, paid = false),
        )
        assertEquals(home, home.withOpenParkUncovered())
    }
}
