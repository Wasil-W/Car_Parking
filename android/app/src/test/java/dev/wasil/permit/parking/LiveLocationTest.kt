package dev.wasil.permit.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLocationTest {
    private val now = 1_000_000_000_000L
    private val spot = GeoPoint(52.3702, 4.8952, 8f)

    // --- the promise: a driving position cannot reach the claim path ---

    @Test
    fun `a sample taken while the link is up seals to nothing`() {
        val live = LiveLocation.captured(spot, now)
        assertNull(live.sealAtDisconnect(linkStillUp = true, nowMs = now))
    }

    @Test
    fun `every sample of a whole drive seals to nothing while still connected`() {
        // The shape of the actual bug we are guarding against: the sampler runs
        // for the length of a drive, and at no point during it may any of what
        // it collected become something the app would act on.
        val drive = (0..30).map { LiveLocation.captured(spot, now + it * LIVE_POLL_INTERVAL_MS) }
        val sealed = drive.map { it.sealAtDisconnect(linkStillUp = true, nowMs = now + 600_000) }
        assertTrue("no driving sample may seal", sealed.all { it == null })
    }

    @Test
    fun `the last sample seals once the link has dropped`() {
        val live = LiveLocation.captured(spot, now)
        val fix = live.sealAtDisconnect(linkStillUp = false, nowMs = now + 20_000)
        assertEquals(spot, fix?.point)
    }

    @Test
    fun `a sample older than the cap is refused`() {
        // The dangerous direction: the oldest thing a trail can hold is the
        // start of this drive — the driveway — and sealing that would tell the
        // app "parked at home, no permit needed" with the car sitting in town.
        val live = LiveLocation.captured(spot, now)
        assertNull(live.sealAtDisconnect(false, nowMs = now + LIVE_FIX_MAX_AGE_MS + 1))
    }

    @Test
    fun `a sample exactly at the cap is still accepted`() {
        val live = LiveLocation.captured(spot, now)
        assertEquals(spot, live.sealAtDisconnect(false, nowMs = now + LIVE_FIX_MAX_AGE_MS)?.point)
    }

    @Test
    fun `a clock that moved backwards is not a brand new sample`() {
        val live = LiveLocation.captured(spot, now)
        assertNull(live.sealAtDisconnect(false, nowMs = now - 1))
    }

    @Test
    fun `the trail-level seal refuses while connected and yields once not`() {
        val last = LiveLocation.captured(spot, now)
        assertNull(sealAtDisconnect(connected = true, last = last, nowMs = now))
        assertEquals(spot, sealAtDisconnect(connected = false, last = last, nowMs = now)?.point)
    }

    @Test
    fun `no trail at all seals to nothing`() {
        assertNull(sealAtDisconnect(connected = false, last = null, nowMs = now))
    }

    // --- surviving the drive: the store round trip ---

    @Test
    fun `encode and decode round trip`() {
        val live = LiveLocation.captured(spot, now)
        assertEquals(live, LiveLocation.decode(live.encode()))
    }

    @Test
    fun `decode of anything unreadable is no trail rather than a wrong one`() {
        // A trail we cannot read must land on the safe side — the app asks,
        // exactly as it did before any of this existed.
        listOf(null, "", "nonsense", "52.37|4.89", "52.37|4.89|8|x", "a|b|c|d").forEach { raw ->
            assertNull("expected null for '$raw'", LiveLocation.decode(raw))
        }
    }

    @Test
    fun `a decoded sample still obeys the seal rules`() {
        val decoded = LiveLocation.decode(LiveLocation.captured(spot, now).encode())
        assertNull("the round trip must not launder a driving position",
            decoded?.sealAtDisconnect(linkStillUp = true, nowMs = now))
        assertEquals(spot, decoded?.sealAtDisconnect(linkStillUp = false, nowMs = now)?.point)
    }

    // --- housekeeping the hand-written class has to get right itself ---

    @Test
    fun `samples compare by position and time`() {
        assertEquals(LiveLocation.captured(spot, now), LiveLocation.captured(spot, now))
        assertEquals(
            LiveLocation.captured(spot, now).hashCode(),
            LiveLocation.captured(spot, now).hashCode(),
        )
        assertNotEquals(LiveLocation.captured(spot, now), LiveLocation.captured(spot, now + 1))
        assertNotEquals(
            LiveLocation.captured(spot, now),
            LiveLocation.captured(GeoPoint(52.0, 4.0, 8f), now),
        )
    }

    @Test
    fun `printing a sample does not print where the car is`() {
        // A driving position in a log line or a crash report is the one place
        // this data has no business being.
        val printed = LiveLocation.captured(spot, now).toString()
        assertFalse(printed.contains("52.37"))
        assertFalse(printed.contains("4.895"))
    }
}
