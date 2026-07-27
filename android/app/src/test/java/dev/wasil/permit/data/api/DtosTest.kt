package dev.wasil.permit.data.api

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

class DtosTest {

    @Test
    fun `client product response parses vrns and ignores unknown keys`() {
        // Shape mirrors the real API: a big object, we only care about vrns.
        val json = """
            {
              "id": 5807976,
              "permit": {"geo_json": {"type": "MultiPolygon"}},
              "validity": {"ended_at": "2026-11-19"},
              "vrns": [
                {"id": 1, "vrn": "RH950F", "has_parking_session": false, "extra": null},
                {"id": 2, "vrn": "XX123Y", "has_parking_session": true}
              ]
            }
        """.trimIndent()
        val parsed = PermitJson.decodeFromString<ClientProductResponse>(json)
        assertEquals(listOf("RH950F", "XX123Y"), parsed.vrns.map { it.vrn })
        assertEquals(listOf(false, true), parsed.vrns.map { it.hasParkingSession })
    }

    @Test
    fun `login response parses token`() {
        val parsed = PermitJson.decodeFromString<LoginResponse>("""{"token":"abc.def.ghi"}""")
        assertEquals("abc.def.ghi", parsed.token)
    }

    @Test
    fun `activate request serializes with snake_case keys`() {
        val body = PermitJson.encodeToString(ActivateRequest(clientProductId = 5807976, vrn = "RH950F"))
        assertEquals("""{"client_product_id":5807976,"vrn":"RH950F"}""", body)
    }

    @Test
    fun `activate response parses session id`() {
        val parsed = PermitJson.decodeFromString<ActivateResponse>("""{"parking_session_id":987654}""")
        assertEquals(987654L, parsed.parkingSessionId)
    }
}
