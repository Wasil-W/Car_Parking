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
    /**
     * Open this app's own system settings page.
     *
     * Its own action because background location cannot be granted by a
     * permission dialog at all on API 30+ — "Allow all the time" exists only on
     * that page, and a `requestPermissions` call for it is denied without ever
     * showing anything. Routing it through [GrantPermission] would produce a
     * button that looks like it works and silently does nothing, which is the
     * exact failure this whole row exists to undo.
     */
    data object AppSettings : FixAction
}

/**
 * Whether this app may read a position while it is not on screen.
 *
 * [NOT_APPLICABLE] is not a polite way of saying "granted". Below API 29 there
 * is no separate background permission — fine location covers both cases — so
 * there is nothing to check and nothing to show, and the row is omitted rather
 * than drawn as a tick for a thing that does not exist.
 */
enum class BackgroundLocation { GRANTED, MISSING, NOT_APPLICABLE }

/**
 * The one place this state is decided.
 *
 * Lifted out of `SettingsScreen` in v0.7.7 because a second caller appeared:
 * the decision prompt now explains *why* it could not place a park, and the
 * honest answer is usually this permission. Two copies of the same three-branch
 * check would be two things to keep in step, and this project has a rule about
 * exactly that.
 */
fun backgroundLocationState(context: android.content.Context): BackgroundLocation = when {
    android.os.Build.VERSION.SDK_INT < 29 -> BackgroundLocation.NOT_APPLICABLE
    androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED -> BackgroundLocation.GRANTED
    else -> BackgroundLocation.MISSING
}

/** [hint] is the rare case where the row's consequence needs a sentence, not a label. */
data class HealthRow(
    val label: String,
    val ok: Boolean,
    val fix: FixAction?,
    val fixLabel: String?,
    val hint: String? = null,
)

/**
 * Permissions and battery only. Kept as-is (same two rows, same behaviour) so
 * the pre-existing callers and tests that only know about these two checks
 * are untouched — see the 6-argument overload below for the full row set.
 */
fun healthRows(missingPermissions: Int, batteryOptimised: Boolean): List<HealthRow> = listOf(
    if (missingPermissions == 0) {
        // Not "Permissions all granted". That is what it said while background
        // location was missing, and it was read exactly as intended — as an
        // all-clear covering every permission the app has. It counts only the
        // ones a dialog inside the app can grant; the one that decides whether
        // detection works at all lives in system settings and has its own row
        // below. The wording now says which set it is talking about.
        HealthRow("Permissions granted in the app", true, null, null)
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
    backgroundLocation: BackgroundLocation,
): List<HealthRow> = healthRows(missingPermissions, batteryOptimised) +
    backgroundLocationRow(backgroundLocation) + listOf(
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
 * The row that was missing, and the most expensive omission in this file's
 * history.
 *
 * `ACCESS_BACKGROUND_LOCATION` has been declared in the manifest since
 * detection was built and never requested at runtime. On API 29+ that is a
 * runtime permission, so declaring it does nothing on its own: fine location
 * alone lets the app read a position only while it is in the foreground, and
 * **every position this app depends on is read from a background worker** —
 * `ParkDetectionUseCase` behind `enqueueDetection`, and the live-location
 * sampler behind `LiveLocationWorker`. Both asked, both got null, every time.
 *
 * That is the single cause behind three symptoms reported over weeks: no parked
 * pin, being asked about the permit at home, and the car's location
 * disappearing. All three read as separate bugs and all three are this.
 *
 * And the health check said "Permissions all granted" throughout, because the
 * list it counts contains four permissions and not this one. A check that
 * reports a false all-clear is worse than no check — it was believed. So this
 * gets its own row rather than joining that count, both because it cannot be
 * fixed the same way and because it must be impossible to miss again.
 *
 * The label states the consequence rather than naming the permission: nobody
 * can act on "background location missing", and everybody can act on "parks are
 * only noticed while Handoff is open".
 */
private fun backgroundLocationRow(state: BackgroundLocation): List<HealthRow> = when (state) {
    BackgroundLocation.NOT_APPLICABLE -> emptyList()
    BackgroundLocation.GRANTED -> listOf(
        HealthRow("Location allowed all the time", true, null, null),
    )
    BackgroundLocation.MISSING -> listOf(
        HealthRow(
            "Parks are only noticed while Handoff is open",
            false,
            FixAction.AppSettings,
            "Fix",
            hint = "Detection runs in the background, so a park is missed unless the " +
                "app happens to be on screen. Open app settings, then Permissions → " +
                "Location, and choose \"Allow all the time\".",
        ),
    )
}

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
