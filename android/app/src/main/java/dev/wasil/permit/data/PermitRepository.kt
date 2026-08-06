package dev.wasil.permit.data

import dev.wasil.permit.data.api.ActivateRequest
import dev.wasil.permit.data.api.ApiConstants
import dev.wasil.permit.data.api.PermitApi

class PermitRepository(private val api: PermitApi) {

    suspend fun activePlate(): String? =
        api.getClientProduct(ApiConstants.PRODUCT_ID)
            .vrns.firstOrNull { it.hasParkingSession }?.vrn

    /**
     * Every plate the permit account covers.
     *
     * The same call [activePlate] has made since v0.1 — it read this list,
     * picked the one holding a session and threw the rest away. Meanwhile setup
     * asked the user to type those plates in by hand. Wasil, on seeing the form:
     * *"instead of making myself put the licences show them to me."* Quite
     * right; the answer was already arriving.
     */
    suspend fun plates(): List<String> =
        api.getClientProduct(ApiConstants.PRODUCT_ID).vrns.map { it.vrn }

    sealed interface SwitchResult {
        data class Confirmed(val activeVrn: String) : SwitchResult
        data class Mismatch(val serverActiveVrn: String?) : SwitchResult
    }

    /**
     * Never trust the write: a 200 from activate is not proof the permit moved.
     * Always re-read state and compare (a wrong permit is a parking fine).
     *
     * Idempotent by design. Activating a plate that already holds the session
     * makes the API reject the call, which used to surface as `SwitchFailed`
     * and put `ClaimPermitWorker` into an endless retry — so parking a second
     * time while already holding the permit produced a failure notification on
     * every attempt. Reading first turns that case into the success it always
     * was: the permit is on the right car, which is all the caller asked for.
     *
     * The extra GET is free in practice — the verification read below was
     * always going to happen anyway.
     */
    suspend fun switchTo(vrn: String): SwitchResult {
        if (activePlate() == vrn) return SwitchResult.Confirmed(vrn)
        api.activate(ActivateRequest(ApiConstants.PRODUCT_ID, vrn))
        val nowActive = activePlate()
        return if (nowActive == vrn) SwitchResult.Confirmed(vrn)
        else SwitchResult.Mismatch(nowActive)
    }
}
