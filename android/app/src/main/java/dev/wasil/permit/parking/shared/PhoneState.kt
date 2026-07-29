package dev.wasil.permit.parking.shared

import kotlinx.serialization.Serializable

@Serializable
data class PhoneState(
    val parkedOutside: Boolean = false,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Double? = null,
    val zoneCode: String? = null,
    val parkedAtMs: Long = 0,
    val heartbeatAtMs: Long = 0,
)

@Serializable
data class PermitClaim(
    val holder: String,
    val vrn: String,
    val claimedAtMs: Long = 0,
    val forced: Boolean = false,
)
