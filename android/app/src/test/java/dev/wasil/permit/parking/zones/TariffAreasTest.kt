package dev.wasil.permit.parking.zones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TariffAreasTest {
    private val fixture = """
    {
      "T11V": {
        "description": {"0": "Basistarief TC1 ma-zo 00-24"},
        "location": {
          "type": "Polygon",
          "coordinates": [[[4.0, 52.0], [5.0, 52.0], [5.0, 53.0], [4.0, 53.0], [4.0, 52.0]]]
        },
        "tarieven": [{"8,05": {"0-2400": "ma-zo"}}]
      },
      "T14_UA01": {
        "description": {"0": "Tarief 4 start tarief 7"},
        "location": {
          "type": "MultiPolygon",
          "coordinates": [
            [[[4.0, 50.0], [4.1, 50.0], [4.1, 50.1], [4.0, 50.1], [4.0, 50.0]]],
            [[[5.0, 51.0], [5.1, 51.0], [5.1, 51.1], [5.0, 51.1], [5.0, 51.0]]]
          ]
        },
        "tarieven": [{"1,72[0-180];4,19[180-999999]": {"900-2100": "ma-za"}}]
      },
      "BROKEN": {"description": {"0": "no geometry"}}
    }
    """

    @Test
    fun `parses codes names and polygons with lng-lat swapped to lat-lng`() {
        val areas = TariffAreas.parse(fixture)
        val t11 = areas.first { it.code == "T11V" }
        assertEquals("Basistarief TC1 ma-zo 00-24", t11.name)
        assertEquals(1, t11.polygons.size)
        assertEquals(LatLng(52.0, 4.0), t11.polygons[0].outer.first())
    }

    @Test
    fun `multipolygon becomes several polygons`() {
        val areas = TariffAreas.parse(fixture)
        assertEquals(2, areas.first { it.code == "T14_UA01" }.polygons.size)
    }

    @Test
    fun `tariff text formats plain and stepped prices`() {
        val areas = TariffAreas.parse(fixture)
        assertEquals("€8,05/h", areas.first { it.code == "T11V" }.tariffText)
        // Was "€1,72/h (stepped)" — a warning that a price changes, without
        // saying what it changes to, which is a worry rather than an answer.
        assertEquals(
            "from €1,72/h · First 3 h €1,72/h, then €4,19/h",
            areas.first { it.code == "T14_UA01" }.tariffText,
        )
    }

    /**
     * The number is read from the same JSON key as the label, so the two can
     * never drift apart — €8,05 shown and 805 compared.
     */
    @Test
    fun `windows carry the rate as a number alongside its label`() {
        val areas = TariffAreas.parse(fixture)
        val t11 = areas.first { it.code == "T11V" }.windows.single()
        assertEquals("€8,05/h", t11.rateText)
    }

    /**
     * The defect this replaces: everything after `[` was discarded, so an area
     * that doubles its rate after three hours advertised the opening tier as
     * though it were flat. Understating a price is the direction that costs
     * money, which is why the label now says "from".
     */
    @Test
    fun `a stepped rate says it is a floor, not a price`() {
        val stepped = TariffAreas.parse(fixture).first { it.code == "T14_UA01" }.windows.single()
        assertEquals("from €1,72/h", stepped.rateText)
        assertEquals("First 3 h €1,72/h, then €4,19/h", stepped.stepNote)
    }

    @Test
    fun `two tiers at the same price are flat and get no note`() {
        // T17F is `1,72[0-180];1,72[180-999999]` — bracketed, and yet nothing
        // changes. A note saying the price stays the same is noise.
        val flat = TariffAreas.rateFor("1,72[0-180];1,72[180-999999]")
        assertEquals("€1,72/h", flat.label)
        assertNull(flat.stepNote)
    }

    @Test
    fun `the cheap opening tier is the one that misled hardest`() {
        // Nine hours at T17_UB01 read as ninety cents; it is nearer €10,60.
        val priced = TariffAreas.rateFor("0,10[0-180];1,72[180-999999]")
        assertEquals("from €0,10/h", priced.label)
        assertEquals("First 3 h €0,10/h, then €1,72/h", priced.stepNote)
    }

    @Test
    fun `an unbracketed rate is untouched`() {
        assertEquals("€8,05/h", TariffAreas.rateFor("8,05").label)
        assertNull(TariffAreas.rateFor("8,05").stepNote)
    }

    @Test
    fun `every stepped area in the real data is named, and only those`() {
        val areas = TariffAreas.parse(
            java.io.File("src/main/assets/amsterdam_tarieven.json").readText(),
        )
        val stepped = areas
            .filter { area -> area.windows.any { it.stepNote != null } }
            .map { it.code }
            .toSet()
        assertEquals(setOf("T14_UA01", "T17_UB01"), stepped)
    }

    @Test
    fun `malformed records are skipped not fatal`() {
        val areas = TariffAreas.parse(fixture)
        assertEquals(2, areas.size)
        assertTrue(areas.none { it.code == "BROKEN" })
    }

    @Test
    fun `garbage input returns empty list`() {
        assertEquals(emptyList<TariffArea>(), TariffAreas.parse("not json"))
    }

    @Test
    fun `bundled amsterdam asset parses to 29 areas`() {
        val json = java.io.File("src/main/assets/amsterdam_tarieven.json").readText()
        val areas = TariffAreas.parse(json)
        assertEquals(29, areas.size)
        assertTrue(areas.all { it.polygons.isNotEmpty() })
        assertTrue(areas.all { area -> area.polygons.all { it.outer.size >= 3 } })
    }
}
