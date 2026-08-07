package dev.wasil.permit.data.auth

import dev.wasil.permit.data.store.FakeCredentialStore
import dev.wasil.permit.data.store.PermitConfig
import dev.wasil.permit.parking.legacyRoster
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PermitAuthenticatorTest {
    private lateinit var server: MockWebServer
    private lateinit var tokenStore: TokenStore
    private lateinit var credentials: FakeCredentialStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = TokenStore()
        credentials = FakeCredentialStore(
            PermitConfig("user", "pass", legacyRoster("RH950F", "XX123Y"))
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client() =
        buildAuthenticatedClient(server.url("/api/"), tokenStore, credentials)

    @Test
    fun `expired token triggers relogin and retries original request once`() {
        tokenStore.token = "stale"
        server.enqueue(MockResponse().setResponseCode(401))                    // original call
        server.enqueue(MockResponse().setBody("""{"token":"fresh"}"""))       // re-login
        server.enqueue(MockResponse().setBody("""{"vrns":[]}"""))             // retried call

        val resp = client().newCall(
            Request.Builder().url(server.url("/api/v1/client_product/5807976")).build()
        ).execute()

        assertEquals(200, resp.code)
        assertEquals("Bearer stale", server.takeRequest().getHeader("Authorization"))
        val loginReq = server.takeRequest()
        assertEquals("/api/ssp/login_check", loginReq.path)
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
        assertEquals("fresh", tokenStore.token)
    }

    @Test
    fun `no token yet - first 401 logs in transparently`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"token":"first"}"""))
        server.enqueue(MockResponse().setBody("""{"vrns":[]}"""))

        val resp = client().newCall(
            Request.Builder().url(server.url("/api/v1/client_product/5807976")).build()
        ).execute()

        assertEquals(200, resp.code)
        assertEquals("first", tokenStore.token)
    }

    @Test
    fun `gives up after one retry instead of looping`() {
        tokenStore.token = "stale"
        server.enqueue(MockResponse().setResponseCode(401))                    // original
        server.enqueue(MockResponse().setBody("""{"token":"fresh"}"""))       // login ok
        server.enqueue(MockResponse().setResponseCode(401))                    // retry also 401

        val resp = client().newCall(
            Request.Builder().url(server.url("/api/v1/client_product/5807976")).build()
        ).execute()

        assertEquals(401, resp.code)
        assertEquals(3, server.requestCount) // no infinite login loop
    }

    @Test
    fun `failed login on the login endpoint itself is not retried`() {
        server.enqueue(MockResponse().setResponseCode(401)) // bad credentials

        val resp = client().newCall(
            Request.Builder().url(server.url("/api/ssp/login_check")).build()
        ).execute()

        assertEquals(401, resp.code)
        assertEquals(1, server.requestCount)
    }

    // --- the wrong password that looked like success (v0.6.8) ---

    /**
     * The defect, reproduced at the level it actually happened.
     *
     * Reported 2026-08-08: *"i entered incorrect credentials and it still showed
     * my cars."* This is why. A token from an earlier sign-in is held in memory
     * with nothing tying it to the account it was issued for, so a request made
     * straight after changing the stored credentials goes out on the **old**
     * session and the site answers about the **old** account — 200, with
     * somebody's real plates. The new username and password are never sent
     * anywhere, so nothing can reject them.
     *
     * Note what the assertions say: not one request carries the new credentials,
     * and the call succeeds. That is the whole bug in two lines.
     */
    @Test
    fun `a live token answers for the previous account, whatever the credentials say`() {
        tokenStore.token = "from-the-previous-sign-in"
        server.enqueue(MockResponse().setBody("""{"vrns":[{"vrn":"RH950F","hasParkingSession":true}]}"""))

        credentials.config = PermitConfig("someone-else", "wrong", legacyRoster("RH950F", "XX123Y"))
        val resp = client().newCall(
            Request.Builder().url(server.url("/api/v1/client_product/5807976")).build()
        ).execute()

        assertEquals(200, resp.code)
        assertEquals(1, server.requestCount)
        assertEquals(
            "the stale session, not a sign-in",
            "Bearer from-the-previous-sign-in",
            server.takeRequest().getHeader("Authorization"),
        )
    }

    /**
     * And the fix. Clearing the token is what turns "fetch my cars" into a
     * sign-in: with nothing to attach, the request 401s, the authenticator has
     * to log in with what was just typed, and the site gets its say.
     *
     * `MainViewModel.findPlates` calls [TokenStore.clear] immediately before the
     * request for exactly this reason.
     */
    @Test
    fun `clearing the token first makes wrong credentials fail where they should`() {
        tokenStore.token = "from-the-previous-sign-in"
        tokenStore.clear()
        server.enqueue(MockResponse().setResponseCode(401))  // no token attached
        server.enqueue(MockResponse().setResponseCode(401))  // the login is refused

        val resp = client().newCall(
            Request.Builder().url(server.url("/api/v1/client_product/5807976")).build()
        ).execute()

        assertEquals("the caller must see a refusal, not somebody's cars", 401, resp.code)
        assertNull("a refused login must not leave a session behind", tokenStore.token)
        assertNull(server.takeRequest().getHeader("Authorization"))
        assertEquals("/api/ssp/login_check", server.takeRequest().path)
    }

    @Test
    fun `clearing the token still signs in normally when the credentials are right`() {
        tokenStore.clear()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"token":"fresh"}"""))
        server.enqueue(MockResponse().setBody("""{"vrns":[]}"""))

        val resp = client().newCall(
            Request.Builder().url(server.url("/api/v1/client_product/5807976")).build()
        ).execute()

        assertEquals(200, resp.code)
        assertEquals("fresh", tokenStore.token)
    }

    @Test
    fun `no stored credentials - gives up without retry`() {
        val emptyStore = FakeCredentialStore(null)
        val c = buildAuthenticatedClient(server.url("/api/"), tokenStore, emptyStore)
        server.enqueue(MockResponse().setResponseCode(401))

        val resp = c.newCall(
            Request.Builder().url(server.url("/api/v1/client_product/5807976")).build()
        ).execute()

        assertEquals(401, resp.code)
        assertEquals(1, server.requestCount)
        assertNull(tokenStore.token)
    }
}
