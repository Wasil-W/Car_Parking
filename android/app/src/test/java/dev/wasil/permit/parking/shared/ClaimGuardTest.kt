package dev.wasil.permit.parking.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimGuardTest {
    private val now = 1_000_000_000_000L
    private val parkedFresh = PhoneState(
        parkedOutside = true, parkedAtMs = now - 60_000, heartbeatAtMs = now - 60_000,
    )

    @Test
    fun `no data about the other proceeds`() {
        assertEquals(ClaimGuard.Verdict.Proceed,
            ClaimGuard.evaluate(null, "XX123Y", "XX123Y", now))
    }

    @Test
    fun `other not parked proceeds`() {
        val idle = parkedFresh.copy(parkedOutside = false)
        assertEquals(ClaimGuard.Verdict.Proceed,
            ClaimGuard.evaluate(idle, "XX123Y", "XX123Y", now))
    }

    @Test
    fun `other parked fresh and holding blocks`() {
        val verdict = ClaimGuard.evaluate(parkedFresh, "XX123Y", "XX123Y", now)
        assertTrue(verdict is ClaimGuard.Verdict.Blocked)
    }

    @Test
    fun `other parked but permit not on their plate proceeds`() {
        assertEquals(ClaimGuard.Verdict.Proceed,
            ClaimGuard.evaluate(parkedFresh, "XX123Y", "RH950F", now))
        assertEquals(ClaimGuard.Verdict.Proceed,
            ClaimGuard.evaluate(parkedFresh, "XX123Y", null, now))
    }

    @Test
    fun `stale heartbeat proceeds`() {
        val stale = parkedFresh.copy(heartbeatAtMs = now - ClaimGuard.STALE_AFTER_MS - 1)
        assertEquals(ClaimGuard.Verdict.Proceed,
            ClaimGuard.evaluate(stale, "XX123Y", "XX123Y", now))
    }

    @Test
    fun `heartbeat exactly at the cutoff still blocks`() {
        val edge = parkedFresh.copy(heartbeatAtMs = now - ClaimGuard.STALE_AFTER_MS)
        assertTrue(ClaimGuard.evaluate(edge, "XX123Y", "XX123Y", now)
            is ClaimGuard.Verdict.Blocked)
    }
}
