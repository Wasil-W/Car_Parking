package dev.wasil.permit.parking

import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.store.CredentialStore
import dev.wasil.permit.parking.shared.ClaimGuard
import dev.wasil.permit.parking.shared.PhoneState
import dev.wasil.permit.parking.shared.SharedStateStore

fun MyCar.other(): MyCar = if (this == MyCar.WASIL) MyCar.WALID else MyCar.WASIL
fun MyCar.label(): String = if (this == MyCar.WASIL) "Wasil" else "Walid"
fun MyCar.key(): String = name.lowercase()

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
    )
}
