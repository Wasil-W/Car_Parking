package dev.wasil.permit.parking.shared

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class RtdbSharedStateStoreTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun store() = RtdbSharedStateStore(
        baseUrl = server.url("/"), room = "room1", me = "wasil", other = "walid",
    )

    @Test
    fun `absent node reads as null`() = runTest {
        server.enqueue(MockResponse().setBody("null"))
        assertNull(store().readOther())
        assertEquals("/rooms/room1/phones/walid.json", server.takeRequest().path)
    }

    @Test
    fun `other phone state parses`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"parkedOutside":true,"lat":52.37,"lng":4.89,"parkedAtMs":100,"heartbeatAtMs":200}"""))
        val other = store().readOther()!!
        assertTrue(other.parkedOutside)
        assertEquals(200L, other.heartbeatAtMs)
    }

    @Test
    fun `writeMine PUTs my node with a server timestamp heartbeat`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        store().writeMine(PhoneState(parkedOutside = true, lat = 52.37, lng = 4.89, parkedAtMs = 100))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/rooms/room1/phones/wasil.json", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"parkedOutside\":true"))
        assertTrue(body.contains("\"heartbeatAtMs\":{\".sv\":\"timestamp\"}"))
    }

    @Test
    fun `heartbeat PATCHes only the timestamp`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        store().heartbeat()
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/rooms/room1/phones/wasil.json", request.path)
        assertEquals("""{"heartbeatAtMs":{".sv":"timestamp"}}""", request.body.readUtf8())
    }

    @Test
    fun `writePermit PUTs holder vrn forced and server time`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        store().writePermit("walid", "XX123Y", forced = true)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/rooms/room1/permit.json", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"holder\":\"walid\""))
        assertTrue(body.contains("\"forced\":true"))
        assertTrue(body.contains("\"claimedAtMs\":{\".sv\":\"timestamp\"}"))
    }

    @Test
    fun `http error surfaces as IOException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            store().readOther()
            fail("expected IOException")
        } catch (expected: IOException) {
        }
    }
}
