package dev.wasil.permit.parking.shared

object ClaimGuard {
    /** After 6 h without a heartbeat the other phone's "parked" is not trusted. */
    const val STALE_AFTER_MS: Long = 6 * 60 * 60 * 1000

    sealed interface Verdict {
        data object Proceed : Verdict
        data class Blocked(val other: PhoneState) : Verdict
    }

    /**
     * Claiming strands the non-target car iff that car is parked outside
     * (fresh heartbeat) AND the permit is actually on its plate right now.
     */
    fun evaluate(
        nonTarget: PhoneState?,
        nonTargetPlate: String,
        activeVrn: String?,
        nowMs: Long,
    ): Verdict {
        if (nonTarget == null || !nonTarget.parkedOutside) return Verdict.Proceed
        if (nowMs - nonTarget.heartbeatAtMs > STALE_AFTER_MS) return Verdict.Proceed
        if (activeVrn != nonTargetPlate) return Verdict.Proceed
        return Verdict.Blocked(nonTarget)
    }
}
