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
            """{"parkedOutside":true,"parkedAtMs":100,"heartbeatAtMs":200}"""))
        val other = store().readOther()!!
        assertTrue(other.parkedOutside)
        assertEquals(100L, other.parkedAtMs)
        assertEquals(200L, other.heartbeatAtMs)
    }

    /**
     * The other phone may still be on any older version, publishing
     * coordinates, a zone code or a rate. The upgrade is not coordinated —
     * both brothers update whenever they happen to — so an old node must keep
     * decoding, and everything dropped since must fall on the floor rather
     * than reappear anywhere. This compiles at all only because those fields
     * no longer exist to be read into.
     */
    @Test
    fun `a node from an older phone decodes and drops everything since removed`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"parkedOutside":true,"lat":52.37,"lng":4.89,"accuracyM":12.0,""" +
                """"zoneCode":"T13B","rateCentsPerHour":805,"heartbeatAtMs":200}"""))
        val other = store().readOther()!!
        assertTrue(other.parkedOutside)
        assertEquals(200L, other.heartbeatAtMs)
    }

    @Test
    fun `writeMine PUTs my node with a server timestamp heartbeat`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        store().writeMine(PhoneState(parkedOutside = true, parkedAtMs = 100))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/rooms/room1/phones/wasil.json", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"parkedOutside\":true"))

        assertTrue(body.contains("\"heartbeatAtMs\":{\".sv\":\"timestamp\"}"))
    }

    /**
     * The privacy guarantee, asserted at the only place bytes actually leave the
     * phone. If a coordinate or a zone code is ever reintroduced to
     * [PhoneState], this fails regardless of which caller put it there.
     *
     * The zone code is on the list as of v0.6.3: an Amsterdam tariff area runs
     * up to 3 km across, so publishing one narrowed the other car to a
     * district. Coarse is not the same as private.
     */
    @Test
    fun `writeMine never puts a position or a zone on the wire`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        store().writeMine(PhoneState(parkedOutside = true, parkedAtMs = 100))
        val body = server.takeRequest().body.readUtf8()
        listOf(
            "lat", "lng", "accuracy", "longitude", "latitude", "52.", "4.8",
            "zone", "rate",
        ).forEach {
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
