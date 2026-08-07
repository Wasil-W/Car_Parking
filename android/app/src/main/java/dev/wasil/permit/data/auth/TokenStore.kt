package dev.wasil.permit.data.auth

/** In-memory only: the JWT lives 1 hour, persisting it buys nothing. */
class TokenStore {
    @Volatile
    var token: String? = null

    /**
     * Throws the session away, so the next request has to sign in again.
     *
     * Exists because "am I signed in?" and "are these credentials right?" are
     * different questions, and until v0.6.8 the app could only answer the first.
     * A token from an earlier sign-in outlives a change of credentials — it is
     * held here, in memory, keyed to nothing — so typing a *wrong* username and
     * password and pressing "Sign in and find my cars" still returned the cars,
     * fetched against the session that was already open. Reported from real use
     * on 2026-08-08: *"i entered incorrect credentials and it still showed my
     * cars."*
     *
     * Clearing the token first is what turns that button into an actual sign-in:
     * with nothing to attach, the request 401s, [PermitAuthenticator] logs in
     * with whatever was just typed, and wrong credentials fail there — visibly.
     */
    fun clear() {
        token = null
    }
}
