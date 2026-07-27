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
data class ClientProductResponse(val vrns: List<VrnEntry>)

@Serializable
data class ActivateRequest(
    @SerialName("client_product_id") val clientProductId: Long,
    val vrn: String,
)

@Serializable
data class ActivateResponse(
    @SerialName("parking_session_id") val parkingSessionId: Long,
)
