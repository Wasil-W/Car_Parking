package dev.wasil.permit.parking.route

import dev.wasil.permit.parking.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkRouteTest {

    private val ok = """
    {
      "code": "Ok",
      "routes": [{
        "distance": 320.4,
        "duration": 245.0,
        "geometry": {
          "type": "LineString",
          "coordinates": [[4.9041, 52.3676], [4.9050, 52.3680], [4.9061, 52.3688]]
        }
      }]
    }
    """

    @Test
    fun `the request puts longitude first, as OSRM expects`() {
        val url = OsrmRoute.url(GeoPoint(52.3676, 4.9041, 0f), GeoPoint(52.3702, 4.8952, 0f))
        assertTrue(url.contains("4.9041,52.3676;4.8952,52.3702"))
        assertTrue(url.contains("/foot/"))
    }

    @Test
    fun `a route parses to points in lat-lng order`() {
        val route = OsrmRoute.parse(ok)!!
        assertEquals(3, route.points.size)
        assertEquals(52.3676, route.points.first().lat, 1e-6)
        assertEquals(4.9041, route.points.first().lng, 1e-6)
        assertEquals(320.4, route.distanceM, 0.01)
    }

    @Test
    fun `a routing failure is null rather than an empty route`() {
        assertNull(OsrmRoute.parse("""{"code":"NoRoute","routes":[]}"""))
    }

    @Test
    fun `junk is null rather than a crash`() {
        assertNull(OsrmRoute.parse("not json at all"))
        assertNull(OsrmRoute.parse("{}"))
    }

    @Test
    fun `a single-point line is not a route`() {
        assertNull(
            OsrmRoute.parse(
                """{"code":"Ok","routes":[{"distance":0,"duration":0,
                   "geometry":{"coordinates":[[4.9,52.3]]}}]}""",
            ),
        )
    }

    @Test
    fun `the summary reads as minutes and distance`() {
        assertEquals("4 min · 320 m", walkSummary(WalkRoute(emptyList(), 320.4, 245.0)))
        // 320 m at 5 km/h is 4 minutes whatever the service claims.
        // Built with the same locale the app formats in: a Dutch phone
        // correctly says "1,4 km", and hard-coding a full stop would fail
        // there while passing in CI.
        // A straight-line fallback carries no duration from any service, and
        // still gets a time — the pace assumption is ours either way.
        val expected = "17 min · " + String.format(java.util.Locale.getDefault(), "%.1f km", 1.44)
        assertEquals(expected, walkSummary(WalkRoute(emptyList(), 1440.0, 0.0)))
    }

    @Test
    fun `a walk under a minute still reads as one, never zero`() {
        assertEquals("1 min · 20 m", walkSummary(WalkRoute(emptyList(), 20.0, 15.0)))
    }

    @Test
    fun `a driving duration from the service is ignored`() {
        // The public OSRM demo does not reliably serve the foot profile and
        // answered a 984 m walk with 128 s — 30 km/h. Walking time comes from
        // the distance, never from the response.
        assertEquals("12 min · 984 m", walkSummary(WalkRoute(emptyList(), 984.0, 128.0)))
    }
}
