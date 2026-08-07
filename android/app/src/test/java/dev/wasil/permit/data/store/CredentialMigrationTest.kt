package dev.wasil.permit.data.store

import dev.wasil.permit.data.api.PermitKind
import dev.wasil.permit.parking.Roster
import dev.wasil.permit.parking.Vehicle
import dev.wasil.permit.parking.VehicleId
import dev.wasil.permit.parking.slotIdFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Upgrading a phone that already has the app on it.
 *
 * Two people are running v0.6.6 with live shared state between them. If this
 * upgrade drops their pairing or their permit, that is a real loss on a real
 * phone, and it is the sort of loss that shows up as "the app forgot
 * everything" rather than as a crash — so it is tested against a store that
 * starts in the **old** shape rather than against one this code wrote.
 */
class CredentialMigrationTest {

    private val wasil = slotIdFor(0)
    private val walid = slotIdFor(1)

    /** Exactly what v0.6.6 left in `permit_credentials`. */
    private fun v066Prefs() = FakeSharedPreferences(
        mapOf(
            "username" to "wasil@example.com",
            "password" to "hunter2",
            "wasil_plate" to "RH950F",
            "walid_plate" to "XX123Y",
        ),
    )

    @Test fun `an old install's two plates become a two-car roster`() {
        val config = EncryptedCredentialStore.loadFrom(v066Prefs())
        assertNotNull(config)
        assertEquals("wasil@example.com", config!!.username)
        assertEquals("hunter2", config.password)
        assertEquals(listOf("RH950F", "XX123Y"), config.roster.vehicles.map { it.plate })
    }

    /**
     * The pairing, and the whole reason ids are these two strings. Both phones'
     * state lives at `/rooms/<hash>/wasil` and `/rooms/<hash>/walid`, written
     * under `MyCar.key()` = `name.lowercase()`. If migration produced any other
     * id, each phone would start writing to a node the other never reads and
     * the collision guard would silently stop guarding.
     */
    @Test fun `the migrated ids are the Firebase node names already in use`() {
        val roster = EncryptedCredentialStore.loadFrom(v066Prefs())!!.roster
        assertEquals(listOf("wasil", "walid"), roster.vehicles.map { it.id.value })
    }

    /**
     * Slot order is identity order. Sorting the two plates on the way in would
     * have swapped the brothers' colours on any account whose plates happen to
     * sort the other way — a visible change, for nothing.
     */
    @Test fun `slot order is taken from the stored layout, not from sorting`() {
        val prefs = FakeSharedPreferences(
            mapOf(
                "username" to "u", "password" to "p",
                // Deliberately against alphabetical order.
                "wasil_plate" to "XX123Y", "walid_plate" to "RH950F",
            ),
        )
        val roster = EncryptedCredentialStore.loadFrom(prefs)!!.roster
        assertEquals("XX123Y", roster.require(wasil).plate)
        assertEquals("RH950F", roster.require(walid).plate)
    }

    @Test fun `the two seeded names survive, so nothing on screen moves`() {
        val roster = EncryptedCredentialStore.loadFrom(v066Prefs())!!.roster
        assertEquals(listOf("Wasil", "Walid"), roster.vehicles.map { it.name })
    }

    /** Nothing has read the account yet, so the permit's type is not known. */
    @Test fun `a migrated permit has no known type, and unknown is the safe kind`() {
        assertEquals(PermitKind.UNKNOWN, EncryptedCredentialStore.loadFrom(v066Prefs())!!.permitKind)
    }

    // --- what the new shape stores, and how the two coexist ---

    @Test fun `saving replaces the legacy keys rather than leaving a second answer`() {
        val prefs = v066Prefs()
        val roster = Roster.of(
            listOf(Vehicle(wasil, "AA111A", "Wasil"), Vehicle(walid, "BB222B", "Walid")),
        )
        EncryptedCredentialStore.saveTo(prefs,PermitConfig("u", "p", roster, PermitKind.VISITOR))

        assertNull(prefs.getString("wasil_plate", null))
        assertNull(prefs.getString("walid_plate", null))
        val reread = EncryptedCredentialStore.loadFrom(prefs)!!
        assertEquals(listOf("AA111A", "BB222B"), reread.roster.vehicles.map { it.plate })
        assertEquals(PermitKind.VISITOR, reread.permitKind)
    }

    @Test fun `a roster survives the round trip with ids and names intact`() {
        val prefs = FakeSharedPreferences()
        val roster = Roster.of(
            listOf(
                Vehicle(wasil, "AA111A", "Wasil"),
                Vehicle(walid, "BB222B", "Walid"),
                Vehicle(VehicleId("car3"), "CC333C", "CC333C"),
            ),
        )
        EncryptedCredentialStore.saveTo(prefs,PermitConfig("u", "p", roster))
        assertEquals(roster.vehicles, EncryptedCredentialStore.loadFrom(prefs)!!.roster.vehicles)
    }

    // --- the ways a read can come back with nothing ---

    @Test fun `no credentials at all reads as no permit`() {
        assertNull(EncryptedCredentialStore.loadFrom(FakeSharedPreferences()))
    }

    /**
     * An install that removed its permit and never added another has a username
     * but nothing else. Reading that as a permit with an empty roster would put
     * a hand-over button on screen pointing at no plate.
     */
    @Test fun `credentials with neither roster nor legacy plates read as no permit`() {
        val prefs = FakeSharedPreferences(mapOf("username" to "u", "password" to "p"))
        assertNull(EncryptedCredentialStore.loadFrom(prefs))
    }

    /**
     * A half-written or corrupt roster is "no permit", not "a permit with cars
     * we guessed". No permit shows the obligation half and moves nothing; a
     * guessed roster could move the permit onto a plate nobody chose.
     */
    @Test fun `an unreadable roster reads as no permit rather than as a guess`() {
        val prefs = FakeSharedPreferences(
            mapOf("username" to "u", "password" to "p", "roster" to "{ not json"),
        )
        assertNull(EncryptedCredentialStore.loadFrom(prefs))
    }

    @Test fun `an unrecognised permit kind falls back to unknown rather than throwing`() {
        val prefs = v066Prefs()
        prefs.values["permit_kind"] = "SOMETHING_ELSE"
        assertEquals(PermitKind.UNKNOWN, EncryptedCredentialStore.loadFrom(prefs)!!.permitKind)
    }
}
