package dev.wasil.permit.parking

import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.store.CredentialStore
import dev.wasil.permit.data.store.PermitConfig
import dev.wasil.permit.parking.shared.ClaimGuard
import dev.wasil.permit.parking.shared.PhoneState
import dev.wasil.permit.parking.shared.SharedStateStore

fun MyCar.other(): MyCar = if (this == MyCar.WASIL) MyCar.WALID else MyCar.WASIL
fun MyCar.label(): String = if (this == MyCar.WASIL) "Wasil" else "Walid"
fun MyCar.key(): String = name.lowercase()

/** Inverse of [MyCar.label] — turns a notifier's "Wasil"/"Walid" string back into a [MyCar]. */
fun myCarForLabel(label: String): MyCar = if (label == "Wasil") MyCar.WASIL else MyCar.WALID

sealed interface GuardedResult {
    /** Guard passed or was skipped; [outcome] is the raw switch outcome. */
    data class Done(val outcome: ParkOutcome, val guardSkippedNote: String? = null) : GuardedResult
    data class Blocked(val otherLabel: String, val other: PhoneState) : GuardedResult
}

sealed interface GiveBackResult {
    data class Given(val vrn: String) : GiveBackResult
    data object NothingToDo : GiveBackResult
    data object Failed : GiveBackResult
}

/**
 * The single choke point for permit switches. Checks whether the switch would
 * strand the non-target car (parked outside, fresh, actually holding the
 * permit) before executing; records the claim in shared state afterwards.
 */
class GuardedClaim(
    private val repository: PermitRepository,
    private val credentialStore: CredentialStore,
    private val stateStore: ParkStateStore,
    private val shared: SharedStateStore,
    private val claimPermit: ClaimPermit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * The plate, if the permit is already on this phone's own car — otherwise
     * null, including when the answer cannot be determined.
     *
     * Asked before any prompt that would offer to claim, because a question you
     * cannot answer wrongly is not worth asking: if the permit is already
     * yours, there is nothing to decide. Returns null on failure so a network
     * problem falls back to asking rather than silently assuming.
     */
    suspend fun alreadyMine(): String? {
        val config = credentialStore.load() ?: return null
        val mine = stateStore.myCar ?: return null
        val myPlate = if (mine == MyCar.WASIL) config.wasilPlate else config.walidPlate
        return runCatching { repository.activePlate() }.getOrNull()
            ?.takeIf { it == myPlate }
    }

    suspend fun claim(
        target: MyCar? = null,
        force: Boolean = false,
        userInitiated: Boolean = false,
        zoneText: String? = null,
    ): GuardedResult {
        val config = credentialStore.load() ?: return GuardedResult.Done(ParkOutcome.NotConfigured)
        val mine = stateStore.myCar ?: return GuardedResult.Done(ParkOutcome.NotConfigured)
        val targetCar = target ?: mine
        val nonTarget = targetCar.other()
        val nonTargetPlate =
            if (nonTarget == MyCar.WASIL) config.wasilPlate else config.walidPlate

        var guardNote: String? = null
        if (!force) {
            try {
                // My own state is local and always fresh; the other's comes from RTDB.
                val nonTargetState =
                    if (nonTarget == mine) localPhoneState() else shared.readOther()
                val verdict = ClaimGuard.evaluate(
                    nonTargetState, nonTargetPlate, repository.activePlate(), nowMs(),
                )
                if (verdict is ClaimGuard.Verdict.Blocked) {
                    return GuardedResult.Blocked(nonTarget.label(), verdict.other)
                }
            } catch (e: Exception) {
                // Guard infrastructure failed. A background claim must not gamble;
                // a human pressing the button proceeds with a visible note.
                if (!userInitiated) return GuardedResult.Done(ParkOutcome.ManualNeeded)
                guardNote = "couldn't check ${nonTarget.label()}'s status"
            }
        }

        val outcome = claimPermit.claim(targetCar, zoneText)
        if (outcome is ParkOutcome.Claimed) {
            if (targetCar == mine && stateStore.parked) stateStore.parkedOutside = true
            runCatching { shared.writePermit(targetCar.key(), outcome.vrn, forced = force) }
        }
        return GuardedResult.Done(outcome, guardNote)
    }

    /** Hand the permit back when I parked free while the other car needs it. */
    suspend fun giveBack(): GiveBackResult {
        val config = credentialStore.load() ?: return GiveBackResult.NothingToDo
        val mine = stateStore.myCar ?: return GiveBackResult.NothingToDo
        val myPlate = if (mine == MyCar.WASIL) config.wasilPlate else config.walidPlate
        val other = mine.other()
        return try {
            val otherState = shared.readOther()
            val needsIt = otherState != null && otherState.parkedOutside &&
                nowMs() - otherState.heartbeatAtMs <= ClaimGuard.STALE_AFTER_MS
            if (!needsIt) return GiveBackResult.NothingToDo
            if (repository.activePlate() != myPlate) return GiveBackResult.NothingToDo
            when (val outcome = claimPermit.claim(other)) {
                is ParkOutcome.Claimed -> {
                    runCatching { shared.writePermit(other.key(), outcome.vrn, forced = false) }
                    GiveBackResult.Given(outcome.vrn)
                }
                ParkOutcome.SwitchFailed -> GiveBackResult.Failed
                else -> GiveBackResult.NothingToDo   // mismatch already warned loudly
            }
        } catch (e: Exception) {
            GiveBackResult.Failed
        }
    }

    private fun localPhoneState(): PhoneState = PhoneState(
        parkedOutside = stateStore.parkedOutside,
        parkedAtMs = stateStore.parkedAtMs,
        heartbeatAtMs = nowMs(),   // local state is by definition fresh
        parkedOutsideKnown = stateStore.parkedOutsideKnown,
    )

    /**
     * Whether a pending decision — read from [ParkStateStore.pendingDecision]
     * by the caller — still describes a real situation. Only BLOCKED and
     * GIVE_BACK depend on facts that can go stale (see
     * [dev.wasil.permit.parking.PendingDecision.stillStands] for the pure
     * rule), so this is the only place those two network reads happen; MANUAL
     * and TAKEOVER are answered without touching the network at all.
     *
     * A failed read (no network, nothing configured) keeps the decision: not
     * being able to confirm a situation ended is not evidence that it did.
     */
    suspend fun stillStands(decision: PendingDecision, nowMs: Long = nowMs()): Boolean {
        val needsFacts = decision is PendingDecision.Blocked || decision is PendingDecision.GiveBack
        val facts = if (needsFacts) runCatching { readDecisionFacts() }.getOrNull() else null
        return decision.stillStands(facts, nowMs)
    }

    private suspend fun readDecisionFacts(): DecisionFacts {
        val config = credentialStore.load() ?: error("not configured")
        val mine = stateStore.myCar ?: error("not configured")
        val other = mine.other()
        return DecisionFacts(
            otherState = shared.readOther(),
            otherPlate = other.plateIn(config),
            myPlate = mine.plateIn(config),
            activeVrn = repository.activePlate(),
        )
    }

    private fun MyCar.plateIn(config: PermitConfig): String =
        if (this == MyCar.WASIL) config.wasilPlate else config.walidPlate
}
