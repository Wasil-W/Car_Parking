package dev.wasil.permit.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roster that replaced `MyCar`.
 *
 * This file also replaces `MyCarForLabelTest`, which pinned the coupling this
 * release deleted: `myCarForLabel("Wasil")` turned a notification's own display
 * text back into an identity, and mapped anything it did not recognise onto
 * Walid. There is nothing left to round-trip — a name is text, an id is an id.
 */
class RosterTest {

    private val wasil = slotIdFor(0)
    private val walid = slotIdFor(1)

    // --- slots, which are the wire keys and the identity order ---

    @Test fun `the first two slot ids are the strings the shared room already uses`() {
        // Not cosmetic. Both phones' state lives at /rooms/<hash>/wasil and
        // /rooms/<hash>/walid, written under MyCar.key() = name.lowercase().
        // Changing these would orphan an existing pairing.
        assertEquals("wasil", wasil.value)
        assertEquals("walid", walid.value)
    }

    @Test fun `slot ids and their indexes are inverses`() {
        (0..4).forEach { assertEquals(it, slotIndexOf(slotIdFor(it))) }
    }

    @Test fun `an id from nowhere sorts last rather than throwing`() {
        assertEquals(Int.MAX_VALUE, slotIndexOf(VehicleId("something-else")))
    }

    // --- the seed ---

    @Test fun `the seed roster is the two cars this app has, with no plates yet`() {
        assertEquals(listOf("Wasil", "Walid"), Roster.SEED.vehicles.map { it.name })
        assertEquals(0, Roster.SEED.platedCount)
    }

    @Test fun `a roster can never be empty, so this phone's car always resolves`() {
        assertEquals(Roster.SEED.vehicles, Roster.of(emptyList()).vehicles)
    }

    // --- arity, which every two-car behaviour is selected on ---

    @Test fun `two cars have an other, and identity hues`() {
        val roster = legacyRoster("RH950F", "XX123Y")
        assertEquals(walid, roster.other(wasil)?.id)
        assertEquals(wasil, roster.other(walid)?.id)
        assertEquals(0, roster.identitySlotOf(wasil))
        assertEquals(1, roster.identitySlotOf(walid))
    }

    @Test fun `one car has no other to hand anything to`() {
        val roster = rosterFrom(listOf("RH950F"), Roster.SEED)
        assertEquals(1, roster.size)
        assertNull(roster.other(roster[0].id))
    }

    /**
     * The rule from `USER-MODEL.md`: identity by hue is a two-body affordance.
     * There is no third safe hue in this palette, so past two the answer is not
     * a new colour, it is no colour — and every caller already has that branch,
     * because it is the one drawn when nobody holds the permit.
     */
    @Test fun `three cars get no identity hue at all, rather than an invented one`() {
        val roster = rosterFrom(listOf("AA111A", "BB222B", "CC333C"), Roster.SEED)
        assertEquals(3, roster.size)
        roster.vehicles.forEach { assertNull(roster.identitySlotOf(it.id)) }
        assertNull(roster.other(roster[0].id))
    }

    // --- the holder, matched on the plate rather than on a label ---

    @Test fun `the holder is whoever's plate the permit site reports`() {
        val roster = legacyRoster("RH950F", "XX123Y")
        assertEquals(wasil, roster.holderFor("RH950F")?.id)
        assertEquals(walid, roster.holderFor("XX123Y")?.id)
    }

    @Test fun `an unknown, absent or blank plate has no holder`() {
        val roster = legacyRoster("RH950F", "XX123Y")
        assertNull(roster.holderFor(null))
        assertNull(roster.holderFor("ZZ999Z"))
        assertNull(roster.holderFor(""))
    }

    /**
     * A seeded slot has a blank plate, and a blank active plate must not match
     * it — that would report a car as holding the permit because the permit
     * site said nothing at all.
     */
    @Test fun `a car with no plate never matches`() {
        assertNull(Roster.SEED.holderFor(""))
        assertNull(Roster.SEED.holderFor(null))
    }

