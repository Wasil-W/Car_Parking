package dev.wasil.permit.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkSignalsTest {
    @Test
    fun `distance between identical points is zero`() {
        val p = GeoPoint(52.3702, 4.8952, 10f)
        assertEquals(0.0, distanceMeters(p, p), 0.001)
    }

    @Test
    fun `distance of roughly 111m per thousandth of latitude`() {
        val a = GeoPoint(52.3702, 4.8952, 10f)
        val b = GeoPoint(52.3712, 4.8952, 10f)
        assertEquals(111.2, distanceMeters(a, b), 2.0)
    }

    @Test
    fun `ten meter walk is measurable`() {
        val a = GeoPoint(52.370200, 4.895200, 5f)
        val b = GeoPoint(52.370290, 4.895200, 5f) // ~10 m north
        assertTrue(distanceMeters(a, b) in 8.0..12.0)
    }

    // --- cached-fix fallback (v0.4.2) ---
    // Reported from real use 2026-08-01: parking at home produced no car pin
    // and a pointless "claim?" prompt, because the fresh-fix request fails
    // indoors and nothing stood in for it.

    private val fix = GeoPoint(52.3702, 4.8952, 12f)

    @Test
    fun `a seconds-old cached fix stands in for a fresh one`() {
        assertEquals(fix, cachedFixIfFresh(fix, ageMs = 5_000))
    }

    @Test
    fun `a fix from earlier in the drive is rejected`() {
        // The dangerous direction: an old fix from home would resolve to the
        // home zone and quietly decide no permit is needed, in town.
        assertNull(cachedFixIfFresh(fix, ageMs = 10 * 60_000))
    }

    @Test
    fun `the boundary is inclusive`() {
        assertEquals(fix, cachedFixIfFresh(fix, ageMs = CACHED_FIX_MAX_AGE_MS))
        assertNull(cachedFixIfFresh(fix, ageMs = CACHED_FIX_MAX_AGE_MS + 1))
    }

    @Test
    fun `no cached fix at all stays null`() {
        assertNull(cachedFixIfFresh(null, ageMs = 0))
    }

    @Test
    fun `a negative age is rejected rather than read as brand new`() {
        assertNull(cachedFixIfFresh(fix, ageMs = -1))
    }
}
