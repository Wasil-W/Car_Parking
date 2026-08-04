package dev.wasil.permit.parking.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class TariffPreferenceTest {
    private val now = 1_000_000_000_000L

    /** Parked outside, heartbeat a minute old, at [cents] per hour. */
    private fun spot(cents: Int?, fresh: Boolean = true) = PhoneState(
        parkedOutside = true,
        rateCentsPerHour = cents,
        parkedAtMs = now - 120_000,
        heartbeatAtMs = if (fresh) now - 60_000 else now - ClaimGuard.STALE_AFTER_MS - 1,
    )

    /** At home or in a free zone: not parked outside at all. */
    private val idle = PhoneState(parkedOutside = false, heartbeatAtMs = now - 60_000)

    private fun holder(mine: PhoneState, other: PhoneState?) =
        preferredPermitHolder(mine, other, now)

    // --- the ordinary case, and the one the release exists for ------------

    @Test
    fun `the more expensive spot keeps the permit`() {
        assertEquals(PermitPreference.Mine, holder(spot(805), spot(301)))
        assertEquals(PermitPreference.Theirs, holder(spot(301), spot(805)))
    }

    @Test
    fun `a single cent is enough to decide it`() {
        assertEquals(PermitPreference.Mine, holder(spot(302), spot(301)))
        assertEquals(PermitPreference.Theirs, holder(spot(301), spot(302)))
    }

    // --- ties -------------------------------------------------------------

    /**
     * Documented tie-break: nobody. Swapping between two equally expensive
     * spots gains nothing and uncovers both cars while it happens.
     */
    @Test
    fun `equal rates move nothing`() {
        assertEquals(PermitPreference.NoPreference, holder(spot(301), spot(301)))
    }

    /**
     * The property that makes the tie-break safe: both phones run this
     * independently, so the two answers must never both be "me". Checked
     * across the whole matrix rather than asserted by hand.
     */
    @Test
    fun `the two phones can never both conclude they win`() {
        val rates = listOf(null, 0, 172, 301, 805)
        val states = rates.map { spot(it) } + idle
        for (a in states) {
            for (b in states) {
                val fromA = holder(a, b)
                val fromB = holder(b, a)
                val bothClaim = fromA == PermitPreference.Mine && fromB == PermitPreference.Mine
                assertEquals("both claimed for a=$a b=$b", false, bothClaim)
                val bothYield = fromA == PermitPreference.Theirs && fromB == PermitPreference.Theirs
                assertEquals("both yielded for a=$a b=$b", false, bothYield)
            }
        }
    }

    /** Mirroring the arguments must mirror the verdict, not change it. */
    @Test
    fun `the comparison is symmetric`() {
        val rates = listOf(null, 0, 172, 805)
        val states = rates.map { spot(it) } + idle
        for (a in states) {
            for (b in states) {
                val mirrored = when (holder(a, b)) {
                    PermitPreference.Mine -> PermitPreference.Theirs
                    PermitPreference.Theirs -> PermitPreference.Mine
                    PermitPreference.NoPreference -> PermitPreference.NoPreference
                }
                assertEquals("a=$a b=$b", mirrored, holder(b, a))
            }
        }
    }

    // --- one side free ----------------------------------------------------

    @Test
    fun `a paying spot beats one that is free right now`() {
        assertEquals(PermitPreference.Mine, holder(spot(301), spot(0)))
        assertEquals(PermitPreference.Theirs, holder(spot(0), spot(301)))
    }

    @Test
    fun `both free right now moves nothing`() {
        assertEquals(PermitPreference.NoPreference, holder(spot(0), spot(0)))
    }

    /**
     * A paid zone outside its hours still outranks being at home: the meter
     * restarts where that car is standing, and never restarts on the drive.
     */
    @Test
    fun `a paid zone that is free right now still beats not being parked outside`() {
        assertEquals(PermitPreference.Mine, holder(spot(0), idle))
        assertEquals(PermitPreference.Theirs, holder(idle, spot(0)))
    }

    @Test
    fun `neither car parked outside moves nothing`() {
        assertEquals(PermitPreference.NoPreference, holder(idle, idle))
    }

    // --- the unknown side, and the safe bias -------------------------------

    /**
     * An unpriceable spot is assumed to be charging, matching the app's
     * existing "tariff data missing means assume paid" rule — so it beats a
     * spot known to cost nothing.
     */
    @Test
    fun `a spot we cannot price outranks one known to be free`() {
        assertEquals(PermitPreference.Mine, holder(spot(null), spot(0)))
        assertEquals(PermitPreference.Theirs, holder(spot(0), spot(null)))
    }

    @Test
    fun `a spot we cannot price outranks not being parked outside`() {
        assertEquals(PermitPreference.Mine, holder(spot(null), idle))
        assertEquals(PermitPreference.Theirs, holder(idle, spot(null)))
    }

    /**
     * The core of the safe bias: an unpriced spot against a priced one is
     * genuinely undecidable, so the permit does not move. Answering here would
     * mean guessing in a direction that can cost a fine.
     */
    @Test
    fun `an unpriced spot against a priced one refuses to decide`() {
        assertEquals(PermitPreference.NoPreference, holder(spot(null), spot(805)))
        assertEquals(PermitPreference.NoPreference, holder(spot(805), spot(null)))
    }

    @Test
    fun `neither spot priceable refuses to decide`() {
        assertEquals(PermitPreference.NoPreference, holder(spot(null), spot(null)))
    }

    /** A corrupt negative rate must not read as free and talk a car out of its permit. */
    @Test
    fun `a negative rate is treated as unreadable rather than as free`() {
        assertEquals(PermitPreference.NoPreference, holder(spot(-50), spot(805)))
        assertEquals(PermitPreference.Mine, holder(spot(-50), spot(0)))
    }

    // --- the other side missing or stale -----------------------------------

    @Test
    fun `an other phone that has never published is no claim on the permit`() {
        assertEquals(PermitPreference.Mine, holder(spot(301), null))
        assertEquals(PermitPreference.Mine, holder(spot(null), null))
        assertEquals(PermitPreference.NoPreference, holder(idle, null))
    }

    /** Same reading ClaimGuard takes: a six-hour-old heartbeat is not evidence. */
    @Test
    fun `a stale other side is disregarded even when it published a higher rate`() {
        assertEquals(PermitPreference.Mine, holder(spot(172), spot(805, fresh = false)))
    }

    @Test
    fun `a stale other side leaves an idle phone with no preference`() {
        assertEquals(PermitPreference.NoPreference, holder(idle, spot(805, fresh = false)))
    }

    /** The boundary must match ClaimGuard exactly, or the two disagree about the same car. */
    @Test
    fun `a heartbeat exactly at the cutoff still counts`() {
        val edge = spot(805).copy(heartbeatAtMs = now - ClaimGuard.STALE_AFTER_MS)
        assertEquals(PermitPreference.Theirs, holder(spot(172), edge))

        val justPast = spot(805).copy(heartbeatAtMs = now - ClaimGuard.STALE_AFTER_MS - 1)
        assertEquals(PermitPreference.Mine, holder(spot(172), justPast))
    }

    /** My own state comes from local storage, so its heartbeat says nothing. */
    @Test
    fun `my own stale heartbeat does not weaken my claim`() {
        assertEquals(PermitPreference.Mine, holder(spot(805, fresh = false), spot(301)))
    }

    // --- the guarantee that started the release ----------------------------

    /**
     * Reads as a formality and is the whole point: everything above decided
     * the permit from two integers and a timestamp. There is no location on
     * [PhoneState] to have consulted.
     */
    @Test
    fun `the decision needs nothing but a price and a heartbeat`() {
        val mine = PhoneState(parkedOutside = true, rateCentsPerHour = 805)
        val other = PhoneState(
            parkedOutside = true, rateCentsPerHour = 301, heartbeatAtMs = now,
        )
        assertEquals(PermitPreference.Mine, preferredPermitHolder(mine, other, now))
    }
}
