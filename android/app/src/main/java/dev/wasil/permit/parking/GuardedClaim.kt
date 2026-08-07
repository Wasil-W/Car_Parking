package dev.wasil.permit.parking

import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.store.CredentialStore
import dev.wasil.permit.data.store.roster
import dev.wasil.permit.parking.shared.ClaimGuard
import dev.wasil.permit.parking.shared.PhoneState
import dev.wasil.permit.parking.shared.SharedStateStore

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
        val mine = myVehicle() ?: return null
        if (mine.plate.isBlank()) return null
        return runCatching { repository.activePlate() }.getOrNull()
            ?.takeIf { it == mine.plate }
    }

    suspend fun claim(
        target: VehicleId? = null,
        force: Boolean = false,
        userInitiated: Boolean = false,
        zoneText: String? = null,
    ): GuardedResult {
        val roster = credentialStore.load()?.roster
            ?: return GuardedResult.Done(ParkOutcome.NotConfigured)
        val mine = roster.byId(stateStore.thisPhoneDrives)
            ?: return GuardedResult.Done(ParkOutcome.NotConfigured)
        val targetCar = roster.byId(target) ?: mine
        // With more than two cars there is no single "the car that isn't the
        // target", so there is nothing for the guard to check against and the
        // claim proceeds as it would with sharing switched off. That is the
        // same branch a one-car roster already takes, and it is honest: the
        // guard exists to stop stranding *the other* car, and past two the
        // question needs a UI this release deliberately does not build.
        val nonTarget = roster.other(targetCar.id)

        var guardNote: String? = null
        if (!force && nonTarget != null) {
            try {
                // My own state is local and always fresh; the other's comes from RTDB.
                val nonTargetState =
                    if (nonTarget.id == mine.id) localPhoneState() else shared.readOther()
                val verdict = ClaimGuard.evaluate(
                    nonTargetState, nonTarget.plate, repository.activePlate(), nowMs(),
                )
                if (verdict is ClaimGuard.Verdict.Blocked) {
                    return GuardedResult.Blocked(nonTarget.name, verdict.other)
                }
            } catch (e: Exception) {
                // Guard infrastructure failed. A background claim must not gamble;
                // a human pressing the button proceeds with a visible note.
                if (!userInitiated) return GuardedResult.Done(ParkOutcome.ManualNeeded)
                guardNote = "couldn't check ${nonTarget.name}'s status"
            }
        }

        val outcome = claimPermit.claim(targetCar.id, zoneText)
        if (outcome is ParkOutcome.Claimed) {
            if (targetCar.id == mine.id && stateStore.parked) stateStore.parkedOutside = true
            runCatching { shared.writePermit(targetCar.id.value, outcome.vrn, forced = force) }
        }
        return GuardedResult.Done(outcome, guardNote)
    }

    /** Hand the permit back when I parked free while the other car needs it. */
    suspend fun giveBack(): GiveBackResult {
        val roster = credentialStore.load()?.roster ?: return GiveBackResult.NothingToDo
        val mine = roster.byId(stateStore.thisPhoneDrives) ?: return GiveBackResult.NothingToDo
        val other = roster.other(mine.id) ?: return GiveBackResult.NothingToDo
        return try {
            val otherState = shared.readOther()
            val needsIt = otherState != null && otherState.parkedOutside &&
                nowMs() - otherState.heartbeatAtMs <= ClaimGuard.STALE_AFTER_MS
            if (!needsIt) return GiveBackResult.NothingToDo
            if (repository.activePlate() != mine.plate) return GiveBackResult.NothingToDo
            when (val outcome = claimPermit.claim(other.id)) {
                is ParkOutcome.Claimed -> {
                    runCatching { shared.writePermit(other.id.value, outcome.vrn, forced = false) }
                    GiveBackResult.Given(outcome.vrn)
                }
                ParkOutcome.SwitchFailed -> GiveBackResult.Failed
                else -> GiveBackResult.NothingToDo   // mismatch already warned loudly
            }
        } catch (e: Exception) {
            GiveBackResult.Failed
        }
    }

    /** This phone's car, or null when there is no permit or no answer to that yet. */
    fun myVehicle(): Vehicle? =
        credentialStore.load()?.roster?.byId(stateStore.thisPhoneDrives)

    /**
     * The cars that exist. The seed when no permit account is stored, because a
     * phone with no permit still drives a car and still has to be able to name
     * which one it is.
     */
    fun roster(): Roster = credentialStore.roster()

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
        val roster = credentialStore.load()?.roster ?: error("not configured")
        val mine = roster.byId(stateStore.thisPhoneDrives) ?: error("not configured")
        val other = roster.other(mine.id) ?: error("no other car")
        return DecisionFacts(
            otherState = shared.readOther(),
            otherPlate = other.plate,
            myPlate = mine.plate,
            activeVrn = repository.activePlate(),
        )
    }
}
