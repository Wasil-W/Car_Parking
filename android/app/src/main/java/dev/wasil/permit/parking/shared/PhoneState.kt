package dev.wasil.permit.parking.shared

import kotlinx.serialization.Serializable

/**
 * Everything one phone tells the other. Deliberately small.
 *
 * **No coordinates.** Until v0.6.3 this carried `lat`, `lng` and `accuracyM`,
 * and both phones could therefore read exactly where the other car was parked.
 * The audit that found it also found that nothing ever read them back: the
 * collision guard uses [parkedOutside], [heartbeatAtMs] and the plate
 * ([ClaimGuard.evaluate]), the give-back check uses the same three, and the map
 * draws from this phone's own local store. They were written and never read.
 *
 * They are removed rather than filtered because there is nowhere to filter.
 * The app has no backend — both phones read and write one Firebase Realtime
 * Database node behind a shared URL with no per-user auth, so any holder of
 * that URL can read any path under it. A field that is never published cannot
 * be read by anyone; a field guarded by a rule can only be as good as the rule.
 * Not publishing is strictly stronger than filtering, and it is also less code.
 *
 * What replaces them is [rateCentsPerHour] — a price, not a place. It is
 * enough to decide which of the two spots should keep the single permit (see
 * [preferredPermitHolder]) and reveals nothing about where either car is: every
 * paid street in the city shares a handful of rates.
 *
 * Removing fields is safe across an upgrade in both directions. The decoder
 * ignores unknown keys, so a phone still on v0.6.2 publishing `lat`/`lng` is
 * read cleanly here and its coordinates are simply dropped on the floor; and
 * every field below has a default, so this phone reads an old node that has no
 * `rateCentsPerHour` as "rate unknown", which is the safe direction.
 */
@Serializable
data class PhoneState(
    val parkedOutside: Boolean = false,
    /**
     * Cents per hour this car's spot is charging right now — see
     * [dev.wasil.permit.parking.zones.spotRateCents] for how it is derived.
     * Zero means free; null means we could not price the spot, which is not the
     * same thing and is never treated as free.
     */
    val rateCentsPerHour: Int? = null,
    val zoneCode: String? = null,
    val parkedAtMs: Long = 0,
    val heartbeatAtMs: Long = 0,
)

@Serializable
data class PermitClaim(
    val holder: String,
    val vrn: String,
    val claimedAtMs: Long = 0,
    val forced: Boolean = false,
)
