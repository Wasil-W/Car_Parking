package dev.wasil.permit.data

import dev.wasil.permit.data.api.ActivateRequest
import dev.wasil.permit.data.api.ApiConstants
import dev.wasil.permit.data.api.PermitApi

class PermitRepository(private val api: PermitApi) {

    suspend fun activePlate(): String? =
        api.getClientProduct(ApiConstants.PRODUCT_ID)
            .vrns.firstOrNull { it.hasParkingSession }?.vrn

    sealed interface SwitchResult {
        data class Confirmed(val activeVrn: String) : SwitchResult
        data class Mismatch(val serverActiveVrn: String?) : SwitchResult
    }

    /**
     * Never trust the write: a 200 from activate is not proof the permit moved.
     * Always re-read state and compare (a wrong permit is a parking fine).
     */
    suspend fun switchTo(vrn: String): SwitchResult {
        api.activate(ActivateRequest(ApiConstants.PRODUCT_ID, vrn))
        val nowActive = activePlate()
        return if (nowActive == vrn) SwitchResult.Confirmed(vrn)
        else SwitchResult.Mismatch(nowActive)
    }
}
