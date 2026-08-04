package dev.wasil.permit.parking.shared

/**
 * Which of the two cars the single permit is worth more to, judged only on
 * what the two spots cost.
 *
 * Named from the asking phone's point of view because that is the only thing a
 * phone can act on: each phone runs this locally against its own state and the
 * other's published state, and can only ever move the permit onto or off its
 * own car.
 */
sealed interface PermitPreference {
    /** My spot is the expensive one — the permit is worth more here. */
    data object Mine : PermitPreference

    /** Their spot is the expensive one — I should not be holding this. */
    data object Theirs : PermitPreference

    /**
     * The two spots do not settle it: equal cost, or at least one of them
     * unpriceable. Leave the permit wherever it currently sits — see the
     * bias note on [preferredPermitHolder] for why that is the safe answer
     * rather than a cop-out.
     */
    data object NoPreference : PermitPreference
}

/**
 * How strong a claim one spot has on the permit. Four levels rather than a
 * single nullable number, because the two "free" cases and the two
 * "unpriceable" cases are genuinely different situations and collapsing them
 * loses the safety argument.
 */
private sealed interface SpotClaim {
    /** Not parked outside: at home, in a marked-free zone, or not parked at all. */
    data object Nothing : SpotClaim

    /**
     * Parked in a paid zone that is not charging at this moment. Weaker than
     * any charging spot, but stronger than [Nothing]: charging resumes where
     * this car is standing, and never resumes at home.
     */
    data object FreeForNow : SpotClaim

    /** Parked in a paid zone we could not put a price on. Assumed to be charging. */
    data object Unpriced : SpotClaim

    /** Parked in a paid zone charging [cents] per hour, always above zero. */
    data class Charging(val cents: Int) : SpotClaim
}

/**
 * Who should hold the permit, given what each spot costs.
 *
 * **This never sees a location.** [PhoneState] stopped carrying coordinates in
 * v0.6.3; each phone prices its own spot locally and publishes the number.
 * Comparing two integers answers the question completely, so neither brother
 * learns where the other parked in order to settle it.
 *
 * **The bias, and why it points the way it does.** The two mistakes available
 * here are not equally expensive. Leaving the permit where it is costs at worst
 * the difference between two tariffs. Moving it off a car that needed it costs
 * a fine. So the rule is: **only move the permit on strictly better evidence,
 * and never on a guess.** [PermitPreference.Mine] is returned only when the
 * other spot is *strictly* weaker than mine; anything ambiguous returns
 * [PermitPreference.NoPreference] and the permit stays put, which strands
 * nobody who is currently covered.
 *
 * The ordering, weakest claim first:
 *
 * 1. **Not parked outside.** Home or a free zone — the permit is worth nothing
 *    here.
 * 2. **Paid zone, free right now.** Outside its charging hours. Nothing is owed
 *    this minute, but this is still a spot where a meter starts running.
 * 3. **Charging, or unpriceable.** An unreadable rate sits in the top tier
 *    rather than the bottom on purpose: a spot we cannot price might be the
 *    expensive one, and the app already assumes paid when tariff data is
 *    missing. Within this tier two known rates compare by number, but an
 *    unpriced spot against a priced one is genuinely undecidable and yields
 *    [PermitPreference.NoPreference] — refusing to answer beats answering
 *    wrongly in the direction of a fine.
 *
 * **Ties.** Equal rates return [PermitPreference.NoPreference], and that is a
 * deliberate choice rather than a missing case. Swapping the permit between two
 * equally expensive spots buys nothing, costs two live API calls, and leaves
 * both cars briefly uncovered in between. It is also the only tie-break that
 * cannot go wrong: any rule based on "who parked first" would compare
 * [PhoneState.parkedAtMs] values stamped by two different phones' clocks — only
 * `heartbeatAtMs` gets the server's timestamp — so a few seconds of clock skew
 * could have *both* phones conclude they won and both grab the permit. A rule
 * that both sides can evaluate to the same answer without trusting a clock is
 * worth more than a rule that picks a winner.
 *
 * **Staleness.** [other] is disregarded entirely once its heartbeat is older
 * than [ClaimGuard.STALE_AFTER_MS], and treated exactly as if that car were not
 * parked outside — the same reading [ClaimGuard.evaluate] already takes, down to
 * the boundary being exclusive, so the two cannot disagree about whether the
 * other car is still there. A published rate is a statement about a moment; six
 * hours later it is not evidence of anything. A null [other] means that phone
 * has never written its node and is likewise no claim on the permit.
 *
 * [mine] is always treated as fresh. It is read from this phone's own local
 * store rather than off the network, so its heartbeat is meaningless here —
 * the same reasoning behind `GuardedClaim.localPhoneState`.
 *
 * Pure: no clock, no network, no Android. [nowMs] is passed in so staleness is
 * testable at the exact boundary.
 */
fun preferredPermitHolder(
    mine: PhoneState,
    other: PhoneState?,
    nowMs: Long,
): PermitPreference {
    val myClaim = claimOf(mine)
    val theirClaim = when {
        other == null -> SpotClaim.Nothing
        nowMs - other.heartbeatAtMs > ClaimGuard.STALE_AFTER_MS -> SpotClaim.Nothing
        else -> claimOf(other)
    }

    val myTier = tier(myClaim)
    val theirTier = tier(theirClaim)
    if (myTier != theirTier) {
        return if (myTier > theirTier) PermitPreference.Mine else PermitPreference.Theirs
    }

    // Same tier. Only two known prices can separate them; everything else —
    // both idle, both free, or either one unpriced — leaves the permit alone.
    if (myClaim is SpotClaim.Charging && theirClaim is SpotClaim.Charging) {
        return when {
            myClaim.cents > theirClaim.cents -> PermitPreference.Mine
            myClaim.cents < theirClaim.cents -> PermitPreference.Theirs
            else -> PermitPreference.NoPreference
        }
    }
    return PermitPreference.NoPreference
}

/**
 * A published state read as a claim on the permit.
 *
 * A negative rate is nonsense that no correct writer produces, so it is treated
 * as unreadable rather than as free — a corrupt node must not be able to talk a
 * car out of its permit.
 */
private fun claimOf(state: PhoneState): SpotClaim = when {
    !state.parkedOutside -> SpotClaim.Nothing
    state.rateCentsPerHour == null -> SpotClaim.Unpriced
    state.rateCentsPerHour < 0 -> SpotClaim.Unpriced
    state.rateCentsPerHour == 0 -> SpotClaim.FreeForNow
    else -> SpotClaim.Charging(state.rateCentsPerHour)
}

/** Charging and unpriced share the top tier: see the ordering on [preferredPermitHolder]. */
private fun tier(claim: SpotClaim): Int = when (claim) {
    SpotClaim.Nothing -> 0
    SpotClaim.FreeForNow -> 1
    SpotClaim.Unpriced, is SpotClaim.Charging -> 2
}
