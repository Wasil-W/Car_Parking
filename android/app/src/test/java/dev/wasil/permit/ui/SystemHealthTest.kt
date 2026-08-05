package dev.wasil.permit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemHealthTest {

    /** The healthy baseline, so each test below varies exactly one thing. */
    private fun rows(
        missingPermissions: Int = 0,
        batteryOptimised: Boolean = false,
        carPaired: Boolean = true,
        permitAdded: Boolean = true,
        syncConfigured: Boolean = true,
        homeZoneSet: Boolean = true,
        backgroundLocation: BackgroundLocation = BackgroundLocation.GRANTED,
    ) = healthRows(
        missingPermissions, batteryOptimised, carPaired, permitAdded,
        syncConfigured, homeZoneSet, backgroundLocation,
    )

    /**
     * "Permissions all granted" is gone on purpose. It counted four in-app
     * permissions and was read as covering every permission the app has —
     * including the background-location one it knew nothing about.
     */
    @Test fun `everything fine gives two ok rows that do not overclaim`() {
        val rows = healthRows(missingPermissions = 0, batteryOptimised = false)
        assertEquals(listOf(true, true), rows.map { it.ok })
        assertEquals(listOf(null, null), rows.map { it.fixLabel })
        assertEquals("Permissions granted in the app", rows[0].label)
        assertFalse(rows[0].label.contains("all"))
    }

    @Test fun `missing permissions are counted and offer a fix`() {
        val rows = healthRows(missingPermissions = 3, batteryOptimised = false)
        assertEquals("3 permissions missing", rows[0].label)
        assertEquals(false, rows[0].ok)
        assertEquals("Grant", rows[0].fixLabel)
    }

    @Test fun `a single missing permission reads in the singular`() {
        assertEquals("1 permission missing", healthRows(1, false)[0].label)
    }

    @Test fun `battery optimisation on is a warning with a fix`() {
        val row = healthRows(missingPermissions = 0, batteryOptimised = true)[1]
        assertEquals("Battery optimisation on", row.label)
        assertEquals(false, row.ok)
        assertEquals("Fix", row.fixLabel)
    }

    @Test fun `everything set gives seven ok rows`() {
        val all = rows()
        assertEquals(7, all.size)
        assertTrue(all.all { it.ok })
        assertEquals("Location allowed all the time", all[2].label)
        assertEquals("Car paired", all[3].label)
        assertEquals("Permit added", all[4].label)
        assertEquals("Sharing with the other phone", all[5].label)
        assertEquals("Home zone set", all[6].label)
    }

    @Test fun `no car paired is not ok — detection cannot run without it`() {
        val row = rows(carPaired = false)[3]
        assertEquals("No car paired — detection won't run", row.label)
        assertEquals(false, row.ok)
    }

    @Test fun `no permit reads as a configuration, not a fault`() {
        val row = rows(permitAdded = false)[4]
        assertEquals("No permit — rates and hours only", row.label)
        assertEquals(true, row.ok)
        assertEquals(null, row.fixLabel)
    }

    @Test fun `sharing off reads as informational, not a fault`() {
        val row = rows(syncConfigured = false)[5]
        assertEquals("Sharing off — this phone only", row.label)
        assertEquals(true, row.ok)
        assertEquals(null, row.fixLabel)
    }

    @Test fun `no home zone reads as informational, not a fault`() {
        val row = rows(homeZoneSet = false)[6]
        assertEquals("No home zone set", row.label)
        assertEquals(true, row.ok)
        assertEquals(null, row.fixLabel)
    }

    @Test fun `several missing — only the genuine faults are not ok`() {
        val all = rows(
            missingPermissions = 2, batteryOptimised = true, carPaired = false,
            permitAdded = false, syncConfigured = false, homeZoneSet = false,
        )
        assertEquals(listOf(false, false, true, false, true, true, true), all.map { it.ok })
    }

    /**
     * The whole point of the copy pass, pinned: one car, no permit, no sharing,
     * no home zone is a **working** install and the app must not call it broken.
     */
    @Test fun `a one-car no-permit no-sharing install is fully set up`() {
        val all = rows(permitAdded = false, syncConfigured = false, homeZoneSet = false)
        assertTrue(all.all { it.ok })
        assertEquals("Everything is set up", setupHeadline(all))
    }

    @Test fun `the headline counts faults rather than declaring setup incomplete`() {
        assertEquals("1 thing needs attention", setupHeadline(rows(missingPermissions = 1)))
        assertEquals(
            "3 things need attention",
            setupHeadline(rows(missingPermissions = 1, batteryOptimised = true, carPaired = false)),
        )
    }

    @Test fun `the configuration line states facts, never complaints`() {
        assertEquals(
            "Wasil's phone · permit added · sharing on",
            setupConfigurationLine("Wasil", permitAdded = true, syncConfigured = true),
        )
        assertEquals(
            "Whose phone isn't set yet · no permit · sharing off",
            setupConfigurationLine(null, permitAdded = false, syncConfigured = false),
        )
    }

    // --- background location: the permission that was declared and never asked for ---

    /**
     * Without it, every position the app reads comes back null, because every
     * one of them is read from a background worker. It is a fault, it says what
     * that costs rather than naming the permission, and it can never be
     * silently absent from the count again.
     */
    @Test fun `missing background location is a fault that states its consequence`() {
        val row = rows(backgroundLocation = BackgroundLocation.MISSING)[2]
        assertEquals("Parks are only noticed while Handoff is open", row.label)
        assertFalse(row.ok)
        assertEquals("Fix", row.fixLabel)
        assertNotNull(row.hint)
        assertTrue(row.hint!!.contains("Allow all the time"))
    }

    /**
     * It must open app settings, not the ordinary permission dialog. On API 30+
     * a `requestPermissions` call for it is denied without showing anything, so
     * a Grant button here would look like it worked and do nothing.
     */
    @Test fun `its fix goes to app settings, never to the permission dialog`() {
        assertEquals(
            FixAction.AppSettings,
            rows(backgroundLocation = BackgroundLocation.MISSING)[2].fix,
        )
    }

    /** The regression that matters: setup can never read as complete without it. */
    @Test fun `setup is not ok while background location is missing`() {
        val all = rows(backgroundLocation = BackgroundLocation.MISSING)
        assertFalse(all.all { it.ok })
        assertEquals("1 thing needs attention", setupHeadline(all))
    }

    @Test fun `granted background location is one quiet ok row`() {
        val row = rows(backgroundLocation = BackgroundLocation.GRANTED)[2]
        assertEquals("Location allowed all the time", row.label)
        assertTrue(row.ok)
        assertEquals(null, row.fixLabel)
        assertEquals(null, row.hint)
    }

    /**
     * Below API 29 there is no separate background permission, so there is
     * nothing to check — the row is omitted rather than drawn as a tick for a
     * thing that does not exist, and it cannot make setup look incomplete.
     */
    @Test fun `below API 29 the row is absent, not a tick and not a fault`() {
        val all = rows(backgroundLocation = BackgroundLocation.NOT_APPLICABLE)
        assertEquals(6, all.size)
        assertTrue(all.none { it.label.contains("Handoff is open") })
        assertTrue(all.none { it.label == "Location allowed all the time" })
        assertTrue(all.all { it.ok })
        assertEquals("Everything is set up", setupHeadline(all))
        // The rows after it keep their meaning, just one index earlier.
        assertEquals("Car paired", all[2].label)
    }

    /**
     * It is counted separately from the four foreground permissions on purpose:
     * bundling it into that request makes the system deny it silently.
     */
    @Test fun `it is its own row, not folded into the permission count`() {
        val all = rows(missingPermissions = 0, backgroundLocation = BackgroundLocation.MISSING)
        assertEquals("Permissions granted in the app", all[0].label)
        assertTrue(all[0].ok)
        assertFalse(all[2].ok)
    }
}
