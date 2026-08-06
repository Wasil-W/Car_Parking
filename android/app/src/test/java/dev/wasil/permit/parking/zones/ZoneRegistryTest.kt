package dev.wasil.permit.parking.zones

import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneRegistryTest {

    private val bundled: ZoneRegistry by lazy {
        ZoneRegistry.parse(java.io.File("src/main/assets/amsterdam_zones.json").readText())!!
    }

    // The three points this file argues from. Waterlooplein is the one the
    // brief names; the other two are the extremes — a spot the old geocoder
    // could only call "Amsterdam-Noord", and a city the bundle does not cover.
    private val waterlooplein = LatLng(52.3676, 4.9041)
    private val ndsm = LatLng(52.4014, 4.8925)
    private val utrecht = LatLng(52.0907, 5.1214)

    @Test
    fun `bundled asset holds all 107 permit zones and 518 neighbourhoods`() {
        assertEquals(107, bundled.zones.size)
        assertEquals(518, bundled.neighbourhoods.size)
        assertTrue(bundled.zones.all { it.code.isNotBlank() && it.name.isNotBlank() })
        assertTrue(bundled.zones.all { z -> z.polygons.all { it.outer.size >= 3 } })
        assertTrue(bundled.neighbourhoods.all { it.name.isNotBlank() && it.district.isNotBlank() })
        assertTrue(bundled.neighbourhoods.all { n -> n.polygons.all { it.outer.size >= 3 } })
    }

    /**
     * The point the whole release is judged on. "Waterloopleinbuurt" is a name
     * a person can act on; "T13B", the tariff region covering the same spot, is
     * 3 km across and named after a rate class.
     */
    @Test
    fun `Waterlooplein resolves to its own neighbourhood and permit zone`() {
        val place = bundled.resolve(waterlooplein)!!
        assertEquals("Waterloopleinbuurt", place.neighbourhood)
        assertEquals("Centrum", place.district)
        assertEquals("CE01", place.zoneCode)
        assertEquals("Centrum 1", place.zoneName)
    }

    @Test
    fun `NDSM resolves to the name on the council's map`() {
        val place = bundled.resolve(ndsm)!!
        assertEquals("NDSM terrein", place.neighbourhood)
        assertEquals("Noord", place.district)
        assertEquals("AN05C", place.zoneCode)
    }

    /** Outside Amsterdam is "we do not know", not a nearest guess. */
    @Test
    fun `a point outside Amsterdam resolves to nothing at all`() {
        assertNull(bundled.resolve(utrecht))
    }

    /**
     * The constraint this release is under, asserted rather than promised: the
     * registry names a place, it does not decide the claim. Waterlooplein is a
     * zone and a neighbourhood to the registry and still free street parking to
     * [ZoneResolver] when no tariff area covers it — because the tariff
     * polygons are the only thing that decision reads.
     */
    @Test
    fun `naming a spot never changes whether the permit is claimed`() {
        assertNotNull(bundled.resolve(waterlooplein))
        val resolver = ZoneResolver(home = null, manualZones = emptyList(), areas = emptyList())
        val point = GeoPoint(waterlooplein.lat, waterlooplein.lng, 5f)
        assertEquals(ZoneInfo.FreeStreet, resolver.resolve(point))
    }

    /** And the same in the other direction: a home zone still outranks everything. */
    @Test
    fun `a home zone inside a named neighbourhood is still home`() {
        val home = FreeZone(waterlooplein.lat, waterlooplein.lng, 60.0, "Home")
        val resolver = ZoneResolver(home, emptyList(), emptyList())
        val point = GeoPoint(waterlooplein.lat, waterlooplein.lng, 5f)
        assertEquals(ZoneInfo.Home, resolver.resolve(point))
    }

    /**
     * Every neighbourhood sits under one of the nine stadsdelen, so the header
     * can always show two levels rather than sometimes one.
     */
    @Test
    fun `every neighbourhood carries a district`() {
        val districts = bundled.neighbourhoods.map { it.district }.toSet()
        assertEquals(
            setOf(
                "Centrum", "Nieuw-West", "Noord", "Oost", "Weesp",
                "West", "Westpoort", "Zuid", "Zuidoost",
            ),
            districts,
        )
    }

    @Test
    fun `a lookup is fast enough to run while the map is drawing`() {
        bundled.resolve(waterlooplein)
        val started = System.nanoTime()
        repeat(20) { bundled.resolve(waterlooplein) }
        val perCallMs = (System.nanoTime() - started) / 20 / 1_000_000.0
        assertTrue("$perCallMs ms per lookup", perCallMs < 50.0)
    }

    @Test
    fun `garbage input parses to null rather than an empty registry`() {
        assertNull(ZoneRegistry.parse("not json"))
        assertNull(ZoneRegistry.parse("""{"districts":[],"zones":[],"buurten":[]}"""))
    }

    @Test
    fun `an unreadable entry is dropped rather than taking the file with it`() {
        val registry = ZoneRegistry.parse(
            """
            {
              "districts": ["Centrum"],
              "zones": [
                {"c": "CE01", "n": "Centrum 1", "g": [["_p~iF~ps|U_ulLnnqC_mqNvxq`@"]]},
                {"n": "no code", "g": [["_p~iF~ps|U"]]}
              ],
              "buurten": [
                {"n": "Waterloopleinbuurt", "d": 0, "g": [["_p~iF~ps|U_ulLnnqC_mqNvxq`@"]]},
                {"n": "no district", "d": 9, "g": [["_p~iF~ps|U_ulLnnqC_mqNvxq`@"]]}
              ]
            }
            """,
        )!!
        assertEquals(listOf("CE01"), registry.zones.map { it.code })
        assertEquals(listOf("Waterloopleinbuurt"), registry.neighbourhoods.map { it.name })
    }

    /** The textbook vector from Google's own polyline documentation. */
    @Test
    fun `polyline decoding matches the reference vector`() {
        val points = decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(
            listOf(
                LatLng(38.5, -120.2),
                LatLng(40.7, -120.95),
                LatLng(43.252, -126.453),
            ),
            points.map { LatLng(round5(it.lat), round5(it.lng)) },
        )
    }

    private fun round5(v: Double) = Math.round(v * 1e5) / 1e5
}
