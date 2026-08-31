package dev.wasil.permit.parking.facilities

import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.distanceMeters
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FacilityRegistryTest {

    private fun bundled(): FacilityRegistry {
        val asset = File("src/main/assets/amsterdam_facilities.json")
        assertTrue("bundled asset missing at ${asset.absolutePath}", asset.exists())
        return FacilityRegistry.parse(asset.readText())
            ?: error("bundled asset failed to parse")
    }

    /**
     * These totals pin a bundled snapshot, so they are *expected* to fail the
     * day the asset is legitimately refreshed. The message has to say so —
     * otherwise the next person meets "expected:<17> but was:<18>" and starts
     * looking for a bug in the parser instead of updating a constant.
     */
    private val REFRESHED =
        "the bundled asset has changed. If amsterdam_facilities.json was just " +
            "regenerated this is expected — update the constant. If it was not, " +
            "something is dropping entries at parse time."

    @Test
    fun `the bundled asset parses to 37 facilities`() {
        val r = bundled()
        assertEquals(REFRESHED, 37, r.facilities.size)
        assertEquals(REFRESHED, 26, r.facilities.count { it.kind == FacilityKind.GARAGE })
        assertEquals(REFRESHED, 11, r.facilities.count { it.kind == FacilityKind.PARK_AND_RIDE })
        // Relational, so it holds through any refresh: every facility is one of
        // the two kinds and nothing is silently dropped between them.
        assertEquals(
            "garages + P+R must account for every facility",
            r.facilities.size,
            r.facilities.count { it.kind == FacilityKind.GARAGE } +
                r.facilities.count { it.kind == FacilityKind.PARK_AND_RIDE },
        )
    }

    /**
     * The whole reason only 37 ship. A facility with no position cannot be
     * drawn, and geocoding a name to invent one put "P+R RAI" 11 km away
     * during this release's research.
     */
    @Test
    fun `every facility carries a position inside Amsterdam`() {
        bundled().facilities.forEach {
            assertTrue("${it.name} lat ${it.lat}", it.lat in 52.20..52.45)
            assertTrue("${it.name} lng ${it.lng}", it.lng in 4.70..5.10)
        }
    }

    @Test
    fun `rates are present for some and absent for others, and absence is representable`() {
        val r = bundled()
        val withRates = r.facilities.filter { it.rates.isNotEmpty() }
        val without = r.facilities.filter { it.rates.isEmpty() }
        assertEquals(REFRESHED, 17, withRates.size)
        assertEquals(REFRESHED, 20, without.size)
        // Relational: the split must account for everything, whatever the totals
        // become. This half survives a refresh; the two above do not.
        assertEquals(r.facilities.size, withRates.size + without.size)
        // Absence must be an empty list rather than a fabricated line.
        without.forEach { assertNull("${it.name} should have no timestamp", it.ratesUpdated) }
        withRates.forEach { assertNotNull("${it.name} should have a timestamp", it.ratesUpdated) }
    }

    /**
     * Quoted, never computed. If this string ever stops matching the asset,
     * someone has started paraphrasing a published price.
     */
    @Test
    fun `rate lines are the operator's own wording, verbatim`() {
        val sloterdijk = bundled().facilities.single { it.name == "P+R Sloterdijk" }
        assertEquals(FacilityKind.PARK_AND_RIDE, sloterdijk.kind)
        assertTrue(
            sloterdijk.rates.any { it.text == "1,00 per 24u, max 4 dg. Daarna reg. tarief" },
        )
        // The weekday morning surcharge does not apply at the weekend.
        val weekdayOnly = sloterdijk.rates.single { it.days.isNotEmpty() }
        assertEquals(setOf(1, 2, 3, 4, 5), weekdayOnly.days)
        assertTrue(weekdayOnly.appliesOn(1))
        assertFalse(weekdayOnly.appliesOn(6))
    }

    @Test
    fun `a line with no days applies every day`() {
        val line = RateLine("Dagkaart 30,00")
        (1..7).forEach { assertTrue(line.appliesOn(it)) }
    }

    @Test
    fun `nearest respects the cutoff`() {
        val r = bundled()
        // Waterlooplein, next to the Stadhuis garage.
        val near = r.nearest(52.36760, 4.90180, withinMetres = 600.0)
        assertNotNull(near)
        // Middle of the North Sea — nothing within 600 m.
        assertNull(r.nearest(52.40, 3.50, withinMetres = 600.0))
    }

    @Test
    fun `near returns nearest first`() {
        val list = bundled().near(52.36760, 4.90180, metres = 4000.0)
        assertTrue(list.size >= 2)
        val from = GeoPoint(52.36760, 4.90180, 0f)
        val d = list.map { distanceMeters(from, GeoPoint(it.lat, it.lng, 0f)) }
        assertEquals(d.sorted(), d)
    }

    /**
     * The class KDoc promises the shipped facilities are ones the council
     * positioned. Until v0.7.6 nothing enforced that, so an asset with lat and
     * lng transposed parsed cleanly and put 37 plates in the Indian Ocean.
     */
    @Test
    fun `a facility outside Amsterdam is dropped rather than drawn`() {
        val swapped = """{"facilities":[{"n":"Swapped","k":0,"y":4.89,"x":52.37}]}"""
        assertNull("lat/lng transposed must not parse", FacilityRegistry.parse(swapped))

        val nullIsland = """{"facilities":[{"n":"Nowhere","k":0,"y":0.0,"x":0.0}]}"""
        assertNull("0,0 must not parse", FacilityRegistry.parse(nullIsland))

        // One good entry survives alongside one bad one, rather than the whole
        // asset being discarded for a single corrupt row.
        val mixed = """
            {"facilities":[
              {"n":"Real","k":0,"y":52.37,"x":4.89},
              {"n":"Bad","k":0,"y":12.34,"x":56.78}
            ]}
        """.trimIndent()
        val r = FacilityRegistry.parse(mixed)
        assertNotNull(r)
        assertEquals(1, r!!.facilities.size)
        assertEquals("Real", r.facilities.single().name)
    }

    /**
     * `weekdayRange` indexes a seven-element list, so an out-of-range day was
     * IndexOutOfBoundsException thrown from inside the bottom sheet — a crash
     * on tap rather than a rejection at load.
     */
    @Test
    fun `a rate line with unusable weekdays is dropped, not widened to every day`() {
        val bad = """
            {"facilities":[{"n":"X","k":0,"y":52.37,"x":4.89,
              "t":[{"r":"1,00 per uur","d":[0,8,99]}]}]}
        """.trimIndent()
        val r = FacilityRegistry.parse(bad)
        assertNotNull(r)
        // Dropped entirely: an empty day set means "every day" on this type, so
        // keeping the line would widen the operator's claim rather than narrow it.
        assertTrue(r!!.facilities.single().rates.isEmpty())

        // Out-of-range values mixed with good ones keep only the good ones.
        val partial = """
            {"facilities":[{"n":"X","k":0,"y":52.37,"x":4.89,
              "t":[{"r":"1,00 per uur","d":[1,2,9]}]}]}
        """.trimIndent()
        val line = FacilityRegistry.parse(partial)!!.facilities.single().rates.single()
        assertEquals(setOf(1, 2), line.days)
    }

    @Test
    fun `garbage parses to null rather than an empty registry`() {
        assertNull(FacilityRegistry.parse("not json"))
        assertNull(FacilityRegistry.parse("""{"facilities":[]}"""))
        // A facility missing its position is dropped, not defaulted to 0,0.
        val one = FacilityRegistry.parse("""{"facilities":[{"n":"X","k":0}]}""")
        assertNull(one)
    }

    @Test
    fun `distance is roughly right`() {
        // Waterlooplein to Sloterdijk is about 5.5 km. Measured through the
        // app's one haversine — this file used to call a second copy that
        // lived in Facility.kt until v0.7.6 removed it.
        val d = distanceMeters(
            GeoPoint(52.36760, 4.90180, 0f),
            GeoPoint(52.39001, 4.83842, 0f),
        )
        assertTrue("was $d", d in 4500.0..6500.0)
    }
}
