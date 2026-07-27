package dev.wasil.permit.data.auth

/** In-memory only: the JWT lives 1 hour, persisting it buys nothing. */
class TokenStore {
    @Volatile
    var token: String? = null
}
