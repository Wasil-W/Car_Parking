package dev.wasil.permit.parking.shared

import java.security.MessageDigest

/**
 * Both phones derive the same RTDB room from the permit-site username they
 * already share. 128 bits of the SHA-256 keeps the path unguessable.
 */
fun roomIdFor(username: String): String {
    val input = "permit-room:v1:" + username.trim().lowercase()
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(32)
}
