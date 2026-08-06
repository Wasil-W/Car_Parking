package dev.wasil.permit.parking.shared

import kotlinx.serialization.Serializable

/**
 * Everything one phone tells the other. Deliberately small — and as of v0.6.3,
 * exactly what the receiving side reads and nothing else.
 *
 * **No coordinates.** Until v0.6.2 this carried `lat`, `lng` and `accuracyM`,
 * so each phone could read exactly where the other car was parked. The audit
 * that found it also found that nothing ever read them: the collision guard
 * uses [parkedOutside], [heartbeatAtMs] and the plate ([ClaimGuard.evaluate]),
 * the give-back check uses the same three, and the map draws from this phone's
 * own local store.
 *
 * They were removed rather than filtered because there is nowhere to filter.
 * The app has no backend — both phones read and write one Firebase Realtime
 * Database node behind a shared URL with no per-user auth, so any holder of
 * that URL can read any path under it. A field that is never published cannot
 * be read by anyone; a field guarded by a rule is only as good as the rule.
 *
 * **No zone code either.** It went the same way, for the same reason, once it
 * was clear it had the same status: written since v0.3, read by nothing. It was
 * meant for a "the other car is in zone T13B" display that was never built, and
 * it is a location signal in its own right — Amsterdam's tariff areas run up to
 * 3 km across, so a code narrows the other car to a district. Wasil's point when
 * dropping it: for anything a person actually reads, the *name* of a place
 * matters and the code does not.
 *
 * **No rate.** v0.6.2 published cents-per-hour so the two spots could be
 * compared and the dearer one keep the permit. That comparison is not being
 * built: it only pays off once the cheaper car can *pay* instead, and today it
 * gets a fine either way, so deciding by rate would change which brother is
 * fined and nothing else. Rather than leave the machinery dormant, it is gone —
 * see docs/TIMELINE.md, "Parked".
 *
 * What remains is the answer to one question: *is the other car sitting on a
 * paid street right now, and is that information fresh enough to trust?* —
 * plus, since v0.6.5, whether that answer is a finding at all.
 *
 * Removing fields is safe across an upgrade in both directions. The decoder
 * ignores unknown keys, so a phone still on an older version publishing
 * coordinates, a zone code or a rate is read cleanly here and those are dropped
 * on the floor; and every field below has a default, so an older node missing a
 * field reads as that field's safe value.
 */
@Serializable
data class PhoneState(
    val parkedOutside: Boolean = false,
    val parkedAtMs: Long = 0,
    val heartbeatAtMs: Long = 0,
    /**
     * Whether [parkedOutside] is a finding or a leftover.
     *
     * False means the phone that wrote this parked without ever resolving a
     * position, so the value above is simply whatever the previous park left
     * standing. It was recorded locally from v0.6.2 and **read by nothing**;
     * publishing it is the other half of that fix. [ClaimGuard.evaluate] is
     * where it lands, and the rule there is the one this project pays for: an
     * unknown state is never more permissive than a known one.
     *
     * Defaults **true**, which is the compatible answer rather than the timid
     * one. A node written by a phone that predates this field is a phone whose
     * `parkedOutside` was always published as a fact, so reading it as a fact
     * is exactly the behaviour that phone expects. Defaulting to false would
     * mean no claim could ever be made against an un-upgraded phone without a
     * prompt.
     */
    val parkedOutsideKnown: Boolean = true,
)

@Serializable
data class PermitClaim(
    val holder: String,
    val vrn: String,
    val claimedAtMs: Long = 0,
    val forced: Boolean = false,
)
