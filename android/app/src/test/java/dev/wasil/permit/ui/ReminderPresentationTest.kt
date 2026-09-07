package dev.wasil.permit.ui

import dev.wasil.permit.parking.zones.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderPresentationTest {

    // ---- the title is a clock time, never a countdown ----------------------

    @Test
    fun `it names the hour the meter starts, not the minutes until it`() {
        // Monday 08:30, charging in 30. The whole reason for a wall-clock time:
        // this sentence is still true if the notification sits unread.
        assertEquals(
            "You start paying here at 09:00",
            reminderTitle(Reminder.StartsOwing(30, "€3,01/h"), dayOfWeek = 0, minuteOfDay = 510),
        )
    }

    @Test
    fun `becoming free is phrased as a start, because that is what you plan around`() {
        assertEquals(
            "Free here from 19:00",
            reminderTitle(Reminder.BecomesFree(10), dayOfWeek = 0, minuteOfDay = 1130),
        )
    }

    @Test
    fun `a boundary on the other side of midnight says which day`() {
        // Sunday 23:50, charging in 20 — T17N's shape. Bare "00:10" would read
        // as ten past midnight *tonight already gone*, which is the wrong night.
        assertEquals(
            "You start paying here at ma 00:10",
            reminderTitle(Reminder.StartsOwing(20, "€1,72/h"), dayOfWeek = 6, minuteOfDay = 1430),
        )
    }

    @Test
    fun `midnight itself is written 24 00, the way the city writes it`() {
        // Six areas charge until midnight and stop. "Free here from ma 00:00"
        // for tonight is a puzzle; "from 24:00" is not — and it is the
        // convention Amsterdam's own data uses ("900-2400").
        assertEquals(
            "Free here from 24:00",
            reminderTitle(Reminder.BecomesFree(10), dayOfWeek = 0, minuteOfDay = 1430),
        )
    }

    // ---- the body carries the rate and the place, and neither is required ---

    @Test
    fun `the body is the rate then the place`() {
        assertEquals(
            "€3,01/h · Molenwijk · Computerweg",
            reminderBody(Reminder.StartsOwing(30, "€3,01/h"), "Molenwijk · Computerweg"),
        )
    }

    @Test
    fun `a missing rate or place shortens the line rather than blocking it`() {
        assertEquals(
            "Molenwijk · Computerweg",
            reminderBody(Reminder.StartsOwing(30, ""), "Molenwijk · Computerweg"),
        )
        assertEquals("€3,01/h", reminderBody(Reminder.StartsOwing(30, "€3,01/h"), null))
    }

    @Test
    fun `with neither, it says the one thing still certainly true`() {
        // The title already carries the time, which is the actionable half. This
        // is the rest of the sentence, and it is a restatement of the condition
        // that produced the reminder rather than anything guessed.
        assertEquals(
            "Nothing is covering this spot yet.",
            reminderBody(Reminder.StartsOwing(30, ""), null),
        )
    }

    @Test
    fun `an unnamed spot turning free gets no second line at all`() {
        // "Free here from 19:00" has said everything. A body repeating it would
        // be worse than no body.
        assertNull(reminderBody(Reminder.BecomesFree(10), null))
        assertEquals("Molenwijk", reminderBody(Reminder.BecomesFree(10), "Molenwijk"))
    }

    @Test
    fun `no holder is ever named`() {
        // The mockup drew "Walid's car has the permit" and the shipped copy drops
        // it: the only local record of the holder is marked presentation-only
        // because it goes stale, and this project has a rule about publishing a
        // guess as a fact. Pinned as a test so it cannot drift back in.
        val body = reminderBody(Reminder.StartsOwing(30, "€3,01/h"), "Molenwijk")
        listOf("Wasil", "Walid", "permit").forEach {
            assert(!body!!.contains(it, ignoreCase = true)) { "body named $it: $body" }
        }
    }
}
