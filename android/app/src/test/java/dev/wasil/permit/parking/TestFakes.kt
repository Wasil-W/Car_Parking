package dev.wasil.permit.parking

import dev.wasil.permit.data.api.ActivateRequest
import dev.wasil.permit.data.api.ActivateResponse
import dev.wasil.permit.data.api.ClientProductResponse
import dev.wasil.permit.data.api.LoginRequest
import dev.wasil.permit.data.api.LoginResponse
import dev.wasil.permit.data.api.PermitApi
import dev.wasil.permit.data.api.VrnEntry
import dev.wasil.permit.parking.shared.PermitClaim
import dev.wasil.permit.parking.shared.PhoneState
import dev.wasil.permit.parking.shared.SharedStateStore
import java.io.IOException

class RecordingParkNotifier : ParkNotifier {
    val calls = mutableListOf<String>()
    override fun statusPermitOn(label: String, vrn: String, zoneText: String?) {
        calls += "status:$label:$vrn" + (zoneText?.let { ":$it" } ?: "")
    }
    override fun statusParkedNoClaim(reason: String) { calls += "noclaim:$reason" }
    override fun askManualDecision() { calls += "manual" }
    override fun askGiveBack(otherLabel: String) { calls += "askgiveback:$otherLabel" }
    override fun blockedByOther(otherLabel: String, parkedAtMs: Long, heartbeatAtMs: Long) {
        calls += "blocked:$otherLabel"
    }
    override fun takeover(byLabel: String) { calls += "takeover:$byLabel" }
    override fun switchFailed(reason: String?) { calls += "failed" }
    override fun mismatchWarning(serverVrn: String?) { calls += "mismatch:$serverVrn" }
    override fun eventNote(text: String) { calls += "note:$text" }
}

/** In-memory permit API: the active plate switches unless [fail] is set. */
class SwitchApi(var active: String? = "XX123Y", var fail: Boolean = false) : PermitApi {
    override suspend fun login(body: LoginRequest) = LoginResponse("tok")
    override suspend fun getClientProduct(productId: Long): ClientProductResponse =
        ClientProductResponse(listOf(
            VrnEntry("RH950F", active == "RH950F"), VrnEntry("XX123Y", active == "XX123Y")))
    override suspend fun activate(body: ActivateRequest): ActivateResponse {
        if (fail) throw IOException("offline")
        active = body.vrn
        return ActivateResponse(1)
    }
}

class FakeSharedStateStore(
    var other: PhoneState? = null,
    var permit: PermitClaim? = null,
    var throwOnRead: Boolean = false,
    override val configured: Boolean = true,
) : SharedStateStore {
    val myWrites = mutableListOf<PhoneState>()
    val permitWrites = mutableListOf<PermitClaim>()
    var heartbeats = 0

    override suspend fun readOther(): PhoneState? {
        if (throwOnRead) throw IOException("rtdb down")
        return other
    }
    override suspend fun writeMine(state: PhoneState) { myWrites += state }
    override suspend fun heartbeat() { heartbeats++ }
    override suspend fun readPermit(): PermitClaim? {
        if (throwOnRead) throw IOException("rtdb down")
        return permit
    }
    override suspend fun writePermit(holder: String, vrn: String, forced: Boolean) {
        permitWrites += PermitClaim(holder, vrn, claimedAtMs = 0, forced = forced)
    }
}
