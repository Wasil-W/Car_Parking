package dev.wasil.permit.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared JSON config: the API returns far more fields than we model. */
val PermitJson: Json = Json { ignoreUnknownKeys = true }

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String)

@Serializable
data class VrnEntry(
    val vrn: String,
    @SerialName("has_parking_session") val hasParkingSession: Boolean,
)

@Serializable
data class ClientProductResponse(
    val vrns: List<VrnEntry> = emptyList(),
    /**
     * What the account calls this product — the permit's own name, if it is in
     * there under this key.
     *
     * The endpoint is `getClientProduct`, and the *product* is the permit, so
     * its name and type are very probably in this same JSON and have been
     * discarded unparsed since v0.1. That is a reasonable expectation and it is
     * not a verified one: **nobody has ever read a real body from this
     * endpoint**, and asking for one needs live credentials this repo does not
     * carry. [ClientProductLogInterceptor] prints the whole object in a debug
     * build so the real key can be read off logcat and this line corrected.
     *
     * Guessing `name` costs nothing in either direction. `PermitJson` ignores
     * unknown keys, so a wrong guess parses to null; null is
     * [PermitKind.UNKNOWN]; and UNKNOWN is treated as the restricted kind. The
     * app is therefore never told a permit works somewhere it might not, which
     * is the only direction that matters.
     */
    val name: String? = null,
)

@Serializable
data class ActivateRequest(
    @SerialName("client_product_id") val clientProductId: Long,
    val vrn: String,
)

@Serializable
data class ActivateResponse(
    @SerialName("parking_session_id") val parkingSessionId: Long,
)
