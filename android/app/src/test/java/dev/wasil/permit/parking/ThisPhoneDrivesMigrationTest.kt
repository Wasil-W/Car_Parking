package dev.wasil.permit.parking

import dev.wasil.permit.data.store.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The other half of the upgrade: which car this phone drives.
 *
 * `my_car=WASIL` is the most consequential string in an existing install. It
 * decides which Firebase node this phone writes to and reads from, which arc
 * the mark lights, and which plate a claim moves the permit onto. Losing it
 * would drop the pairing between two phones that are in daily use, and it would
 * do so quietly — the app would simply show the first-run question again and
 * then start writing to whichever node the user happened to re-pick.
 *
 * The migration is a `lowercase()`, and that is not a coincidence: the shared
 * room has always been keyed on `MyCar.key()`, which was `name.lowercase()`. So
 * the wire format never changes here; only the type on this side of it does.
 */
class ThisPhoneDrivesMigrationTest {

    private fun store(vararg entries: Pair<String, Any?>) =
        PrefsParkStateStore(FakeSharedPreferences(entries.toMap()))

    @Test fun `an install that stored WASIL keeps writing to the wasil node`() {
        assertEquals(VehicleId("wasil"), store("my_car" to "WASIL").thisPhoneDrives)
    }

    @Test fun `an install that stored WALID keeps writing to the walid node`() {
        assertEquals(VehicleId("walid"), store("my_car" to "WALID").thisPhoneDrives)
    }

    /** The migrated id resolves against a migrated roster, which is the point of both. */
    @Test fun `the migrated id names a car in the migrated roster`() {
        val id = store("my_car" to "WASIL").thisPhoneDrives
        val roster = legacyRoster("RH950F", "XX123Y")
        assertEquals("RH950F", roster.require(id).plate)
        assertEquals("Wasil", roster.require(id).name)
    }

    @Test fun `a fresh install has no answer yet, which is what shows the setup screen`() {
        assertNull(store().thisPhoneDrives)
        assertNull(store("my_car" to "").thisPhoneDrives)
    }

    /**
     * One source of truth after the first write. Leaving `my_car` behind would
     * let a later change of phone be silently undone by a stale key, since the
     * old value would still be sitting there for a fallback read to find.
     */
    @Test fun `writing the new key retires the old one`() {
        val prefs = FakeSharedPreferences(mapOf("my_car" to "WASIL"))
        val store = PrefsParkStateStore(prefs)
        store.thisPhoneDrives = VehicleId("walid")
        assertNull(prefs.getString("my_car", null))
        assertEquals("walid", prefs.getString("this_phone_drives", null))
        assertEquals(VehicleId("walid"), store.thisPhoneDrives)
    }

    @Test fun `the new key wins over a legacy one that has not been cleared`() {
        val store = store("my_car" to "WASIL", "this_phone_drives" to "walid")
        assertEquals(VehicleId("walid"), store.thisPhoneDrives)
    }

    /**
     * Nothing else in `park_state` is touched by this release, and it is worth
     * pinning: the parked pin, the home zone and the sync link are the rest of
     * what an upgrading phone would notice losing.
     */
    @Test fun `the rest of an existing park state is left exactly alone`() {
        val prefs = FakeSharedPreferences(
            mapOf(
                "my_car" to "WASIL",
                "car_mac" to "AA:BB:CC:DD:EE:FF",
                "car_name" to "Golf GTI",
                "parked" to true,
                "parked_outside" to true,
                "parked_at_ms" to 1_700_000_000_000L,
                "sync_url" to "https://example.firebasedatabase.app",
                "last_zone_code" to "T11V",
            ),
        )
        val store = PrefsParkStateStore(prefs)
        // Reading the migrated field must not disturb its neighbours.
        assertEquals(VehicleId("wasil"), store.thisPhoneDrives)
        assertEquals("AA:BB:CC:DD:EE:FF", store.carMac)
        assertEquals("Golf GTI", store.carName)
        assertEquals(true, store.parked)
        assertEquals(true, store.parkedOutside)
        assertEquals(1_700_000_000_000L, store.parkedAtMs)
        assertEquals("https://example.firebasedatabase.app", store.syncUrl)
        assertEquals("T11V", store.lastZoneCode)
    }
}
