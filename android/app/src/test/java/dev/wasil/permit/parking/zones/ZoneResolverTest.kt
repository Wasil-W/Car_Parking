package dev.wasil.permit.parking.zones

import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneResolverTest {
    private val home = FreeZone(52.3702, 4.8952, 60.0, "Home")
    // A free zone is a neighbourhood since v0.6.8, so it needs a boundary
    // rather than a radius. A square around (52.3800, 4.9000).
    private val workLotShape = listOf(
        ZonePolygon(outer = listOf(
            LatLng(52.3790, 4.8990), LatLng(52.3790, 4.9010),
            LatLng(52.3810, 4.9010), LatLng(52.3810, 4.8990),
        )),
    )
    private val shapes: (String) -> List<ZonePolygon>? =
        { name -> workLotShape.takeIf { name == "Work lot" } }
    private val manual = FreeZone(52.3800, 4.9000, 60.0, "Work lot", buurt = "Work lot")
    // Paid square covering everything from (52.0, 4.0) to (53.0, 5.0).
    private val paidArea = TariffArea(
        code = "T11V", name = "Centrum", tariffText = "€8,05/h",
        polygons = listOf(ZonePolygon(outer = listOf(
            LatLng(52.0, 4.0), LatLng(52.0, 5.0), LatLng(53.0, 5.0), LatLng(53.0, 4.0),
        ))),
    )
    private val resolver = ZoneResolver(home, listOf(manual), listOf(paidArea), shapes)

    @Test
    fun `home wins even inside a paid polygon`() {
        assertEquals(ZoneInfo.Home, resolver.resolve(GeoPoint(52.3702, 4.8952, 5f)))
    }

    @Test
    fun `manual free zone wins over paid`() {
        val zone = resolver.resolve(GeoPoint(52.3800, 4.9000, 5f))
        assertEquals(ZoneInfo.ManualFree("Work lot"), zone)
    }

    @Test
    fun `inside a tariff polygon is paid with the area attached`() {
        val zone = resolver.resolve(GeoPoint(52.5, 4.5, 5f)) as ZoneInfo.Paid
        assertEquals("T11V", zone.area?.code)
    }

    @Test
    fun `outside every polygon is free street parking`() {
        assertEquals(ZoneInfo.FreeStreet, resolver.resolve(GeoPoint(51.0, 4.5, 5f)))
    }

    @Test
    fun `no tariff data degrades to paid-unknown`() {
        val degraded = ZoneResolver(home, emptyList(), areas = null)
        val zone = degraded.resolve(GeoPoint(51.0, 4.5, 5f)) as ZoneInfo.Paid
        assertNull(zone.area)
        assertEquals(ZoneInfo.Home, degraded.resolve(GeoPoint(52.3702, 4.8952, 5f)))
    }

    @Test
    fun `no home configured never resolves home`() {
        val noHome = ZoneResolver(null, emptyList(), listOf(paidArea))
        assertTrue(noHome.resolve(GeoPoint(52.3702, 4.8952, 5f)) is ZoneInfo.Paid)
    }
}
