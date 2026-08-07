package dev.wasil.permit.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permit's own type, which the app had no concept of until now.
 *
 * It decides whether the 66 areas in `parkeerzones_uitzondering` are a hazard
 * or noise: those state *"uw parkeervergunning geldt niet van ma t/m za 9.00
 * tot 18.00 uur"* and they bind resident permits, not visitor ones.
 */
class PermitKindTest {

    @Test fun `the council's own words for a visitor permit are recognised`() {
        assertEquals(PermitKind.VISITOR, permitKindFor("Bezoekersvergunning"))
        assertEquals(PermitKind.VISITOR, permitKindFor("BEZOEKERSVERGUNNING CENTRUM"))
        assertEquals(PermitKind.VISITOR, permitKindFor("Visitor permit"))
    }

    @Test fun `and its words for a resident permit`() {
        assertEquals(PermitKind.RESIDENT, permitKindFor("Bewonersvergunning Oost"))
        assertEquals(PermitKind.RESIDENT, permitKindFor("Resident permit"))
    }

    /**
     * The whole of this feature's honesty. No captured `getClientProduct` body
     * exists, so the field this reads may simply not be there — and a name that
     * is absent, blank or in words nobody anticipated must not be turned into a
     * type. UNKNOWN is what "nobody has said" looks like.
     */
    @Test fun `anything else is unknown rather than assumed`() {
        assertEquals(PermitKind.UNKNOWN, permitKindFor(null))
        assertEquals(PermitKind.UNKNOWN, permitKindFor(""))
        assertEquals(PermitKind.UNKNOWN, permitKindFor("   "))
        assertEquals(PermitKind.UNKNOWN, permitKindFor("Parkeerproduct 5807976"))
    }

    /**
     * The rule the default exists for: "we do not know" must never render as
     * "you are covered". Being wrong in that direction is what costs a fine,
     * and it is the same rule `parkedOutsideKnown` already encodes for zones.
     */
    @Test fun `an unknown permit is treated as the restricted kind`() {
        assertTrue(PermitKind.UNKNOWN.boundByExceptionAreas)
        assertTrue(PermitKind.RESIDENT.boundByExceptionAreas)
        assertFalse(PermitKind.VISITOR.boundByExceptionAreas)
    }

    // --- what the response is actually read for ---

    @Test fun `a product response with no name parses, and says nothing about the type`() {
        val json = """{"vrns":[{"vrn":"RH950F","has_parking_session":true}]}"""
        val parsed = PermitJson.decodeFromString<ClientProductResponse>(json)
        assertEquals(null, parsed.name)
        assertEquals(PermitKind.UNKNOWN, permitKindFor(parsed.name))
    }

    /**
     * `name` is a guess about a key nobody has seen, and this is what makes the
     * guess free: an unknown key is ignored, so a wrong guess reads as null,
     * null is UNKNOWN, and UNKNOWN is the restricted kind. The app can be
     * uninformed here but never wrong in the expensive direction.
     */
    @Test fun `a response whose type sits under some other key stays unknown`() {
        val json = """
            {"vrns":[],"product_type":"bezoekersvergunning","permit":{"kind":"visitor"}}
        """.trimIndent()
        val parsed = PermitJson.decodeFromString<ClientProductResponse>(json)
        assertEquals(null, parsed.name)
        assertTrue(permitKindFor(parsed.name).boundByExceptionAreas)
    }

    @Test fun `a response that does carry a name under that key is read`() {
        val json = """{"name":"Bezoekersvergunning","vrns":[]}"""
        val parsed = PermitJson.decodeFromString<ClientProductResponse>(json)
        assertEquals(PermitKind.VISITOR, permitKindFor(parsed.name))
    }
}
