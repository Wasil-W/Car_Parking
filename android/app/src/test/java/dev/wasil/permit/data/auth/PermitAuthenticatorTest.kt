package dev.wasil.permit.data.auth

import dev.wasil.permit.data.store.FakeCredentialStore
import dev.wasil.permit.data.store.PermitConfig
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
    private val credentials = FakeCredentialStore(
        PermitConfig("user", "pass", "RH950F", "XX123Y")
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = TokenStore()
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
