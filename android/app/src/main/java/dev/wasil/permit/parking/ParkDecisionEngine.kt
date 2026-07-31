package dev.wasil.permit.parking

sealed interface Decision {
    data object FalseAlarm : Decision
    data object ParkedInCar : Decision
    data object ParkedWalkedAway : Decision
    data object Unclear : Decision
}

/**
 * Pure decision table over accumulated signals. Returns null while evidence is
 * insufficient - the caller keeps sampling until TIMEOUT_MS, then gets Unclear.
 * Auto-switching is only ever allowed on ParkedInCar / ParkedWalkedAway.
 */
object ParkDecisionEngine {
    const val CONFIDENCE = 70
    const val MIN_STILL_DELAY_MS = 5_000L

    /**
     * How far you must move from the car before "you walked away" is believed.
     *
     * Lowered from 10 m: detection only starts once the car's Bluetooth has
     * dropped, so the car is already stationary and this is measuring you
     * leaving it, not the car moving. The remaining risk is a Bluetooth blip in
     * traffic, and that got much cheaper — claiming is idempotent since v0.3.4,
     * and the collision guard stops an early claim stranding the other car.
     *
     * Not lowered to 2 m: [MAX_ACCURACY_M] admits fixes off by up to 25 m, so
     * two consecutive readings from a motionless phone can differ by more than
     * that on noise alone.
     */
    const val WALK_DISTANCE_M = 4.0
    const val MAX_ACCURACY_M = 25f
    const val TIMEOUT_MS = 90_000L

    fun decide(
        samples: List<ActivitySample>,
        disconnectPoint: GeoPoint?,
        latestPoint: GeoPoint?,
        elapsedMs: Long,
    ): Decision? {
        val confident = samples.filter { it.confidence >= CONFIDENCE }
        // Driving again beats everything: a BT drop mid-drive must never switch.
        if (confident.any { it.type == ActivityType.IN_VEHICLE }) return Decision.FalseAlarm
        if (confident.any { it.type == ActivityType.ON_FOOT }) return Decision.ParkedWalkedAway
        if (confident.any { it.type == ActivityType.STILL && it.elapsedMs >= MIN_STILL_DELAY_MS }) {
            return Decision.ParkedInCar
        }
        if (disconnectPoint != null && latestPoint != null &&
            disconnectPoint.accuracyM <= MAX_ACCURACY_M && latestPoint.accuracyM <= MAX_ACCURACY_M &&
            distanceMeters(disconnectPoint, latestPoint) > WALK_DISTANCE_M
        ) {
            return Decision.ParkedWalkedAway
        }
        return if (elapsedMs >= TIMEOUT_MS) Decision.Unclear else null
    }
}
