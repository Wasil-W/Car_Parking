package dev.wasil.permit.parking

import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.store.CredentialStore

/** Shared by auto-claim and the notification "Claim"/"Retry" actions. */
class ClaimPermit(
    private val repository: PermitRepository,
    private val credentialStore: CredentialStore,
    private val stateStore: ParkStateStore,
    private val notifier: ParkNotifier,
) {
    suspend fun claim(target: MyCar? = null, zoneText: String? = null): ParkOutcome {
        val config = credentialStore.load() ?: return ParkOutcome.NotConfigured
        val car = target ?: stateStore.myCar ?: return ParkOutcome.NotConfigured
        val (label, plate) = when (car) {
            MyCar.WASIL -> "Wasil" to config.wasilPlate
            MyCar.WALID -> "Walid" to config.walidPlate
        }
        return try {
            when (val result = repository.switchTo(plate)) {
                is PermitRepository.SwitchResult.Confirmed -> {
                    notifier.statusPermitOn(label, result.activeVrn, zoneText)
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
