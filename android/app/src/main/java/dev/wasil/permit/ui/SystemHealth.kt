package dev.wasil.permit.ui

/** One line per system requirement: quiet when fine, actionable when not. */
/**
 * What a health row's fix button does. A sealed type rather than the button's
 * own label text: dispatch used to be `when (fix) { "Grant" -> …; else ->
 * battery }`, so any new row silently inherited the battery-settings intent.
 * That is why the "car not paired" row shipped with no button at all — wiring
 * one would have opened the wrong screen.
 */
sealed interface FixAction {
    /** Ask for the first permission still missing. */
    data object GrantPermission : FixAction
    /** Open the battery-optimisation settings screen. */
    data object BatterySettings : FixAction
}

data class HealthRow(val label: String, val ok: Boolean, val fix: FixAction?, val fixLabel: String?)

/**
 * Permissions and battery only. Kept as-is (same two rows, same behaviour) so
 * the pre-existing callers and tests that only know about these two checks
 * are untouched — see the 6-argument overload below for the full row set.
 */
fun healthRows(missingPermissions: Int, batteryOptimised: Boolean): List<HealthRow> = listOf(
    if (missingPermissions == 0) {
        HealthRow("Permissions all granted", true, null, null)
    } else {
        val noun = if (missingPermissions == 1) "permission" else "permissions"
        HealthRow("$missingPermissions $noun missing", false, FixAction.GrantPermission, "Grant")
    },
    if (batteryOptimised) {
        HealthRow("Battery optimisation on", false, FixAction.BatterySettings, "Fix")
    } else {
        HealthRow("Battery optimisation off", true, null, null)
    },
)

/**
 * The full System list, and the line between "this is broken" and "this is how
 * you have it set up".
 *
 * Only two things here can stop the app doing its job. A permission it was
 * refused, and battery optimisation, both of which mean detection cannot run
 * reliably — and a car with no Bluetooth device paired, which means the core
 * loop never fires at all. Those are faults, they carry the alert colour, and
 * they are what the summary counts.
 *
 * **The other three are configurations, not faults**, and saying otherwise was
 * the bug this release fixes. One car with no permit and no second phone is a
 * working install of this app: the obligation half — where you are, what it
 * costs, until when — is computed from position alone and never consults a
 * permit. Telling that person "Setup incomplete" with a rust warning icon is
 * the app calling itself broken for a feature they declined. So permit, sharing
 * and home zone always read `ok = true`, worded as facts ("No permit — rates
 * and hours only") rather than as things left undone.
 */
fun healthRows(
    missingPermissions: Int,
    batteryOptimised: Boolean,
    carPaired: Boolean,
    permitAdded: Boolean,
    syncConfigured: Boolean,
    homeZoneSet: Boolean,
): List<HealthRow> = healthRows(missingPermissions, batteryOptimised) + listOf(
    if (carPaired) {
        HealthRow("Car paired", true, null, null)
    } else {
        HealthRow("No car paired — detection won't run", false, null, null)
    },
    HealthRow(
        if (permitAdded) "Permit added" else "No permit — rates and hours only",
        true, null, null,
    ),
    HealthRow(
        if (syncConfigured) "Sharing with the other phone" else "Sharing off — this phone only",
        true, null, null,
    ),
    HealthRow(if (homeZoneSet) "Home zone set" else "No home zone set", true, null, null),
)

/**
 * The headline over the Settings summary card.
 *
 * It used to be "Setup complete" or "Setup incomplete", and the second one was
 * a lie in the most common standalone case. Naming the count instead means the
 * card can only ever be as loud as the number of things that are genuinely
 * wrong, and it says how many rather than making you hunt for them.
 */
fun setupHeadline(rows: List<HealthRow>): String = when (val faults = rows.count { !it.ok }) {
    0 -> "Everything is set up"
    1 -> "1 thing needs attention"
    else -> "$faults things need attention"
}

/**
 * The one-line description of how this install is configured — facts in a row,
 * none of them a complaint. "no permit" is a description of an app that works;
 * "sync not set up", which this replaces, read as a step someone forgot.
 */
fun setupConfigurationLine(
    phoneLabel: String?,
    permitAdded: Boolean,
    syncConfigured: Boolean,
): String = listOf(
    phoneLabel?.let { "$it's phone" } ?: "Whose phone isn't set yet",
    if (permitAdded) "permit added" else "no permit",
    if (syncConfigured) "sharing on" else "sharing off",
).joinToString(" · ")
