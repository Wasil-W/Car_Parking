package dev.wasil.permit.data.api

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PermitApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PermitApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .addInterceptor(BrowserHeadersInterceptor())
            .build()
        api = buildPermitApi(server.url("/api/"), client)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `login posts credentials to ssp login_check`() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"tok1"}"""))
        val resp = api.login(LoginRequest("user", "pass"))
        assertEquals("tok1", resp.token)
        val recorded = server.takeRequest()
        assertEquals("/api/ssp/login_check", recorded.path)
        assertEquals("POST", recorded.method)
        assertTrue(recorded.body.readUtf8().contains("\"username\":\"user\""))
    }

    @Test
    fun `every request carries the browser headers the API requires`() = runTest {
        server.enqueue(MockResponse().setBody("""{"vrns":[]}"""))
        api.getClientProduct(ApiConstants.PRODUCT_ID)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/client_product/5807976", recorded.path)
        assertEquals("https://parkeervergunningen.amsterdam.nl", recorded.getHeader("origin"))
        assertEquals("https://parkeervergunningen.amsterdam.nl/", recorded.getHeader("referer"))
        assertTrue(recorded.getHeader("user-agent")!!.contains("Chrome"))
        assertEquals("application/json, text/plain, */*", recorded.getHeader("accept"))
    }

    @Test
    fun `activate posts product id and vrn`() = runTest {
        server.enqueue(MockResponse().setBody("""{"parking_session_id":42}"""))
        val resp = api.activate(ActivateRequest(ApiConstants.PRODUCT_ID, "RH950F"))
        assertEquals(42L, resp.parkingSessionId)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/ssp/parking_session/activate", recorded.path)
        assertEquals("""{"client_product_id":5807976,"vrn":"RH950F"}""", recorded.body.readUtf8())
    }
}
