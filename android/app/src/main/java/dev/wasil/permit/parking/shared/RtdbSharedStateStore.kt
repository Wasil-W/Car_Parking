package dev.wasil.permit.parking.shared

import dev.wasil.permit.data.api.PermitJson
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Firebase Realtime Database over plain REST: `<base>/rooms/<room>/<node>.json`. */
class RtdbSharedStateStore(
    baseUrl: HttpUrl,
    private val room: String,
    private val me: String,
    private val other: String,
    private val client: OkHttpClient = OkHttpClient(),
) : SharedStateStore {

    companion object {
        private val SERVER_TIMESTAMP = JsonObject(mapOf(".sv" to JsonPrimitive("timestamp")))
        private val JSON = "application/json".toMediaType()
    }

    private val base = baseUrl.toString().trimEnd('/')
    override val configured = true

    private fun url(path: String) = "$base/rooms/$room/$path.json"

    private suspend fun http(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("RTDB HTTP ${response.code}")
            response.body?.string() ?: "null"
        }
    }

    override suspend fun readOther(): PhoneState? {
        val body = http(Request.Builder().url(url("phones/$other")).get().build())
        if (body == "null") return null
        return PermitJson.decodeFromString(PhoneState.serializer(), body)
    }

    override suspend fun writeMine(state: PhoneState) {
        val obj = PermitJson.encodeToJsonElement(PhoneState.serializer(), state).jsonObject
        val body = JsonObject(obj + ("heartbeatAtMs" to SERVER_TIMESTAMP)).toString()
        http(Request.Builder().url(url("phones/$me")).put(body.toRequestBody(JSON)).build())
    }

    override suspend fun heartbeat() {
        val body = JsonObject(mapOf("heartbeatAtMs" to SERVER_TIMESTAMP)).toString()
        http(Request.Builder().url(url("phones/$me")).patch(body.toRequestBody(JSON)).build())
    }

    override suspend fun readPermit(): PermitClaim? {
        val body = http(Request.Builder().url(url("permit")).get().build())
        if (body == "null") return null
        return PermitJson.decodeFromString(PermitClaim.serializer(), body)
    }

    override suspend fun writePermit(holder: String, vrn: String, forced: Boolean) {
        val body = buildJsonObject {
            put("holder", JsonPrimitive(holder))
            put("vrn", JsonPrimitive(vrn))
            put("claimedAtMs", SERVER_TIMESTAMP)
            put("forced", JsonPrimitive(forced))
        }.toString()
        http(Request.Builder().url(url("permit")).put(body.toRequestBody(JSON)).build())
    }
}
