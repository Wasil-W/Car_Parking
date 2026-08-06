package dev.wasil.permit.parking.shared

object ClaimGuard {
    /** After 6 h without a heartbeat the other phone's "parked" is not trusted. */
    const val STALE_AFTER_MS: Long = 6 * 60 * 60 * 1000

    sealed interface Verdict {
        data object Proceed : Verdict

        /**
         * Claiming might strand the other car, so it must be put to the user.
         *
         * [other] carries [PhoneState.parkedOutsideKnown], and everything the
         * user is told about this has to branch on it: with a known park the
         * app can say *their car is parked outside and the permit is on it*;
         * with an unknown one all it knows is that their phone could not tell.
         * Stating the first when only the second is true is publishing a guess
         * as a fact, which is the one thing this project does not do.
         */
        data class Blocked(val other: PhoneState) : Verdict
    }

    /**
     * Claiming strands the non-target car iff that car is parked outside
     * (fresh heartbeat) AND the permit is actually on its plate right now.
     *
     * "Not parked outside" only clears the way when it is a *finding*. Until
     * v0.6.5 this read `!parkedOutside` and proceeded, without ever asking
     * whether that false meant "I looked, and I am not on a paid street" or "I
     * parked and never resolved a position, so this is last week's answer". The
     * second is a gap, and treating a gap as a green light is how the other car
     * ends up unpermitted on the strength of a failed GPS read.
     *
     * So an unknown state simply loses the shortcut. It is not treated as
     * "definitely parked outside" either — the staleness and plate tests below
     * still apply, and either one still lets a claim through. The rule is only
     * that unknown is never *more* permissive than known.
     */
    fun evaluate(
        nonTarget: PhoneState?,
        nonTargetPlate: String,
        activeVrn: String?,
        nowMs: Long,
    ): Verdict {
        if (nonTarget == null) return Verdict.Proceed
        if (!nonTarget.parkedOutside && nonTarget.parkedOutsideKnown) return Verdict.Proceed
        if (nowMs - nonTarget.heartbeatAtMs > STALE_AFTER_MS) return Verdict.Proceed
        if (activeVrn != nonTargetPlate) return Verdict.Proceed
        return Verdict.Blocked(nonTarget)
    }
}
