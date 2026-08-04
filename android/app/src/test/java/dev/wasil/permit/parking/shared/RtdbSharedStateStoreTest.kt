package dev.wasil.permit.parking.shared

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            """{"parkedOutside":true,"rateCentsPerHour":301,"parkedAtMs":100,"heartbeatAtMs":200}"""))
        val other = store().readOther()!!
        assertTrue(other.parkedOutside)
        assertEquals(301, other.rateCentsPerHour)
        assertEquals(200L, other.heartbeatAtMs)
    }

    /**
     * The other phone may still be on v0.6.2 and still publishing coordinates.
     * Its node must keep decoding — the upgrade is not coordinated, both
     * brothers update whenever they update — and the coordinates must simply
     * fall on the floor rather than reappear anywhere.
     */
    @Test
    fun `a node from an older phone still carrying coordinates decodes without them`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"parkedOutside":true,"lat":52.37,"lng":4.89,"accuracyM":12.0,"heartbeatAtMs":200}"""))
        val other = store().readOther()!!
        assertTrue(other.parkedOutside)
        assertEquals(200L, other.heartbeatAtMs)
        // Nothing on PhoneState can hold them any more; this compiles only
        // because the fields are gone.
        assertNull(other.rateCentsPerHour)
    }

    /** A missing rate reads as unknown, not as free — see PhoneState. */
    @Test
    fun `a node written before rates existed reads as an unknown rate`() = runTest {
        server.enqueue(MockResponse().setBody("""{"parkedOutside":true,"heartbeatAtMs":200}"""))
        assertNull(store().readOther()!!.rateCentsPerHour)
    }

    @Test
    fun `writeMine PUTs my node with a server timestamp heartbeat`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        store().writeMine(PhoneState(
            parkedOutside = true, rateCentsPerHour = 805, parkedAtMs = 100,
        ))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/rooms/room1/phones/wasil.json", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"parkedOutside\":true"))
        assertTrue(body.contains("\"rateCentsPerHour\":805"))
        assertTrue(body.contains("\"heartbeatAtMs\":{\".sv\":\"timestamp\"}"))
    }

    /**
     * The v0.6.3 privacy guarantee, asserted at the only place bytes actually
     * leave the phone. If a coordinate is ever reintroduced to [PhoneState],
     * this fails regardless of which caller put it there.
     */
    @Test
    fun `writeMine never puts a coordinate on the wire`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        store().writeMine(PhoneState(
            parkedOutside = true, rateCentsPerHour = 805, zoneCode = "T11V", parkedAtMs = 100,
        ))
        val body = server.takeRequest().body.readUtf8()
        listOf("lat", "lng", "accuracy", "longitude", "latitude", "52.", "4.8").forEach {
            assertFalse("published body leaked \"$it\": $body", body.contains(it, ignoreCase = true))
        }
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
