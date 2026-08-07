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

    /**
     * @param carLinkBackUp whether the car's Bluetooth has **reconnected** since
     *   the drop that started this run.
     */
    fun decide(
        samples: List<ActivitySample>,
        disconnectPoint: GeoPoint?,
        latestPoint: GeoPoint?,
        elapsedMs: Long,
        carLinkBackUp: Boolean,
    ): Decision? {
        // Ranked above every activity sample, because it is the same conclusion
        // as IN_VEHICLE from a harder sensor: the car's own stereo is answering,
        // so the car has power, so the car has not been left.
        //
        // This is the hole that recorded a park at a traffic light (reported
        // 2026-08-08: stopped at a light, engine running, and the app put the
        // car there). The blip drops the link, detection is enqueued, the link
        // comes straight back — and CarBluetoothReceiver dutifully resets
        // `parked`, cancels the claim chain and restarts the sampler, all of
        // which the *already running* detection loop knew nothing about. It kept
        // polling, and a car stopped at a red light is exactly what Play
        // Services reports as STILL at high confidence: IN_VEHICLE describes
        // motion, and there is none. Five seconds later the table below said
        // ParkedInCar.
        //
        // Deliberately not fixed by raising MIN_STILL_DELAY_MS. Five seconds is
        // right for the case it was chosen for, a red light lasts far longer
        // than any threshold worth setting, and a longer wait would delay every
        // genuine park to half-catch one false one. The reconnect is not a
        // heuristic about how long people sit still — it is the answer.
        if (carLinkBackUp) return Decision.FalseAlarm
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
