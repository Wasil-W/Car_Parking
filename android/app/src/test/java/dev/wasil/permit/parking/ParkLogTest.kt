package dev.wasil.permit.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkLogTest {

    private fun park(
        startedAtMs: Long,
        settlement: Settlement = Settlement.UNSETTLED,
        holder: VehicleId? = null,
        paid: Boolean? = true,
        endedAtMs: Long? = null,
    ) = ParkRecord(
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        settlement = settlement,
        holder = holder,
        place = "Molenwijk · Computerweg",
        rateText = "€3,01/h · until 19:00",
        paid = paid,
    )

    // --- the round trip, which is the whole reason this is not in the store ---

    @Test fun `a log survives being written and read back`() {
        val records = listOf(
            park(1_000, Settlement.PERMIT, WASIL, endedAtMs = 2_000),
            park(3_000, Settlement.HOME, paid = false),
        )
        assertEquals(records, decodeParkLog(encodeParkLog(records)))
    }

    @Test fun `every field survives, including the ones that can be null`() {
        val bare = ParkRecord(startedAtMs = 42)
        val round = decodeParkLog(encodeParkLog(listOf(bare))).single()
        assertEquals(bare, round)
        assertNull(round.endedAtMs)
        assertNull(round.holder)
        assertNull(round.place)
        assertNull(round.rateText)
        assertNull(round.paid)
        assertEquals(Settlement.UNKNOWN, round.settlement)
    }

    /**
     * The park-log half of the migration. Records written before the roster
     * stored a `MyCar`, which serialised as `"WASIL"` / `"WALID"`; ids are the
     * lowercase form of exactly those strings. Without the lowercase on the way
     * in, months of history would keep its rows and lose every "Permit · Wasil"
     * badge to an id that matches no car — quietly wrong, which is the one
     * thing a log must not be.
     */
    @Test fun `a log written before the roster still names the right car`() {
        val v065 = """
            [{"startedAtMs":1000,"endedAtMs":2000,"settlement":"PERMIT","holder":"WASIL",
              "place":"Molenwijk · Computerweg","paid":true},
             {"startedAtMs":3000,"settlement":"PERMIT","holder":"WALID","paid":true}]
        """.trimIndent()
        val decoded = decodeParkLog(v065)
        assertEquals(listOf(WASIL, WALID), decoded.map { it.holder })
    }

    @Test fun `an id already in the new shape round-trips unchanged`() {
        val record = park(1_000, Settlement.PERMIT, WASIL)
        assertEquals(WASIL, decodeParkLog(encodeParkLog(listOf(record))).single().holder)
    }

    @Test fun `a store that has never held a log reads as empty`() {
        assertEquals(emptyList<ParkRecord>(), decodeParkLog(null))
        assertEquals(emptyList<ParkRecord>(), decodeParkLog(""))
        assertEquals(emptyList<ParkRecord>(), decodeParkLog("   "))
    }

    @Test fun `corrupt or half-written data reads as empty rather than throwing`() {
        assertEquals(emptyList<ParkRecord>(), decodeParkLog("[{\"startedAtMs\":"))
        assertEquals(emptyList<ParkRecord>(), decodeParkLog("not json at all"))
        // A shape from some other version: an object where a list belongs.
        assertEquals(emptyList<ParkRecord>(), decodeParkLog("{\"startedAtMs\":1}"))
    }

    @Test fun `an unknown field from a later version does not lose the log`() {
        val json = "[{\"startedAtMs\":7,\"settlement\":\"HOME\",\"costCents\":100}]"
        assertEquals(listOf(ParkRecord(startedAtMs = 7, settlement = Settlement.HOME)), decodeParkLog(json))
    }

    // --- the cap ---

    @Test fun `the log is capped, keeping the newest`() {
        val many = (1..PARK_LOG_CAP + 50).map { park(it.toLong()) }
        val kept = decodeParkLog(encodeParkLog(many))
        assertEquals(PARK_LOG_CAP, kept.size)
        assertEquals(51L, kept.first().startedAtMs)
        assertEquals((PARK_LOG_CAP + 50).toLong(), kept.last().startedAtMs)
    }

    @Test fun `appending past the cap drops the oldest, not the newest`() {
        val full = (1..PARK_LOG_CAP).map { park(it.toLong()) }
        val after = full.withPark(park(9_999))
        assertEquals(PARK_LOG_CAP, after.size)
        assertEquals(9_999L, after.last().startedAtMs)
        assertEquals(2L, after.first().startedAtMs)
    }

    @Test fun `appending is a plain append while there is room`() {
        val one = listOf(park(1_000))
        assertEquals(listOf(park(1_000), park(2_000)), one.withPark(park(2_000)))
    }

    // --- closing, which is the only end this app ever observes ---

    @Test fun `closing fills in the end of the newest open park`() {
        val closed = listOf(park(1_000)).withOpenParkClosed(5_000)
        assertEquals(5_000L, closed.single().endedAtMs)
    }

    @Test fun `closing twice does not move an end that is already set`() {
        val once = listOf(park(1_000)).withOpenParkClosed(5_000)
        val twice = once.withOpenParkClosed(9_000)
        assertEquals(5_000L, twice.single().endedAtMs)
    }

    @Test fun `closing an empty log does nothing`() {
        val empty = emptyList<ParkRecord>()
        assertSame(empty, empty.withOpenParkClosed(5_000))
    }

    @Test fun `an end before the start is refused rather than stored`() {
        // A clock that has gone backwards is not a park that lasted -3 minutes.
        val log = listOf(park(5_000)).withOpenParkClosed(4_000)
        assertNull(log.single().endedAtMs)
    }

    @Test fun `only the newest park is closed`() {
        val log = listOf(park(1_000), park(3_000)).withOpenParkClosed(5_000)
        assertNull(log.first().endedAtMs)
        assertEquals(5_000L, log.last().endedAtMs)
    }

    // --- re-badging after the fact ---

    @Test fun `a claim made after detection re-badges the open park`() {
        val log = listOf(park(1_000, Settlement.UNSETTLED))
            .withOpenParkSettled(Settlement.PERMIT, WALID)
        assertEquals(Settlement.PERMIT, log.single().settlement)
        assertEquals(WALID, log.single().holder)
    }

    @Test fun `a finished park is never re-badged — a claim now belongs to the next one`() {
        val log = listOf(park(1_000, Settlement.UNSETTLED, endedAtMs = 2_000))
            .withOpenParkSettled(Settlement.PERMIT, WASIL)
        assertEquals(Settlement.UNSETTLED, log.single().settlement)
    }

    @Test fun `handing the permit away leaves a known-paid spot unsettled`() {
        val log = listOf(park(1_000, Settlement.PERMIT, WASIL, paid = true))
            .withOpenParkUncovered()
        assertEquals(Settlement.UNSETTLED, log.single().settlement)
        assertNull(log.single().holder)
    }

    @Test fun `handing it away where no zone resolved says so, rather than claiming a debt`() {
        val log = listOf(park(1_000, Settlement.PERMIT, WASIL, paid = null))
            .withOpenParkUncovered()
        assertEquals(Settlement.UNKNOWN, log.single().settlement)
    }

    @Test fun `handing it away leaves a free park alone`() {
        val home = listOf(park(1_000, Settlement.HOME, paid = false))
        assertEquals(Settlement.HOME, home.withOpenParkUncovered().single().settlement)
    }

    @Test fun `an empty log survives every operation`() {
        val empty = emptyList<ParkRecord>()
        assertTrue(empty.withOpenParkSettled(Settlement.PERMIT, WASIL).isEmpty())
        assertTrue(empty.withOpenParkUncovered().isEmpty())
        assertTrue(empty.withOpenParkClosed(1).isEmpty())
    }
}
