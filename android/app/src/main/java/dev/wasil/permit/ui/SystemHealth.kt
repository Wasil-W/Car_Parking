package dev.wasil.permit.ui

/** One line per system requirement: quiet when fine, actionable when not. */
data class HealthRow(val label: String, val ok: Boolean, val fixLabel: String?)

/**
 * Permissions and battery only. Kept as-is (same two rows, same behaviour) so
 * the pre-existing callers and tests that only know about these two checks
 * are untouched — see the 5-argument overload below for the full row set.
 */
fun healthRows(missingPermissions: Int, batteryOptimised: Boolean): List<HealthRow> = listOf(
    if (missingPermissions == 0) {
        HealthRow("Permissions all granted", true, null)
    } else {
        val noun = if (missingPermissions == 1) "permission" else "permissions"
        HealthRow("$missingPermissions $noun missing", false, "Grant")
    },
    if (batteryOptimised) {
        HealthRow("Battery optimisation on", false, "Fix")
    } else {
        HealthRow("Battery optimisation off", true, null)
    },
)

/**
 * The full System health list. `SetupFlow` only ever asks two of the six
 * first-run questions (credentials, whose phone) — car pairing, the sync URL
 * and the home zone are all skippable and otherwise surface nowhere, so a
 * user who skips them gets a green "Setup complete" while detection silently
 * never fires. These three rows make that state visible.
 *
 * Car pairing is treated as a real requirement (`ok = false`, alert-coloured)
 * when missing, because without a paired car Bluetooth-disconnect detection
 * can never fire at all — the app's core loop is inert, not just degraded.
 *
 * Sync and home zone are different: leaving them unset is a legitimate,
 * working configuration (single-phone use; no "never claim near home"
 * refinement), not a fault. They always read `ok = true` so they never turn
 * the summary tick red and never borrow the `alert` colour for a state that
 * isn't wrong — the row exists purely so the choice is visible, worded
 * informationally ("Sync not set up" / "No home zone set") rather than as a
 * warning.
 */
fun healthRows(
    missingPermissions: Int,
    batteryOptimised: Boolean,
    carPaired: Boolean,
    syncConfigured: Boolean,
    homeZoneSet: Boolean,
): List<HealthRow> = healthRows(missingPermissions, batteryOptimised) + listOf(
    if (carPaired) {
        HealthRow("Car paired", true, null)
    } else {
        HealthRow("No car paired — detection won't run", false, null)
    },
    HealthRow(if (syncConfigured) "Sync configured" else "Sync not set up", true, null),
    HealthRow(if (homeZoneSet) "Home zone set" else "No home zone set", true, null),
)
