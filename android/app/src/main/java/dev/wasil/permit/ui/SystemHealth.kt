package dev.wasil.permit.ui

/** One line per system requirement: quiet when fine, actionable when not. */
data class HealthRow(val label: String, val ok: Boolean, val fixLabel: String?)

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
