package dev.wasil.permit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemHealthTest {
    @Test fun `everything fine gives two ok rows and no fix affordance`() {
        val rows = healthRows(missingPermissions = 0, batteryOptimised = false)
        assertEquals(listOf(true, true), rows.map { it.ok })
        assertEquals(listOf(null, null), rows.map { it.fixLabel })
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

    @Test fun `everything set gives six ok rows`() {
        val rows = healthRows(
            missingPermissions = 0, batteryOptimised = false,
            carPaired = true, permitAdded = true, syncConfigured = true, homeZoneSet = true,
        )
        assertEquals(6, rows.size)
        assertTrue(rows.all { it.ok })
        assertEquals("Car paired", rows[2].label)
        assertEquals("Permit added", rows[3].label)
        assertEquals("Sharing with the other phone", rows[4].label)
        assertEquals("Home zone set", rows[5].label)
    }

    @Test fun `no car paired is not ok — detection cannot run without it`() {
        val row = healthRows(
            missingPermissions = 0, batteryOptimised = false,
            carPaired = false, permitAdded = true, syncConfigured = true, homeZoneSet = true,
        )[2]
        assertEquals("No car paired — detection won't run", row.label)
        assertEquals(false, row.ok)
    }

    @Test fun `no permit reads as a configuration, not a fault`() {
        val row = healthRows(
            missingPermissions = 0, batteryOptimised = false,
            carPaired = true, permitAdded = false, syncConfigured = true, homeZoneSet = true,
        )[3]
        assertEquals("No permit — rates and hours only", row.label)
        assertEquals(true, row.ok)
        assertEquals(null, row.fixLabel)
    }

    @Test fun `sharing off reads as informational, not a fault`() {
        val row = healthRows(
            missingPermissions = 0, batteryOptimised = false,
            carPaired = true, permitAdded = true, syncConfigured = false, homeZoneSet = true,
        )[4]
        assertEquals("Sharing off — this phone only", row.label)
        assertEquals(true, row.ok)
        assertEquals(null, row.fixLabel)
    }

    @Test fun `no home zone reads as informational, not a fault`() {
        val row = healthRows(
            missingPermissions = 0, batteryOptimised = false,
            carPaired = true, permitAdded = true, syncConfigured = true, homeZoneSet = false,
        )[5]
        assertEquals("No home zone set", row.label)
        assertEquals(true, row.ok)
        assertEquals(null, row.fixLabel)
    }

    @Test fun `several missing — only the genuine faults are not ok`() {
        val rows = healthRows(
            missingPermissions = 2, batteryOptimised = true,
            carPaired = false, permitAdded = false, syncConfigured = false, homeZoneSet = false,
        )
        assertEquals(listOf(false, false, false, true, true, true), rows.map { it.ok })
    }

    /**
     * The whole point of the copy pass, pinned: one car, no permit, no sharing,
     * no home zone is a **working** install and the app must not call it broken.
     */
    @Test fun `a one-car no-permit no-sharing install is fully set up`() {
        val rows = healthRows(
            missingPermissions = 0, batteryOptimised = false,
            carPaired = true, permitAdded = false, syncConfigured = false, homeZoneSet = false,
        )
        assertTrue(rows.all { it.ok })
        assertEquals("Everything is set up", setupHeadline(rows))
    }

    @Test fun `the headline counts faults rather than declaring setup incomplete`() {
        val one = healthRows(
            missingPermissions = 1, batteryOptimised = false,
            carPaired = true, permitAdded = false, syncConfigured = false, homeZoneSet = false,
        )
        assertEquals("1 thing needs attention", setupHeadline(one))

        val three = healthRows(
            missingPermissions = 1, batteryOptimised = true,
            carPaired = false, permitAdded = true, syncConfigured = true, homeZoneSet = true,
        )
        assertEquals("3 things need attention", setupHeadline(three))
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
}