    // --- building the roster from the permit account ---

    @Test fun `plates from the account are normalised and sorted into slots`() {
        val roster = rosterFrom(listOf("xx-123-y", "rh 950 f"), Roster.SEED)
        assertEquals(listOf("RH950F", "XX123Y"), roster.vehicles.map { it.plate })
        assertEquals(listOf(wasil, walid), roster.vehicles.map { it.id })
    }

    /**
     * The property two phones depend on and cannot negotiate: they read the
     * same account and must land on the same slots. Ordering by "mine first"
     * would give each phone the mirror of the other's roster, so every shared
     * write would go to the other's node and the two identity colours would
     * swap between handsets.
     */
    @Test fun `slot order does not depend on which car is mine`() {
        val fromWasilsPhone = rosterFrom(listOf("RH950F", "XX123Y"), Roster.SEED)
        val fromWalidsPhone = rosterFrom(listOf("XX123Y", "RH950F"), Roster.SEED)
        assertEquals(fromWasilsPhone.vehicles, fromWalidsPhone.vehicles)
    }

    /**
     * Re-saving the permit must not move a car between slots. It would swap its
     * colour on both phones and silently redirect its Firebase node — which is
     * the same pairing loss the whole migration exists to avoid.
     */
    @Test fun `a plate already in the roster keeps its slot, whatever the sort says`() {
        // Wasil's install migrates with XX123Y in slot 0, against sort order.
        val existing = legacyRoster("XX123Y", "RH950F")
        val rebuilt = rosterFrom(listOf("RH950F", "XX123Y"), existing)
        assertEquals("XX123Y", rebuilt.vehicles.first { it.id == wasil }.plate)
        assertEquals("RH950F", rebuilt.vehicles.first { it.id == walid }.plate)
    }

    @Test fun `a new plate takes the slot the departing one vacated`() {
        val existing = legacyRoster("RH950F", "XX123Y")
        val rebuilt = rosterFrom(listOf("RH950F", "ZZ999Z"), existing)
        assertEquals("RH950F", rebuilt.vehicles.first { it.id == wasil }.plate)
        assertEquals("ZZ999Z", rebuilt.vehicles.first { it.id == walid }.plate)
    }

    @Test fun `a car keeps its name when only its plate changed`() {
        val rebuilt = rosterFrom(listOf("RH950F", "XX123Y"), legacyRoster("RH950F", "XX123Y"))
        assertEquals(listOf("Wasil", "Walid"), rebuilt.vehicles.map { it.name })
    }

    /**
     * An account that answered with nothing is a failed read, not a statement
     * that the cars are gone — and wiping the roster would take both plates and
     * this phone's own identity with it.
     */
    @Test fun `no plates at all leaves the roster alone`() {
        val existing = legacyRoster("RH950F", "XX123Y")
        assertEquals(existing.vehicles, rosterFrom(emptyList(), existing).vehicles)
        assertEquals(existing.vehicles, rosterFrom(listOf("", "   "), existing).vehicles)
    }

    @Test fun `a third car is named by its plate rather than by an invented person`() {
        val roster = rosterFrom(listOf("AA111A", "BB222B", "CC333C"), Roster.SEED)
        assertEquals("CC333C", roster[2].name)
        assertEquals("car3", roster[2].id.value)
    }

    // --- the loud failure that replaces `when (car)` exhaustiveness ---

    @Test fun `resolving an id that is not in the roster fails rather than picking one`() {
        val roster = legacyRoster("RH950F", "XX123Y")
        assertNotNull(roster.require(wasil))
        val thrown = runCatching { roster.require(VehicleId("nobody")) }.exceptionOrNull()
        assertTrue("expected a loud failure, got $thrown", thrown is IllegalStateException)
    }
}
