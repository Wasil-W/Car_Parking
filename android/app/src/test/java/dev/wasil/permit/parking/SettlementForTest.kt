package dev.wasil.permit.parking

import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.ZoneInfo
import dev.wasil.permit.parking.zones.ZonePolygon
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which badge a finished park earns. This is the join between the two halves of
 * the app — a zone (obligation) and an outcome (settlement) — so it is the one
 * place they could quietly be confused for each other.
 */
class SettlementForTest {

    private val area = TariffArea(
        "T11V", "Centrum", "€8,05/h",
        listOf(ZonePolygon(outer = listOf(LatLng(52.0, 4.0), LatLng(52.0, 5.0), LatLng(53.0, 4.0)))),
    )

    @Test fun `a claim that landed is a permit park`() {
        assertEquals(
            Settlement.PERMIT,
            settlementFor(ZoneInfo.Paid(area), ParkOutcome.Claimed("RH950F")),
        )
    }

    /**
     * The claim is checked before the zone on purpose: if the permit ended up
     * on this car, it covered the spot whether or not the position could be
     * resolved. "Where were you" being unknown does not make "covered" unknown.
     */
    @Test fun `a claim with no zone resolved is still a permit park`() {
        assertEquals(Settlement.PERMIT, settlementFor(null, ParkOutcome.Claimed("RH950F")))
    }

    @Test fun `the free kinds keep their own names`() {
        assertEquals(Settlement.HOME, settlementFor(ZoneInfo.Home, ParkOutcome.FreeZoneParked))
        assertEquals(
            Settlement.FREE_ZONE,
            settlementFor(ZoneInfo.ManualFree("Mum's street"), ParkOutcome.FreeZoneParked),
        )
        assertEquals(
            Settlement.FREE_STREET,
            settlementFor(ZoneInfo.FreeStreet, ParkOutcome.FreeZoneParked),
        )
    }

    @Test fun `a paid spot nothing covered is unsettled, and that is honest today`() {
        assertEquals(
            Settlement.UNSETTLED,
            settlementFor(ZoneInfo.Paid(area), ParkOutcome.ManualNeeded),
        )
        assertEquals(
            Settlement.UNSETTLED,
            settlementFor(ZoneInfo.Paid(null), ParkOutcome.SwitchFailed),
        )
    }

    /**
     * The distinction the whole app is built on. A failed position read is a
     * gap in what we know, and it must never render as the finding that nothing
     * was owed — that is the direction that costs a fine.
     */
    @Test fun `no zone at all is unknown, never free and never a debt`() {
        assertEquals(Settlement.UNKNOWN, settlementFor(null, ParkOutcome.ManualNeeded))
        assertEquals(Settlement.UNKNOWN, settlementFor(null, ParkOutcome.NotConfigured))
    }
}
