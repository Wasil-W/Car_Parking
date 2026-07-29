package dev.wasil.permit.parking.zones

import org.junit.Assert.assertEquals
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
        assertEquals("€1,72/h (stepped)", areas.first { it.code == "T14_UA01" }.tariffText)
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
