package dev.wasil.permit.parking

/**
 * A decision raised by a background notification, waiting for the user to act
 * on it. Must survive the app being killed between the notification firing and
 * the tap, so it is persisted through [ParkStateStore] rather than held in a
 * ViewModel. The tappable-notification screen and the notification's own
 * action buttons both read/act on the same record — see [ParkActionReceiver]
 * for the single place actions are actually carried out.
 */
sealed interface PendingDecision {
    val raisedAtMs: Long

    /** The other car is parked and holding the permit; claiming now would strand it. */
    data class Blocked(
        val otherLabel: String,
        val parkedAtMs: Long,
        val heartbeatAtMs: Long,
        override val raisedAtMs: Long,
    ) : PendingDecision

    /** Parked, but detection couldn't tell what to do automatically. */
    data class Manual(override val raisedAtMs: Long) : PendingDecision

    /** Parked free while the other car is still parked outside on this permit. */
    data class GiveBack(val otherLabel: String, override val raisedAtMs: Long) : PendingDecision

    /** The other phone forced a claim; this car may now be parked without a permit. */
    data class Takeover(val byLabel: String, override val raisedAtMs: Long) : PendingDecision
}

/**
 * Flat, primitive shape a SharedPreferences-backed store can hold directly
 * (Strings/Longs only) — see [PrefsParkStateStore]. Kept separate from
 * [PendingDecision] so the encode/decode mapping below is plain Kotlin,
 * testable without touching Android.
 */
data class PendingDecisionRecord(
    val kind: String?,
    val label: String? = null,
    val parkedAtMs: Long = 0L,
    val heartbeatAtMs: Long = 0L,
    val raisedAtMs: Long = 0L,
)

private const val KIND_BLOCKED = "BLOCKED"
private const val KIND_MANUAL = "MANUAL"
private const val KIND_GIVE_BACK = "GIVE_BACK"
private const val KIND_TAKEOVER = "TAKEOVER"

fun PendingDecision.toRecord(): PendingDecisionRecord = when (this) {
    is PendingDecision.Blocked -> PendingDecisionRecord(
        kind = KIND_BLOCKED,
        label = otherLabel,
        parkedAtMs = parkedAtMs,
        heartbeatAtMs = heartbeatAtMs,
        raisedAtMs = raisedAtMs,
    )
    is PendingDecision.Manual -> PendingDecisionRecord(
        kind = KIND_MANUAL,
        raisedAtMs = raisedAtMs,
    )
    is PendingDecision.GiveBack -> PendingDecisionRecord(
        kind = KIND_GIVE_BACK,
        label = otherLabel,
        raisedAtMs = raisedAtMs,
    )
    is PendingDecision.Takeover -> PendingDecisionRecord(
        kind = KIND_TAKEOVER,
        label = byLabel,
        raisedAtMs = raisedAtMs,
    )
}

/**
 * Null on anything that doesn't decode cleanly — an empty/corrupt record, an
 * unrecognised kind, or a kind missing the label it requires — so a store that
 * has never held a decision (or was cleared) reads back as "no decision"
 * rather than throwing.
 */
fun PendingDecisionRecord.toDecision(): PendingDecision? = when (kind) {
    KIND_BLOCKED -> label?.let {
        PendingDecision.Blocked(it, parkedAtMs, heartbeatAtMs, raisedAtMs)
    }
    KIND_MANUAL -> PendingDecision.Manual(raisedAtMs)
    KIND_GIVE_BACK -> label?.let { PendingDecision.GiveBack(it, raisedAtMs) }
    KIND_TAKEOVER -> label?.let { PendingDecision.Takeover(it, raisedAtMs) }
    else -> null
}

/** Past this age a pending decision is treated as absent — the situation that raised it has long moved on. */
const val PENDING_DECISION_MAX_AGE_MS: Long = 12 * 60 * 60 * 1000L

/**
 * The store's [ParkStateStore.pendingDecision] as it currently applies: null
 * if there was never one, if it was cleared, or if it is older than
 * [PENDING_DECISION_MAX_AGE_MS] — a stale decision must never resurface just
 * because nothing has explicitly cleared it.
 */
fun ParkStateStore.currentPendingDecision(nowMs: Long = System.currentTimeMillis()): PendingDecision? =
    pendingDecision?.takeIf { nowMs - it.raisedAtMs <= PENDING_DECISION_MAX_AGE_MS }
