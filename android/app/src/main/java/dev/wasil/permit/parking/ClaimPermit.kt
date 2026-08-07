package dev.wasil.permit.parking

import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.store.CredentialStore

/** Shared by auto-claim and the notification "Claim"/"Retry" actions. */
class ClaimPermit(
    private val repository: PermitRepository,
    private val credentialStore: CredentialStore,
    private val stateStore: ParkStateStore,
    private val notifier: ParkNotifier,
    /**
     * Kept up to date with what the permit is actually doing, because detection
     * writes what was true when it *finished* and a claim can land minutes
     * later. Without this, a paid park claimed from the notification would sit
     * in history badged "Unsettled" for ever — quietly wrong, which is the one
     * thing a log must not be. Null wherever no log is at hand; recording is
     * never allowed to be a reason a claim fails.
     */
    private val parkLog: ParkLogStore? = null,
) {
    suspend fun claim(target: VehicleId? = null, zoneText: String? = null): ParkOutcome {
        val roster = credentialStore.load()?.roster ?: return ParkOutcome.NotConfigured
        val car = roster.byId(target ?: stateStore.thisPhoneDrives)
            ?: return ParkOutcome.NotConfigured
        // A car with no plate cannot hold the permit, and asking the site to
        // activate "" would be a 4xx dressed up as a switch failure.
        if (car.plate.isBlank()) return ParkOutcome.NotConfigured
        val identitySlot = roster.identitySlotOf(car.id)
        return try {
            when (val result = repository.switchTo(car.plate)) {
                is PermitRepository.SwitchResult.Confirmed -> {
                    notifier.statusPermitOn(car.name, identitySlot, result.activeVrn, zoneText)
                    // Only while this phone is actually parked: a switch made
                    // from the sofa belongs to no park at all, and re-badging
                    // the last one would credit the permit to a spot it never
                    // covered.
                    if (stateStore.parked) {
                        if (car.id == stateStore.thisPhoneDrives) {
                            parkLog?.settleOpen(Settlement.PERMIT, car.id)
                        } else {
                            // Handed to the other car while still parked here,
                            // so whatever this spot demands is no longer met by
                            // the permit. What that leaves is decided by what we
                            // knew about the spot, not assumed.
                            parkLog?.uncoverOpen()
                        }
                    }
                    ParkOutcome.Claimed(result.activeVrn)
                }
                is PermitRepository.SwitchResult.Mismatch -> {
                    notifier.mismatchWarning(result.serverActiveVrn)
                    ParkOutcome.MismatchDetected(result.serverActiveVrn)
                }
            }
        } catch (e: Exception) {
            notifier.switchFailed(e.message)
            ParkOutcome.SwitchFailed
        }
    }
}
