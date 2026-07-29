# Phase 3: Shared Parked State + Collision Guard — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the two phones share parked state via Firebase RTDB (REST, no SDK) so a permit claim never silently strands the other brother's parked car; add home zone, Amsterdam tariff-area polygons, give-back, a personal map, and fix the five confirmed Phase 2 field bugs.

**Architecture:** Pure decision core (ClaimGuard, ZoneResolver, GuardedClaim) with JVM tests; thin Android shell (WorkManager workers with network constraints, receivers, notifications). Firebase Realtime Database accessed over plain OkHttp REST — testable with MockWebServer like the rest of the app.

**Tech Stack:** Kotlin, Compose, WorkManager, OkHttp + kotlinx-serialization, osmdroid 6.1.20, Firebase RTDB REST API.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-29-phase3-shared-state-design.md`.
- Work on branch `phase3-shared-state`; repo root `C:\Users\wasil\Dev\Car_Parking`.
- Test command (from `android\`): `.\gradlew.bat :app:testDebugUnitTest`. Full build: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`. JDK comes from `org.gradle.java.home` in `android/gradle.properties` — do not change it.
- The 47 existing unit tests must stay green after every task.
- No Firebase SDK, no `google-services.json`. The database URL is user-entered in Settings and **never committed** (repo + APKs are public).
- Staleness cutoff: `6 * 60 * 60 * 1000` ms. Heartbeat period: 15 minutes. Home-zone radius: default 60 m, range 30–200 m.
- Holder keys in shared state are `"wasil"`/`"walid"` (`MyCar.name.lowercase()`).
- Notification copy: exact strings given in Task 8/10 — do not improvise.
- New package roots: `dev.wasil.permit.parking.shared` and `dev.wasil.permit.parking.zones`.

---

### Task 1: Store fields, dependency, manifest prep

**Files:**
- Modify: `android/gradle/libs.versions.toml`
- Modify: `android/app/build.gradle.kts`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/java/dev/wasil/permit/parking/ParkStateStore.kt`
- Modify: `android/app/src/main/java/dev/wasil/permit/parking/PrefsParkStateStore.kt`
- Modify: `android/app/src/test/java/dev/wasil/permit/parking/FakeParkStateStore.kt`

**Interfaces:**
- Consumes: existing `ParkStateStore`, `FreeZone`, `GeoPoint`.
- Produces: `ParkStateStore` gains `var parkedOutside: Boolean`, `var parkedAtMs: Long`, `var lastZoneCode: String?`, `var homeZone: FreeZone?`, `var syncUrl: String?`, `var lastAlertedClaimMs: Long`. Library alias `libs.osmdroid`.

- [ ] **Step 1: Add osmdroid to the version catalog**

In `android/gradle/libs.versions.toml` add under `[versions]`:

```toml
osmdroid = "6.1.20"
```

and under `[libraries]`:

```toml
osmdroid = { group = "org.osmdroid", name = "osmdroid-android", version.ref = "osmdroid" }
```

- [ ] **Step 2: Add the dependency**

In `android/app/build.gradle.kts` `dependencies` block, after `implementation(libs.play.services.location)`:

```kotlin
    implementation(libs.osmdroid)
```

- [ ] **Step 3: Add battery-optimization permission to the manifest**

In `android/app/src/main/AndroidManifest.xml`, after the `ACCESS_BACKGROUND_LOCATION` line:

```xml
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

- [ ] **Step 4: Extend ParkStateStore**

Replace the interface body in `ParkStateStore.kt`:

```kotlin
interface ParkStateStore {
    var carMac: String?
    var carName: String?
    var myCar: MyCar?
    var autoClaim: Boolean
    var parked: Boolean
    var lastParkLocation: GeoPoint?

    /** True only when confirmed parked in a PAID zone — the state that blocks the other phone. */
    var parkedOutside: Boolean
    var parkedAtMs: Long
    var lastZoneCode: String?
    var homeZone: FreeZone?
    var syncUrl: String?
    /** claimedAtMs of the last permit takeover we already alerted about. */
    var lastAlertedClaimMs: Long
}
```

- [ ] **Step 5: Implement in PrefsParkStateStore**

Append inside the class (add `import dev.wasil.permit.data.api.PermitJson` and `import kotlinx.serialization.encodeToString` at the top):

```kotlin
    override var parkedOutside: Boolean
        get() = prefs.getBoolean("parked_outside", false)
        set(value) { prefs.edit().putBoolean("parked_outside", value).apply() }

    override var parkedAtMs: Long
        get() = prefs.getLong("parked_at_ms", 0L)
        set(value) { prefs.edit().putLong("parked_at_ms", value).apply() }

    override var lastZoneCode: String?
        get() = prefs.getString("last_zone_code", null)
        set(value) { prefs.edit().putString("last_zone_code", value).apply() }

    override var homeZone: FreeZone?
        get() = prefs.getString("home_zone", null)?.let {
            runCatching { PermitJson.decodeFromString<FreeZone>(it) }.getOrNull()
        }
        set(value) {
            prefs.edit().putString("home_zone", value?.let { PermitJson.encodeToString(it) }).apply()
        }

    override var syncUrl: String?
        get() = prefs.getString("sync_url", null)
        set(value) { prefs.edit().putString("sync_url", value).apply() }

    override var lastAlertedClaimMs: Long
        get() = prefs.getLong("last_alerted_claim_ms", 0L)
        set(value) { prefs.edit().putLong("last_alerted_claim_ms", value).apply() }
```

- [ ] **Step 6: Extend the fake**

Append inside `FakeParkStateStore`:

```kotlin
    override var parkedOutside: Boolean = false
    override var parkedAtMs: Long = 0
    override var lastZoneCode: String? = null
    override var homeZone: FreeZone? = null
    override var syncUrl: String? = null
    override var lastAlertedClaimMs: Long = 0
```

- [ ] **Step 7: Verify build + tests**

Run (from `android\`): `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 47 tests pass.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: phase 3 groundwork - store fields, osmdroid dep, battery permission"
```

---

### Task 2: Room id derivation

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/parking/shared/RoomId.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/parking/shared/RoomIdTest.kt`

**Interfaces:**
- Produces: `fun roomIdFor(username: String): String` — 32 lowercase hex chars, deterministic, case/whitespace-normalized input.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.wasil.permit.parking.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomIdTest {
    @Test
    fun `room id is 32 lowercase hex chars`() {
        val id = roomIdFor("wasil@example.com")
        assertEquals(32, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
    }

    @Test
    fun `same username gives same room`() {
        assertEquals(roomIdFor("wasil@example.com"), roomIdFor("wasil@example.com"))
    }

    @Test
    fun `case and whitespace are normalized`() {
        assertEquals(roomIdFor("wasil@example.com"), roomIdFor("  Wasil@Example.COM "))
    }

    @Test
    fun `different usernames give different rooms`() {
        assertNotEquals(roomIdFor("wasil@example.com"), roomIdFor("walid@example.com"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.shared.RoomIdTest"`
Expected: compilation FAILURE (`roomIdFor` unresolved).

- [ ] **Step 3: Implement**

```kotlin
package dev.wasil.permit.parking.shared

import java.security.MessageDigest

/**
 * Both phones derive the same RTDB room from the permit-site username they
 * already share. 128 bits of the SHA-256 keeps the path unguessable.
 */
fun roomIdFor(username: String): String {
    val input = "permit-room:v1:" + username.trim().lowercase()
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(32)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.shared.RoomIdTest"`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: room id derivation from permit username"
```

---

### Task 3: Shared-state DTOs + RTDB REST store

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/parking/shared/PhoneState.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/parking/shared/SharedStateStore.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/parking/shared/RtdbSharedStateStore.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/parking/shared/RtdbSharedStateStoreTest.kt`

**Interfaces:**
- Consumes: `dev.wasil.permit.data.api.PermitJson` (existing lenient Json instance).
- Produces:
  - `data class PhoneState(parkedOutside: Boolean = false, lat: Double? = null, lng: Double? = null, accuracyM: Double? = null, zoneCode: String? = null, parkedAtMs: Long = 0, heartbeatAtMs: Long = 0)`
  - `data class PermitClaim(holder: String, vrn: String, claimedAtMs: Long = 0, forced: Boolean = false)`
  - `interface SharedStateStore { val configured: Boolean; suspend fun readOther(): PhoneState?; suspend fun writeMine(state: PhoneState); suspend fun heartbeat(); suspend fun readPermit(): PermitClaim?; suspend fun writePermit(holder: String, vrn: String, forced: Boolean) }` — network failure throws `IOException`, absent node returns `null`.
  - `object UnconfiguredSharedStateStore : SharedStateStore` (configured=false, reads null, writes no-op).
  - `class RtdbSharedStateStore(baseUrl: HttpUrl, room: String, me: String, other: String, client: OkHttpClient)`.

- [ ] **Step 1: Write the DTOs**

`PhoneState.kt`:

```kotlin
package dev.wasil.permit.parking.shared

import kotlinx.serialization.Serializable

@Serializable
data class PhoneState(
    val parkedOutside: Boolean = false,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Double? = null,
    val zoneCode: String? = null,
    val parkedAtMs: Long = 0,
    val heartbeatAtMs: Long = 0,
)

@Serializable
data class PermitClaim(
    val holder: String,
    val vrn: String,
    val claimedAtMs: Long = 0,
    val forced: Boolean = false,
)
```

`SharedStateStore.kt`:

```kotlin
package dev.wasil.permit.parking.shared

/**
 * Two-phone shared state. Implementations throw IOException on network
 * trouble and return null when a node simply doesn't exist yet.
 */
interface SharedStateStore {
    val configured: Boolean
    suspend fun readOther(): PhoneState?
    /** heartbeatAtMs in [state] is replaced by the RTDB server timestamp. */
    suspend fun writeMine(state: PhoneState)
    suspend fun heartbeat()
    suspend fun readPermit(): PermitClaim?
    /** claimedAtMs is set to the RTDB server timestamp. */
    suspend fun writePermit(holder: String, vrn: String, forced: Boolean)
}

/** Used until the user enters a database URL: nothing to read, writes vanish. */
object UnconfiguredSharedStateStore : SharedStateStore {
    override val configured = false
    override suspend fun readOther(): PhoneState? = null
    override suspend fun writeMine(state: PhoneState) {}
    override suspend fun heartbeat() {}
    override suspend fun readPermit(): PermitClaim? = null
    override suspend fun writePermit(holder: String, vrn: String, forced: Boolean) {}
}
```

- [ ] **Step 2: Write the failing MockWebServer test**

```kotlin
package dev.wasil.permit.parking.shared

import java.io.IOException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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
        try { store().readOther(); fail("expected IOException") }
        catch (expected: IOException) {}
    }
}
```

Note: if the project's MockWebServer artifact uses the `okhttp3.mockwebserver` package (check the imports in `android/app/src/test/java/dev/wasil/permit/data/` tests), use those imports instead of `mockwebserver3` — match whatever the existing tests use.

- [ ] **Step 3: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.shared.RtdbSharedStateStoreTest"`
Expected: compilation FAILURE (`RtdbSharedStateStore` unresolved).

- [ ] **Step 4: Implement RtdbSharedStateStore**

```kotlin
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
```

- [ ] **Step 5: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.shared.RtdbSharedStateStoreTest"`
Expected: 6 tests pass.

- [ ] **Step 6: Full test run + commit**

Run: `.\gradlew.bat :app:testDebugUnitTest` — all pass. Then:

```bash
git add -A
git commit -m "feat: RTDB shared-state store over REST with server timestamps"
```

---

### Task 4: Point-in-polygon

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/parking/zones/PointInPolygon.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/parking/zones/PointInPolygonTest.kt`

**Interfaces:**
- Produces: `data class LatLng(lat: Double, lng: Double)`, `data class ZonePolygon(outer: List<LatLng>, holes: List<List<LatLng>> = emptyList())`, `fun pointInPolygon(p: LatLng, polygon: ZonePolygon): Boolean`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.wasil.permit.parking.zones

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointInPolygonTest {
    // Unit square around Amsterdam-ish coordinates.
    private val square = ZonePolygon(outer = listOf(
        LatLng(52.0, 4.0), LatLng(52.0, 5.0), LatLng(53.0, 5.0), LatLng(53.0, 4.0),
    ))

    @Test
    fun `point inside square`() {
        assertTrue(pointInPolygon(LatLng(52.5, 4.5), square))
    }

    @Test
    fun `point outside square`() {
        assertFalse(pointInPolygon(LatLng(51.5, 4.5), square))
        assertFalse(pointInPolygon(LatLng(52.5, 5.5), square))
    }

    @Test
    fun `point in hole is outside`() {
        val withHole = square.copy(holes = listOf(listOf(
            LatLng(52.4, 4.4), LatLng(52.4, 4.6), LatLng(52.6, 4.6), LatLng(52.6, 4.4),
        )))
        assertFalse(pointInPolygon(LatLng(52.5, 4.5), withHole))
        assertTrue(pointInPolygon(LatLng(52.1, 4.1), withHole))
    }

    @Test
    fun `concave polygon (U shape)`() {
        // U opening upward: notch cut from the top between lng 4.3 and 4.7.
        val u = ZonePolygon(outer = listOf(
            LatLng(52.0, 4.0), LatLng(52.0, 5.0), LatLng(53.0, 5.0), LatLng(53.0, 4.7),
            LatLng(52.3, 4.7), LatLng(52.3, 4.3), LatLng(53.0, 4.3), LatLng(53.0, 4.0),
        ))
        assertTrue(pointInPolygon(LatLng(52.1, 4.5), u))    // bottom of the U
        assertFalse(pointInPolygon(LatLng(52.8, 4.5), u))   // inside the notch
        assertTrue(pointInPolygon(LatLng(52.8, 4.1), u))    // left arm
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.zones.PointInPolygonTest"`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement**

```kotlin
package dev.wasil.permit.parking.zones

data class LatLng(val lat: Double, val lng: Double)

data class ZonePolygon(
    val outer: List<LatLng>,
    val holes: List<List<LatLng>> = emptyList(),
)

/** Even-odd ray casting; horizontal ray toward +lng. */
private fun pointInRing(p: LatLng, ring: List<LatLng>): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[j]
        if ((a.lat > p.lat) != (b.lat > p.lat)) {
            val lngAtLat = (b.lng - a.lng) * (p.lat - a.lat) / (b.lat - a.lat) + a.lng
            if (p.lng < lngAtLat) inside = !inside
        }
        j = i
    }
    return inside
}

fun pointInPolygon(p: LatLng, polygon: ZonePolygon): Boolean =
    pointInRing(p, polygon.outer) && polygon.holes.none { pointInRing(p, it) }
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.zones.PointInPolygonTest"`
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: ray-casting point-in-polygon with hole support"
```

---

### Task 5: Tariff-area parsing + bundled Amsterdam asset

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/parking/zones/TariffAreas.kt`
- Create: `android/app/src/main/assets/amsterdam_tarieven.json` (downloaded snapshot)
- Test: `android/app/src/test/java/dev/wasil/permit/parking/zones/TariffAreasTest.kt`

**Interfaces:**
- Consumes: `LatLng`, `ZonePolygon` (Task 4).
- Produces: `data class TariffArea(code: String, name: String, tariffText: String, polygons: List<ZonePolygon>)`, `object TariffAreas { fun parse(json: String): List<TariffArea> }`.

The source file (already inspected): top level is an object keyed by area code
(`T11V`, `T12A`, …, 29 entries). Each entry has `description` (object whose
values are display names), `location` (GeoJSON `Polygon` or `MultiPolygon`,
coordinates in `[lng, lat]` order), and `tarieven` (array or object whose keys
are prices like `"8,05"` or stepped `"1,72[0-180];4,19[180-999999]"`).

- [ ] **Step 1: Download the snapshot into assets**

From the repo root in PowerShell:

```powershell
New-Item -ItemType Directory -Force android\app\src\main\assets | Out-Null
Invoke-WebRequest -Uri "https://amsterdam-maps.bma-collective.com/embed/parkeren/deploy_data/tarieven.json" -OutFile "android\app\src\main\assets\amsterdam_tarieven.json" -UseBasicParsing
```

Verify size ≈ 617 KB and that it starts with `{"T11V"`.

- [ ] **Step 2: Write the failing parser test**

The fixture mirrors the real structure (one Polygon area, one MultiPolygon
area with a stepped tariff):

```kotlin
package dev.wasil.permit.parking.zones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TariffAreasTest {
    private val fixture = """
    {
      "T11V": {
        "description": {"0": "Basistarief TC1 ma-zo 00-24"},
        "location": {
          "type": "Polygon",
          "coordinates": [[[4.0, 52.0], [5.0, 52.0], [5.0, 53.0], [4.0, 53.0], [4.0, 52.0]]]
        },
        "tarieven": [{"8,05": {"0-2400": "ma-zo"}}]
      },
      "T14_UA01": {
        "description": {"0": "Tarief 4 start tarief 7"},
        "location": {
          "type": "MultiPolygon",
          "coordinates": [
            [[[4.0, 50.0], [4.1, 50.0], [4.1, 50.1], [4.0, 50.1], [4.0, 50.0]]],
            [[[5.0, 51.0], [5.1, 51.0], [5.1, 51.1], [5.0, 51.1], [5.0, 51.0]]]
          ]
        },
        "tarieven": [{"1,72[0-180];4,19[180-999999]": {"900-2100": "ma-za"}}]
      },
      "BROKEN": {"description": {"0": "no geometry"}}
    }
    """

    @Test
    fun `parses codes names and polygons with lng-lat swapped to lat-lng`() {
        val areas = TariffAreas.parse(fixture)
        val t11 = areas.first { it.code == "T11V" }
        assertEquals("Basistarief TC1 ma-zo 00-24", t11.name)
        assertEquals(1, t11.polygons.size)
        assertEquals(LatLng(52.0, 4.0), t11.polygons[0].outer.first())
    }

    @Test
    fun `multipolygon becomes several polygons`() {
        val areas = TariffAreas.parse(fixture)
        assertEquals(2, areas.first { it.code == "T14_UA01" }.polygons.size)
    }

    @Test
    fun `tariff text formats plain and stepped prices`() {
        val areas = TariffAreas.parse(fixture)
        assertEquals("€8,05/h", areas.first { it.code == "T11V" }.tariffText)
        assertEquals("€1,72/h (stepped)", areas.first { it.code == "T14_UA01" }.tariffText)
    }

    @Test
    fun `malformed records are skipped not fatal`() {
        val areas = TariffAreas.parse(fixture)
        assertEquals(2, areas.size)
        assertTrue(areas.none { it.code == "BROKEN" })
    }

    @Test
    fun `garbage input returns empty list`() {
        assertEquals(emptyList<TariffArea>(), TariffAreas.parse("not json"))
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.zones.TariffAreasTest"`
Expected: compilation FAILURE.

- [ ] **Step 4: Implement the parser**

```kotlin
package dev.wasil.permit.parking.zones

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class TariffArea(
    val code: String,
    val name: String,
    val tariffText: String,
    val polygons: List<ZonePolygon>,
)

/** Parser for Amsterdam's tarieven.json (maps.amsterdam.nl parking-rate areas, CC-BY). */
object TariffAreas {

    fun parse(json: String): List<TariffArea> = runCatching {
        Json.parseToJsonElement(json).jsonObject.mapNotNull { (code, value) ->
            runCatching { parseArea(code, value.jsonObject) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun parseArea(code: String, obj: JsonObject): TariffArea {
        val name = obj["description"]?.jsonObject?.values?.firstOrNull()
            ?.jsonPrimitive?.content ?: code
        val location = obj.getValue("location").jsonObject
        val coords = location.getValue("coordinates").jsonArray
        val polygons = when (location.getValue("type").jsonPrimitive.content) {
            "Polygon" -> listOf(parsePolygon(coords))
            "MultiPolygon" -> coords.map { parsePolygon(it.jsonArray) }
            else -> error("unknown geometry")
        }
        require(polygons.isNotEmpty() && polygons.all { it.outer.size >= 3 })
        return TariffArea(code, name, tariffText(obj), polygons)
    }

    /** GeoJSON: first ring is the outer boundary, the rest are holes; [lng, lat] order. */
    private fun parsePolygon(rings: JsonArray): ZonePolygon {
        val parsed = rings.map { ring ->
            ring.jsonArray.mapNotNull { pos ->
                val arr = pos.jsonArray
                val lng = arr[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                val lat = arr[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                LatLng(lat, lng)
            }
        }
        return ZonePolygon(outer = parsed.first(), holes = parsed.drop(1))
    }

    private fun tariffText(obj: JsonObject): String {
        val tarieven = obj["tarieven"] ?: return ""
        val entry = when (tarieven) {
            is JsonArray -> tarieven.firstOrNull() as? JsonObject
            is JsonObject -> tarieven
            else -> null
        } ?: return ""
        val key = entry.keys.firstOrNull() ?: return ""
        return if ("[" in key) "€${key.substringBefore("[")}/h (stepped)" else "€$key/h"
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.zones.TariffAreasTest"`
Expected: 5 tests pass.

- [ ] **Step 6: Sanity-check the real asset parses**

Add to `TariffAreasTest`:

```kotlin
    @Test
    fun `bundled amsterdam asset parses to 29 areas`() {
        val path = java.nio.file.Paths.get("src/main/assets/amsterdam_tarieven.json")
        val json = java.nio.file.Files.readString(path)
        val areas = TariffAreas.parse(json)
        assertEquals(29, areas.size)
        assertTrue(areas.all { it.polygons.isNotEmpty() })
        assertTrue(areas.all { area -> area.polygons.all { it.outer.size >= 3 } })
    }
```

(Unit tests run with the module directory `android/app` as working directory,
so the relative path resolves. If the working directory differs, resolve via
`System.getProperty("user.dir")` — check the failure message.)

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.zones.TariffAreasTest"`
Expected: 6 tests pass — this proves the real 617 KB snapshot parses.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: Amsterdam tariff-area parsing + bundled city snapshot (CC-BY Gemeente Amsterdam)"
```

---

### Task 6: Zone resolver

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/parking/zones/ZoneResolver.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/parking/zones/ZoneResolverTest.kt`

**Interfaces:**
- Consumes: `FreeZone`, `GeoPoint`, `distanceMeters` (existing); `TariffArea`, `LatLng`, `pointInPolygon` (Tasks 4–5).
- Produces:
  - `sealed interface ZoneInfo { Home; ManualFree(label: String); Paid(area: TariffArea?); FreeStreet }` — `Paid(null)` means "tariff data unavailable, assume paid".
  - `class ZoneResolver(home: FreeZone?, manualZones: List<FreeZone>, areas: List<TariffArea>?) { fun resolve(point: GeoPoint): ZoneInfo }` — precedence Home > ManualFree > Paid > FreeStreet; `areas == null` ⇒ `Paid(null)` for anything not home/manual.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.wasil.permit.parking.zones

import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneResolverTest {
    private val home = FreeZone(52.3702, 4.8952, 60.0, "Home")
    private val manual = FreeZone(52.3800, 4.9000, 60.0, "Work lot")
    // Paid square covering everything from (52.0, 4.0) to (53.0, 5.0).
    private val paidArea = TariffArea(
        code = "T11V", name = "Centrum", tariffText = "€8,05/h",
        polygons = listOf(ZonePolygon(outer = listOf(
            LatLng(52.0, 4.0), LatLng(52.0, 5.0), LatLng(53.0, 5.0), LatLng(53.0, 4.0),
        ))),
    )
    private val resolver = ZoneResolver(home, listOf(manual), listOf(paidArea))

    @Test
    fun `home wins even inside a paid polygon`() {
        assertEquals(ZoneInfo.Home, resolver.resolve(GeoPoint(52.3702, 4.8952, 5f)))
    }

    @Test
    fun `manual free zone wins over paid`() {
        val zone = resolver.resolve(GeoPoint(52.3800, 4.9000, 5f))
        assertEquals(ZoneInfo.ManualFree("Work lot"), zone)
    }

    @Test
    fun `inside a tariff polygon is paid with the area attached`() {
        val zone = resolver.resolve(GeoPoint(52.5, 4.5, 5f)) as ZoneInfo.Paid
        assertEquals("T11V", zone.area?.code)
    }

    @Test
    fun `outside every polygon is free street parking`() {
        assertEquals(ZoneInfo.FreeStreet, resolver.resolve(GeoPoint(51.0, 4.5, 5f)))
    }

    @Test
    fun `no tariff data degrades to paid-unknown`() {
        val degraded = ZoneResolver(home, emptyList(), areas = null)
        val zone = degraded.resolve(GeoPoint(51.0, 4.5, 5f)) as ZoneInfo.Paid
        assertNull(zone.area)
        assertEquals(ZoneInfo.Home, degraded.resolve(GeoPoint(52.3702, 4.8952, 5f)))
    }

    @Test
    fun `no home configured never resolves home`() {
        val noHome = ZoneResolver(null, emptyList(), listOf(paidArea))
        assertTrue(noHome.resolve(GeoPoint(52.3702, 4.8952, 5f)) is ZoneInfo.Paid)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.zones.ZoneResolverTest"`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement**

```kotlin
package dev.wasil.permit.parking.zones

import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.distanceMeters

sealed interface ZoneInfo {
    data object Home : ZoneInfo
    data class ManualFree(val label: String) : ZoneInfo
    /** area == null: tariff data unavailable — assume paid (claiming is the safe bias). */
    data class Paid(val area: TariffArea?) : ZoneInfo
    data object FreeStreet : ZoneInfo
}

class ZoneResolver(
    private val home: FreeZone?,
    private val manualZones: List<FreeZone>,
    private val areas: List<TariffArea>?,
) {
    fun resolve(point: GeoPoint): ZoneInfo {
        if (home != null && inCircle(point, home)) return ZoneInfo.Home
        manualZones.firstOrNull { inCircle(point, it) }
            ?.let { return ZoneInfo.ManualFree(it.label) }
        val loaded = areas ?: return ZoneInfo.Paid(null)
        val p = LatLng(point.lat, point.lng)
        loaded.firstOrNull { area -> area.polygons.any { pointInPolygon(p, it) } }
            ?.let { return ZoneInfo.Paid(it) }
        return ZoneInfo.FreeStreet
    }

    private fun inCircle(point: GeoPoint, zone: FreeZone): Boolean =
        distanceMeters(point, GeoPoint(zone.lat, zone.lng, 0f)) <= zone.radiusM
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.zones.ZoneResolverTest"`
Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: zone resolver - home > manual free > paid > free street"
```

---

### Task 7: Claim guard

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/parking/shared/ClaimGuard.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/parking/shared/ClaimGuardTest.kt`

**Interfaces:**
- Consumes: `PhoneState` (Task 3).
- Produces: `object ClaimGuard { const val STALE_AFTER_MS: Long; sealed interface Verdict { Proceed; Blocked(other: PhoneState) }; fun evaluate(nonTarget: PhoneState?, nonTargetPlate: String, activeVrn: String?, nowMs: Long): Verdict }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.wasil.permit.parking.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimGuardTest {
    private val now = 1_000_000_000_000L
    private val parkedFresh = PhoneState(
        parkedOutside = true, parkedAtMs = now - 60_000, heartbeatAtMs = now - 60_000,
    )

    @Test
    fun `no data about the other proceeds`() {
        assertEquals(ClaimGuard.Verdict.Proceed,
            ClaimGuard.evaluate(null, "XX123Y", "XX123Y", now))
    }

    @Test
    fun `other not parked proceeds`() {
        val idle = parkedFresh.copy(parkedOutside = false)
        assertEquals(ClaimGuard.Verdict.Proceed,
            ClaimGuard.evaluate(idle, "XX123Y", "XX123Y", now))
    }

    @Test
    fun `other parked fresh and holding blocks`() {
        val verdict = ClaimGuard.evaluate(parkedFresh, "XX123Y", "XX123Y", now)
        assertTrue(verdict is ClaimGuard.Verdict.Blocked)
    }

    @Test
    fun `other parked but permit not on their plate proceeds`() {
        assertEquals(ClaimGuard.Verdict.Proceed,
            ClaimGuard.evaluate(parkedFresh, "XX123Y", "RH950F", now))
        assertEquals(ClaimGuard.Verdict.Proceed,
            ClaimGuard.evaluate(parkedFresh, "XX123Y", null, now))
    }

    @Test
    fun `stale heartbeat proceeds`() {
        val stale = parkedFresh.copy(heartbeatAtMs = now - ClaimGuard.STALE_AFTER_MS - 1)
        assertEquals(ClaimGuard.Verdict.Proceed,
            ClaimGuard.evaluate(stale, "XX123Y", "XX123Y", now))
    }

    @Test
    fun `heartbeat exactly at the cutoff still blocks`() {
        val edge = parkedFresh.copy(heartbeatAtMs = now - ClaimGuard.STALE_AFTER_MS)
        assertTrue(ClaimGuard.evaluate(edge, "XX123Y", "XX123Y", now)
            is ClaimGuard.Verdict.Blocked)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.shared.ClaimGuardTest"`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement**

```kotlin
package dev.wasil.permit.parking.shared

object ClaimGuard {
    /** After 6 h without a heartbeat the other phone's "parked" is not trusted. */
    const val STALE_AFTER_MS: Long = 6 * 60 * 60 * 1000

    sealed interface Verdict {
        data object Proceed : Verdict
        data class Blocked(val other: PhoneState) : Verdict
    }

    /**
     * Claiming strands the non-target car iff that car is parked outside
     * (fresh heartbeat) AND the permit is actually on its plate right now.
     */
    fun evaluate(
        nonTarget: PhoneState?,
        nonTargetPlate: String,
        activeVrn: String?,
        nowMs: Long,
    ): Verdict {
        if (nonTarget == null || !nonTarget.parkedOutside) return Verdict.Proceed
        if (nowMs - nonTarget.heartbeatAtMs > STALE_AFTER_MS) return Verdict.Proceed
        if (activeVrn != nonTargetPlate) return Verdict.Proceed
        return Verdict.Blocked(nonTarget)
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.shared.ClaimGuardTest"`
Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: claim guard - block only when the other car is parked, fresh, and holds the permit"
```

---

### Task 8: Notifier extension, ClaimPermit(target), GuardedClaim + give-back

This is the collision fix. `ParkNotifier` gets its final Phase 3 shape (with
the Android impl updated in the same task so everything compiles), `ClaimPermit`
learns to switch to either car, and the new `GuardedClaim` becomes the single
choke point every permit switch goes through.

**Files:**
- Modify: `android/app/src/main/java/dev/wasil/permit/parking/ParkDetectionUseCase.kt` (only the `ParkNotifier` interface block and the one `statusFreeZone()` call site)
- Modify: `android/app/src/main/java/dev/wasil/permit/parking/android/ParkNotifications.kt`
- Modify: `android/app/src/main/java/dev/wasil/permit/parking/ClaimPermit.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/parking/GuardedClaim.kt`
- Create: `android/app/src/test/java/dev/wasil/permit/parking/TestFakes.kt`
- Modify: `android/app/src/test/java/dev/wasil/permit/parking/ParkDetectionUseCaseTest.kt` (its private `RecordingNotifier` moves to TestFakes; its private `SwitchApi` moves too)
- Test: `android/app/src/test/java/dev/wasil/permit/parking/GuardedClaimTest.kt`

**Interfaces:**
- Consumes: `ClaimGuard`, `PhoneState`, `SharedStateStore` (Tasks 3, 7); existing `PermitRepository`, `CredentialStore`, `ParkStateStore`, `MyCar`, `ParkOutcome`.
- Produces:
  - `ParkNotifier` final shape (below).
  - `ClaimPermit.claim(target: MyCar? = null, zoneText: String? = null): ParkOutcome`.
  - `fun MyCar.other(): MyCar`, `fun MyCar.label(): String`, `fun MyCar.key(): String`.
  - `sealed interface GuardedResult { Done(outcome: ParkOutcome, guardSkippedNote: String? = null); Blocked(otherLabel: String, other: PhoneState) }`.
  - `sealed interface GiveBackResult { Given(vrn: String); NothingToDo; Failed }`.
  - `class GuardedClaim(repository, credentialStore, stateStore, shared, claimPermit, nowMs = System::currentTimeMillis)` with `suspend fun claim(target: MyCar? = null, force: Boolean = false, userInitiated: Boolean = false, zoneText: String? = null): GuardedResult` and `suspend fun giveBack(): GiveBackResult`.
  - Test fakes: `RecordingParkNotifier`, `SwitchApi`, `FakeSharedStateStore`.

- [ ] **Step 1: Final ParkNotifier interface**

In `ParkDetectionUseCase.kt`, replace the `ParkNotifier` interface with:

```kotlin
interface ParkNotifier {
    fun statusPermitOn(label: String, vrn: String, zoneText: String? = null)
    /** Ongoing status when parked without claiming (home / free zone / free street). */
    fun statusParkedNoClaim(reason: String)
    fun askManualDecision()
    fun askGiveBack(otherLabel: String)
    fun blockedByOther(otherLabel: String, parkedAtMs: Long, heartbeatAtMs: Long)
    fun takeover(byLabel: String)
    fun switchFailed(reason: String?)
    fun mismatchWarning(serverVrn: String?)
    /** One-off dismissible note on the events channel. */
    fun eventNote(text: String)
}
```

In the same file, change the `Decision.ParkedInCar, Decision.ParkedWalkedAway` branch's free-zone line from `notifier.statusFreeZone()` to `notifier.statusParkedNoClaim("in a free zone")` (the full rework of this class happens in Task 9).

- [ ] **Step 2: Update the Android implementation**

In `ParkNotifications.kt` replace `statusPermitOn` and `statusFreeZone` and add the new methods (keep `askManualDecision`, `switchFailed`, `mismatchWarning`, `notify`, `action`, `now` as they are):

```kotlin
    private fun time(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    override fun statusPermitOn(label: String, vrn: String, zoneText: String?) {
        val text = buildString {
            append("Claimed at ${now()}")
            zoneText?.let { append(" · $it") }
        }
        notify(STATUS_ID, NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Permit on $label's car ($vrn)")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true))
        dismissEvents(context)
    }

    override fun statusParkedNoClaim(reason: String) {
        notify(STATUS_ID, NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Parked — permit untouched")
            .setContentText("$reason (${now()})")
            .setOngoing(true)
            .setOnlyAlertOnce(true))
    }

    override fun askGiveBack(otherLabel: String) {
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Give the permit back to $otherLabel?")
            .setContentText("You parked free; $otherLabel's car is parked outside and the permit is still on yours.")
            .setAutoCancel(true)
            .addAction(action(ParkActionReceiver.ACTION_GIVE_BACK, "Give back"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "Keep it")))
    }

    override fun blockedByOther(otherLabel: String, parkedAtMs: Long, heartbeatAtMs: Long) {
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$otherLabel's car is parked — permit NOT claimed")
            .setContentText("$otherLabel parked at ${time(parkedAtMs)} (last seen ${time(heartbeatAtMs)}). Claiming would leave their car unpermitted.")
            .setAutoCancel(true)
            .addAction(action(ParkActionReceiver.ACTION_CLAIM_FORCE, "Claim anyway"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "Ignore")))
    }

    override fun takeover(byLabel: String) {
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$byLabel took the permit")
            .setContentText("Your car is parked WITHOUT a permit. Move it or reclaim.")
            .setAutoCancel(true)
            .addAction(action(ParkActionReceiver.ACTION_CLAIM, "Reclaim"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "OK")))
    }

    override fun eventNote(text: String) {
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Permit Switcher")
            .setContentText(text)
            .setAutoCancel(true))
    }
```

`ACTION_CLAIM_FORCE` and `ACTION_GIVE_BACK` don't exist yet — add the constants now in `ParkActionReceiver`'s companion (wiring happens in Task 10):

```kotlin
        const val ACTION_CLAIM_FORCE = "dev.wasil.permit.CLAIM_FORCE"
        const val ACTION_GIVE_BACK = "dev.wasil.permit.GIVE_BACK"
```

- [ ] **Step 3: ClaimPermit gains a target + zone text**

Replace the `claim` function in `ClaimPermit.kt`:

```kotlin
    suspend fun claim(target: MyCar? = null, zoneText: String? = null): ParkOutcome {
        val config = credentialStore.load() ?: return ParkOutcome.NotConfigured
        val car = target ?: stateStore.myCar ?: return ParkOutcome.NotConfigured
        val (label, plate) = when (car) {
            MyCar.WASIL -> "Wasil" to config.wasilPlate
            MyCar.WALID -> "Walid" to config.walidPlate
        }
        return try {
            when (val result = repository.switchTo(plate)) {
                is PermitRepository.SwitchResult.Confirmed -> {
                    notifier.statusPermitOn(label, result.activeVrn, zoneText)
                    ParkOutcome.Claimed(result.activeVrn)
                }
                is PermitRepository.SwitchResult.Mismatch -> {
                    notifier.mismatchWarning(result.serverActiveVrn)
                    ParkOutcome.MismatchDetected(result.serverActiveVrn)
                }
            }
        } catch (e: Exception) {
            notifier.switchFailed(e.message)
            ParkOutcome.SwitchFailed
        }
    }
```

- [ ] **Step 4: Move the shared test fakes**

Create `android/app/src/test/java/dev/wasil/permit/parking/TestFakes.kt`. Cut `RecordingNotifier` and `SwitchApi` out of `ParkDetectionUseCaseTest.kt` (delete them there, add `RecordingParkNotifier` import-free since same package):

```kotlin
package dev.wasil.permit.parking

import dev.wasil.permit.data.api.ActivateRequest
import dev.wasil.permit.data.api.ActivateResponse
import dev.wasil.permit.data.api.ClientProductResponse
import dev.wasil.permit.data.api.LoginRequest
import dev.wasil.permit.data.api.LoginResponse
import dev.wasil.permit.data.api.PermitApi
import dev.wasil.permit.data.api.VrnEntry
import dev.wasil.permit.parking.shared.PermitClaim
import dev.wasil.permit.parking.shared.PhoneState
import dev.wasil.permit.parking.shared.SharedStateStore
import java.io.IOException

class RecordingParkNotifier : ParkNotifier {
    val calls = mutableListOf<String>()
    override fun statusPermitOn(label: String, vrn: String, zoneText: String?) {
        calls += "status:$label:$vrn" + (zoneText?.let { ":$it" } ?: "")
    }
    override fun statusParkedNoClaim(reason: String) { calls += "noclaim:$reason" }
    override fun askManualDecision() { calls += "manual" }
    override fun askGiveBack(otherLabel: String) { calls += "askgiveback:$otherLabel" }
    override fun blockedByOther(otherLabel: String, parkedAtMs: Long, heartbeatAtMs: Long) {
        calls += "blocked:$otherLabel"
    }
    override fun takeover(byLabel: String) { calls += "takeover:$byLabel" }
    override fun switchFailed(reason: String?) { calls += "failed" }
    override fun mismatchWarning(serverVrn: String?) { calls += "mismatch:$serverVrn" }
    override fun eventNote(text: String) { calls += "note:$text" }
}

/** In-memory permit API: active plate switches unless [fail] is set. */
class SwitchApi(var active: String? = "XX123Y", var fail: Boolean = false) : PermitApi {
    override suspend fun login(body: LoginRequest) = LoginResponse("tok")
    override suspend fun getClientProduct(productId: Long): ClientProductResponse =
        ClientProductResponse(listOf(
            VrnEntry("RH950F", active == "RH950F"), VrnEntry("XX123Y", active == "XX123Y")))
    override suspend fun activate(body: ActivateRequest): ActivateResponse {
        if (fail) throw IOException("offline")
        active = body.vrn
        return ActivateResponse(1)
    }
}

class FakeSharedStateStore(
    var other: PhoneState? = null,
    var permit: PermitClaim? = null,
    var throwOnRead: Boolean = false,
    override val configured: Boolean = true,
) : SharedStateStore {
    val myWrites = mutableListOf<PhoneState>()
    val permitWrites = mutableListOf<PermitClaim>()
    var heartbeats = 0

    override suspend fun readOther(): PhoneState? {
        if (throwOnRead) throw IOException("rtdb down")
        return other
    }
    override suspend fun writeMine(state: PhoneState) { myWrites += state }
    override suspend fun heartbeat() { heartbeats++ }
    override suspend fun readPermit(): PermitClaim? {
        if (throwOnRead) throw IOException("rtdb down")
        return permit
    }
    override suspend fun writePermit(holder: String, vrn: String, forced: Boolean) {
        permitWrites += PermitClaim(holder, vrn, claimedAtMs = 0, forced = forced)
    }
}
```

In `ParkDetectionUseCaseTest.kt`: replace every `RecordingNotifier` reference with `RecordingParkNotifier` and update its one assertion that used `"freezone"` to `notifier.calls.any { it.startsWith("noclaim:") }`. Do not otherwise change tests yet (full rework in Task 9).

- [ ] **Step 5: Run existing tests — must still pass**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: all 47 + new pass (compile fixes only, no behavior change).

- [ ] **Step 6: Write the failing GuardedClaim test**

```kotlin
package dev.wasil.permit.parking

import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.store.FakeCredentialStore
import dev.wasil.permit.data.store.PermitConfig
import dev.wasil.permit.parking.shared.PhoneState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedClaimTest {
    private val config = PermitConfig("u", "p", "RH950F", "XX123Y")  // Wasil, Walid
    private val now = 1_000_000_000_000L
    private val walidParkedFresh = PhoneState(
        parkedOutside = true, parkedAtMs = now - 120_000, heartbeatAtMs = now - 60_000,
    )

    private fun guarded(
        api: SwitchApi = SwitchApi(),
        shared: FakeSharedStateStore = FakeSharedStateStore(),
        state: FakeParkStateStore = FakeParkStateStore(),
        notifier: RecordingParkNotifier = RecordingParkNotifier(),
    ): GuardedClaim {
        val repo = PermitRepository(api)
        val credentials = FakeCredentialStore(config)
        return GuardedClaim(repo, credentials, state, shared,
            ClaimPermit(repo, credentials, state, notifier), nowMs = { now })
    }

    @Test
    fun `claiming my car while other parked fresh and holding blocks`() = runTest {
        val api = SwitchApi(active = "XX123Y")   // permit on Walid
        val shared = FakeSharedStateStore(other = walidParkedFresh)
        val result = guarded(api, shared).claim()
        assertTrue(result is GuardedResult.Blocked)
        assertEquals("Walid", (result as GuardedResult.Blocked).otherLabel)
        assertEquals("XX123Y", api.active)   // untouched
    }

    @Test
    fun `force claims anyway and records the takeover`() = runTest {
        val api = SwitchApi(active = "XX123Y")
        val shared = FakeSharedStateStore(other = walidParkedFresh)
        val result = guarded(api, shared).claim(force = true)
        assertEquals(ParkOutcome.Claimed("RH950F"), (result as GuardedResult.Done).outcome)
        assertEquals("RH950F", api.active)
        assertTrue(shared.permitWrites.single().forced)
        assertEquals("wasil", shared.permitWrites.single().holder)
    }

    @Test
    fun `other parked but not holding proceeds and records claim`() = runTest {
        val api = SwitchApi(active = "RH950F")   // permit already mine
        val shared = FakeSharedStateStore(other = walidParkedFresh)
        val result = guarded(api, shared).claim()
        assertEquals(ParkOutcome.Claimed("RH950F"), (result as GuardedResult.Done).outcome)
        assertFalse(shared.permitWrites.single().forced)
    }

    @Test
    fun `stale other proceeds`() = runTest {
        val api = SwitchApi(active = "XX123Y")
        val stale = walidParkedFresh.copy(
            heartbeatAtMs = now - dev.wasil.permit.parking.shared.ClaimGuard.STALE_AFTER_MS - 1)
        val result = guarded(api, FakeSharedStateStore(other = stale)).claim()
        assertTrue((result as GuardedResult.Done).outcome is ParkOutcome.Claimed)
    }

    @Test
    fun `rtdb down on an automatic claim degrades to manual`() = runTest {
        val api = SwitchApi(active = "XX123Y")
        val shared = FakeSharedStateStore(throwOnRead = true)
        val result = guarded(api, shared).claim(userInitiated = false)
        assertEquals(ParkOutcome.ManualNeeded, (result as GuardedResult.Done).outcome)
        assertEquals("XX123Y", api.active)   // did NOT gamble
    }

    @Test
    fun `rtdb down on a user claim proceeds with a note`() = runTest {
        val api = SwitchApi(active = "XX123Y")
        val shared = FakeSharedStateStore(throwOnRead = true)
        val result = guarded(api, shared).claim(userInitiated = true)
        result as GuardedResult.Done
        assertEquals(ParkOutcome.Claimed("RH950F"), result.outcome)
        assertEquals("couldn't check Walid's status", result.guardSkippedNote)
    }

    @Test
    fun `switching to the other car warns when MY car is parked outside holding`() = runTest {
        val api = SwitchApi(active = "RH950F")   // permit on me (Wasil)
        val state = FakeParkStateStore().apply { parkedOutside = true; parkedAtMs = now - 60_000 }
        val result = guarded(api, state = state).claim(target = MyCar.WALID)
        assertTrue(result is GuardedResult.Blocked)
        assertEquals("Wasil", (result as GuardedResult.Blocked).otherLabel)
    }

    @Test
    fun `successful claim of my car while parked marks parkedOutside`() = runTest {
        val state = FakeParkStateStore().apply { parked = true; parkedOutside = false }
        val result = guarded(state = state).claim(userInitiated = true)
        assertTrue((result as GuardedResult.Done).outcome is ParkOutcome.Claimed)
        assertTrue(state.parkedOutside)
    }

    @Test
    fun `give-back hands permit to the parked other`() = runTest {
        val api = SwitchApi(active = "RH950F")   // mine
        val shared = FakeSharedStateStore(other = walidParkedFresh)
        val result = guarded(api, shared).giveBack()
        assertEquals(GiveBackResult.Given("XX123Y"), result)
        assertEquals("XX123Y", api.active)
        assertEquals("walid", shared.permitWrites.single().holder)
    }

    @Test
    fun `give-back does nothing when other is not parked`() = runTest {
        val api = SwitchApi(active = "RH950F")
        val result = guarded(api, FakeSharedStateStore(other = null)).giveBack()
        assertEquals(GiveBackResult.NothingToDo, result)
        assertEquals("RH950F", api.active)
    }

    @Test
    fun `give-back does nothing when permit is not mine`() = runTest {
        val api = SwitchApi(active = "XX123Y")   // already Walid's
        val shared = FakeSharedStateStore(other = walidParkedFresh)
        assertEquals(GiveBackResult.NothingToDo, guarded(api, shared).giveBack())
    }

    @Test
    fun `give-back reports failure when rtdb is down`() = runTest {
        val result = guarded(shared = FakeSharedStateStore(throwOnRead = true)).giveBack()
        assertEquals(GiveBackResult.Failed, result)
    }

    @Test
    fun `give-back reports failure when the switch fails`() = runTest {
        val api = SwitchApi(active = "RH950F", fail = true)
        val shared = FakeSharedStateStore(other = walidParkedFresh)
        assertEquals(GiveBackResult.Failed, guarded(api, shared).giveBack())
    }
}
```

- [ ] **Step 7: Run to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.GuardedClaimTest"`
Expected: compilation FAILURE (`GuardedClaim` unresolved).

- [ ] **Step 8: Implement GuardedClaim**

`GuardedClaim.kt`:

```kotlin
package dev.wasil.permit.parking

import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.store.CredentialStore
import dev.wasil.permit.parking.shared.ClaimGuard
import dev.wasil.permit.parking.shared.PhoneState
import dev.wasil.permit.parking.shared.SharedStateStore

fun MyCar.other(): MyCar = if (this == MyCar.WASIL) MyCar.WALID else MyCar.WASIL
fun MyCar.label(): String = if (this == MyCar.WASIL) "Wasil" else "Walid"
fun MyCar.key(): String = name.lowercase()

sealed interface GuardedResult {
    /** Guard passed or was skipped; [outcome] is the raw switch outcome. */
    data class Done(val outcome: ParkOutcome, val guardSkippedNote: String? = null) : GuardedResult
    data class Blocked(val otherLabel: String, val other: PhoneState) : GuardedResult
}

sealed interface GiveBackResult {
    data class Given(val vrn: String) : GiveBackResult
    data object NothingToDo : GiveBackResult
    data object Failed : GiveBackResult
}

/**
 * The single choke point for permit switches. Checks whether the switch would
 * strand the non-target car (parked outside, fresh, actually holding the
 * permit) before executing; records the claim in shared state afterwards.
 */
class GuardedClaim(
    private val repository: PermitRepository,
    private val credentialStore: CredentialStore,
    private val stateStore: ParkStateStore,
    private val shared: SharedStateStore,
    private val claimPermit: ClaimPermit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun claim(
        target: MyCar? = null,
        force: Boolean = false,
        userInitiated: Boolean = false,
        zoneText: String? = null,
    ): GuardedResult {
        val config = credentialStore.load() ?: return GuardedResult.Done(ParkOutcome.NotConfigured)
        val mine = stateStore.myCar ?: return GuardedResult.Done(ParkOutcome.NotConfigured)
        val targetCar = target ?: mine
        val nonTarget = targetCar.other()
        val nonTargetPlate =
            if (nonTarget == MyCar.WASIL) config.wasilPlate else config.walidPlate

        var guardNote: String? = null
        if (!force) {
            try {
                // My own state is local and always fresh; the other's comes from RTDB.
                val nonTargetState =
                    if (nonTarget == mine) localPhoneState() else shared.readOther()
                val verdict = ClaimGuard.evaluate(
                    nonTargetState, nonTargetPlate, repository.activePlate(), nowMs(),
                )
                if (verdict is ClaimGuard.Verdict.Blocked) {
                    return GuardedResult.Blocked(nonTarget.label(), verdict.other)
                }
            } catch (e: Exception) {
                // Guard infrastructure failed. A background claim must not gamble;
                // a human pressing the button proceeds with a visible note.
                if (!userInitiated) return GuardedResult.Done(ParkOutcome.ManualNeeded)
                guardNote = "couldn't check ${nonTarget.label()}'s status"
            }
        }

        val outcome = claimPermit.claim(targetCar, zoneText)
        if (outcome is ParkOutcome.Claimed) {
            if (targetCar == mine && stateStore.parked) stateStore.parkedOutside = true
            runCatching { shared.writePermit(targetCar.key(), outcome.vrn, forced = force) }
        }
        return GuardedResult.Done(outcome, guardNote)
    }

    /** Hand the permit back when I parked free while the other car needs it. */
    suspend fun giveBack(): GiveBackResult {
        val config = credentialStore.load() ?: return GiveBackResult.NothingToDo
        val mine = stateStore.myCar ?: return GiveBackResult.NothingToDo
        val myPlate = if (mine == MyCar.WASIL) config.wasilPlate else config.walidPlate
        val other = mine.other()
        return try {
            val otherState = shared.readOther()
            val needsIt = otherState != null && otherState.parkedOutside &&
                nowMs() - otherState.heartbeatAtMs <= ClaimGuard.STALE_AFTER_MS
            if (!needsIt) return GiveBackResult.NothingToDo
            if (repository.activePlate() != myPlate) return GiveBackResult.NothingToDo
            when (val outcome = claimPermit.claim(other)) {
                is ParkOutcome.Claimed -> {
                    runCatching { shared.writePermit(other.key(), outcome.vrn, forced = false) }
                    GiveBackResult.Given(outcome.vrn)
                }
                ParkOutcome.SwitchFailed -> GiveBackResult.Failed
                else -> GiveBackResult.NothingToDo   // mismatch already warned loudly
            }
        } catch (e: Exception) {
            GiveBackResult.Failed
        }
    }

    private fun localPhoneState(): PhoneState = PhoneState(
        parkedOutside = stateStore.parkedOutside,
        parkedAtMs = stateStore.parkedAtMs,
        heartbeatAtMs = nowMs(),   // local state is by definition fresh
    )
}
```

- [ ] **Step 9: Run to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.GuardedClaimTest"`
Expected: 13 tests pass.

- [ ] **Step 10: Full run + commit**

Run: `.\gradlew.bat :app:testDebugUnitTest` — everything green. Then:

```bash
git add -A
git commit -m "feat: guarded claim - collision guard, override, give-back, shared permit record"
```

---

### Task 9: ParkDetectionUseCase rework — the decision table

**Files:**
- Modify: `android/app/src/main/java/dev/wasil/permit/parking/ParkDetectionUseCase.kt`
- Modify: `android/app/src/test/java/dev/wasil/permit/parking/ParkDetectionUseCaseTest.kt` (full rewrite of the test class body; keep `ScriptedSignals`)

**Interfaces:**
- Consumes: `ZoneResolver`, `ZoneInfo` (Task 6), `GuardedClaim`, `GuardedResult` (Task 8).
- Produces:
  - `interface ParkScheduler { fun requestSync(); fun requestGiveBack() }`
  - New constructor: `ParkDetectionUseCase(signals, stateStore, zoneResolver: ZoneResolver, guardedClaim: GuardedClaim, notifier, scheduler: ParkScheduler, pollIntervalMs = 5_000, nowMs: () -> Long = System::currentTimeMillis)` — the `freeZones`/`claimPermit` parameters are gone.
  - Behavior per the spec's decision table; shared `parkedOutside := confirmed park && zone is Paid`.

- [ ] **Step 1: Rewrite the use case**

Replace the `ParkDetectionUseCase` class (the file keeps `DetectionSignals`, `ParkNotifier`, `ParkOutcome` as edited in Task 8; add `ParkScheduler` after `ParkNotifier`):

```kotlin
interface ParkScheduler {
    fun requestSync()
    fun requestGiveBack()
}
```

```kotlin
import dev.wasil.permit.parking.zones.ZoneInfo
import dev.wasil.permit.parking.zones.ZoneResolver
```

```kotlin
class ParkDetectionUseCase(
    private val signals: DetectionSignals,
    private val stateStore: ParkStateStore,
    private val zoneResolver: ZoneResolver,
    private val guardedClaim: GuardedClaim,
    private val notifier: ParkNotifier,
    private val scheduler: ParkScheduler,
    private val pollIntervalMs: Long = 5_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): ParkOutcome {
        if (stateStore.myCar == null) return ParkOutcome.NotConfigured

        signals.start()
        val disconnectPoint = signals.currentLocation()
        var latestPoint = disconnectPoint
        var elapsed = 0L
        var decision: Decision? = null
        try {
            while (decision == null && elapsed < ParkDecisionEngine.TIMEOUT_MS) {
                decision = ParkDecisionEngine.decide(
                    signals.activitySamples(), disconnectPoint, latestPoint, elapsed,
                )
                if (decision == null) {
                    delay(pollIntervalMs)
                    elapsed += pollIntervalMs
                    latestPoint = signals.currentLocation() ?: latestPoint
                }
            }
        } finally {
            signals.stop()
        }

        return when (decision ?: Decision.Unclear) {
            Decision.FalseAlarm -> ParkOutcome.FalseAlarm
            Decision.Unclear -> {
                notifier.askManualDecision()
                ParkOutcome.ManualNeeded
            }
            Decision.ParkedInCar, Decision.ParkedWalkedAway -> confirmedPark(latestPoint)
        }
    }

    private suspend fun confirmedPark(point: GeoPoint?): ParkOutcome {
        stateStore.parked = true
        stateStore.parkedAtMs = nowMs()
        stateStore.lastParkLocation = point

        if (point == null) {
            // No GPS fix: could be at home for all we know. Never claim blind,
            // never block the other phone on guesswork.
            markNotOutside()
            notifier.askManualDecision()
            return ParkOutcome.ManualNeeded
        }

        val zone = zoneResolver.resolve(point)
        if (zone !is ZoneInfo.Paid) {
            markNotOutside()
            notifier.statusParkedNoClaim(reasonFor(zone))
            // The other car may be waiting for the permit we still hold.
            scheduler.requestGiveBack()
            return ParkOutcome.FreeZoneParked
        }

        stateStore.parkedOutside = true
        stateStore.lastZoneCode = zone.area?.code
        scheduler.requestSync()

        if (!stateStore.autoClaim) {
            notifier.askManualDecision()
            return ParkOutcome.ManualNeeded
        }

        return when (val result = guardedClaim.claim(zoneText = zoneText(zone))) {
            is GuardedResult.Blocked -> {
                notifier.blockedByOther(
                    result.otherLabel, result.other.parkedAtMs, result.other.heartbeatAtMs,
                )
                ParkOutcome.ManualNeeded
            }
            is GuardedResult.Done -> {
                if (result.outcome == ParkOutcome.ManualNeeded) notifier.askManualDecision()
                if (result.outcome is ParkOutcome.Claimed) scheduler.requestSync()
                result.outcome
            }
        }
    }

    private fun markNotOutside() {
        stateStore.parkedOutside = false
        stateStore.lastZoneCode = null
        scheduler.requestSync()
    }

    private fun reasonFor(zone: ZoneInfo): String = when (zone) {
        ZoneInfo.Home -> "at home"
        is ZoneInfo.ManualFree -> "in a free zone" +
            (zone.label.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        ZoneInfo.FreeStreet -> "free street parking (outside paid zones)"
        is ZoneInfo.Paid -> ""   // unreachable
    }

    private fun zoneText(zone: ZoneInfo.Paid): String =
        zone.area?.let { "${it.tariffText} zone ${it.code}" } ?: "paid area (zone data unavailable)"
}
```

- [ ] **Step 2: Rewrite the test class**

Keep `ScriptedSignals` at the top of `ParkDetectionUseCaseTest.kt` unchanged. Replace the test class body:

```kotlin
class ParkDetectionUseCaseTest {
    private val config = PermitConfig("u", "p", "RH950F", "XX123Y")
    private val stillAt6s = ActivitySample(ActivityType.STILL, 85, 6_000)
    private val driving = ActivitySample(ActivityType.IN_VEHICLE, 85, 6_000)
    private val now = 1_000_000_000_000L

    // Paid polygon covering lat 52..53, lng 4..5; home circle at 52.3702,4.8952.
    private val paidArea = dev.wasil.permit.parking.zones.TariffArea(
        "T11V", "Centrum", "€8,05/h",
        listOf(dev.wasil.permit.parking.zones.ZonePolygon(outer = listOf(
            dev.wasil.permit.parking.zones.LatLng(52.0, 4.0),
            dev.wasil.permit.parking.zones.LatLng(52.0, 5.0),
            dev.wasil.permit.parking.zones.LatLng(53.0, 5.0),
            dev.wasil.permit.parking.zones.LatLng(53.0, 4.0),
        ))))
    private val home = FreeZone(52.3702, 4.8952, 60.0, "Home")
    private val paidPoint = GeoPoint(52.5, 4.5, 5f)
    private val homePoint = GeoPoint(52.3702, 4.8952, 5f)
    private val outsidePoint = GeoPoint(51.0, 4.5, 5f)

    private class RecordingScheduler : ParkScheduler {
        val calls = mutableListOf<String>()
        override fun requestSync() { calls += "sync" }
        override fun requestGiveBack() { calls += "giveback" }
    }

    private fun useCase(
        signals: DetectionSignals,
        state: FakeParkStateStore = FakeParkStateStore(),
        api: SwitchApi = SwitchApi(),
        shared: FakeSharedStateStore = FakeSharedStateStore(),
        notifier: RecordingParkNotifier = RecordingParkNotifier(),
        scheduler: RecordingScheduler = RecordingScheduler(),
        homeZone: FreeZone? = home,
    ): ParkDetectionUseCase {
        val repo = PermitRepository(api)
        val credentials = FakeCredentialStore(config)
        val guarded = GuardedClaim(repo, credentials, state, shared,
            ClaimPermit(repo, credentials, state, notifier), nowMs = { now })
        val resolver = dev.wasil.permit.parking.zones.ZoneResolver(
            homeZone, emptyList(), listOf(paidArea))
        return ParkDetectionUseCase(signals, state, resolver, guarded, notifier,
            scheduler, nowMs = { now })
    }

    @Test
    fun `park in paid zone auto-claims with zone text and marks parked outside`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val state = FakeParkStateStore()
        val notifier = RecordingParkNotifier()
        val scheduler = RecordingScheduler()
        val outcome = useCase(signals, state, notifier = notifier, scheduler = scheduler).run()
        assertEquals(ParkOutcome.Claimed("RH950F"), outcome)
        assertTrue(state.parked)
        assertTrue(state.parkedOutside)
        assertEquals("T11V", state.lastZoneCode)
        assertTrue(notifier.calls.contains("status:Wasil:RH950F:€8,05/h zone T11V"))
        assertTrue(scheduler.calls.contains("sync"))
    }

    @Test
    fun `park at home never claims and asks give-back check`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(homePoint))
        val state = FakeParkStateStore()
        val api = SwitchApi()
        val scheduler = RecordingScheduler()
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, state, api, notifier = notifier, scheduler = scheduler).run()
        assertEquals(ParkOutcome.FreeZoneParked, outcome)
        assertFalse(state.parkedOutside)
        assertEquals("XX123Y", api.active)
        assertTrue(notifier.calls.contains("noclaim:at home"))
        assertTrue(scheduler.calls.contains("giveback"))
    }

    @Test
    fun `park outside all polygons is free street no claim`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(outsidePoint))
        val api = SwitchApi()
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, api = api, notifier = notifier).run()
        assertEquals(ParkOutcome.FreeZoneParked, outcome)
        assertEquals("XX123Y", api.active)
        assertTrue(notifier.calls.contains("noclaim:free street parking (outside paid zones)"))
    }

    @Test
    fun `no gps fix asks manual and does not block the other phone`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(null))
        val state = FakeParkStateStore()
        val api = SwitchApi()
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, state, api, notifier = notifier).run()
        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertTrue(state.parked)
        assertFalse(state.parkedOutside)
        assertEquals("XX123Y", api.active)
        assertTrue(notifier.calls.contains("manual"))
    }

    @Test
    fun `blocked by parked brother posts the blocked notification`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val api = SwitchApi(active = "XX123Y")
        val shared = FakeSharedStateStore(other = dev.wasil.permit.parking.shared.PhoneState(
            parkedOutside = true, parkedAtMs = now - 120_000, heartbeatAtMs = now - 60_000))
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, api = api, shared = shared, notifier = notifier).run()
        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertEquals("XX123Y", api.active)
        assertTrue(notifier.calls.contains("blocked:Walid"))
    }

    @Test
    fun `rtdb down on auto claim degrades to manual`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val api = SwitchApi(active = "XX123Y")
        val shared = FakeSharedStateStore(throwOnRead = true)
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, api = api, shared = shared, notifier = notifier).run()
        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertEquals("XX123Y", api.active)
        assertTrue(notifier.calls.contains("manual"))
    }

    @Test
    fun `auto-claim off in paid zone asks manual`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val state = FakeParkStateStore().apply { autoClaim = false }
        val api = SwitchApi()
        val outcome = useCase(signals, state, api).run()
        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertTrue(state.parkedOutside)   // still blocks the other phone
        assertEquals("XX123Y", api.active)
    }

    @Test
    fun `bluetooth blip while driving does nothing`() = runTest {
        val signals = ScriptedSignals(script = mapOf(0 to driving))
        val state = FakeParkStateStore()
        val api = SwitchApi()
        val outcome = useCase(signals, state, api).run()
        assertEquals(ParkOutcome.FalseAlarm, outcome)
        assertFalse(state.parked)
        assertEquals("XX123Y", api.active)
    }

    @Test
    fun `timeout with no evidence asks for a manual decision`() = runTest {
        val signals = ScriptedSignals()
        val notifier = RecordingParkNotifier()
        val api = SwitchApi()
        val outcome = useCase(signals, api = api, notifier = notifier).run()
        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertTrue(notifier.calls.contains("manual"))
        assertEquals("XX123Y", api.active)
    }

    @Test
    fun `network failure during switch is loud and reported`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val notifier = RecordingParkNotifier()
        val outcome = useCase(signals, api = SwitchApi(fail = true), notifier = notifier).run()
        assertEquals(ParkOutcome.SwitchFailed, outcome)
        assertTrue(notifier.calls.contains("failed"))
    }

    @Test
    fun `walid phone claims walid plate`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s),
            locations = mutableListOf(paidPoint))
        val state = FakeParkStateStore().apply { myCar = MyCar.WALID }
        val api = SwitchApi(active = "RH950F")
        val outcome = useCase(signals, state, api).run()
        assertEquals(ParkOutcome.Claimed("XX123Y"), outcome)
    }

    @Test
    fun `unconfigured phone does nothing`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s))
        val state = FakeParkStateStore().apply { myCar = null }
        val outcome = useCase(signals, state).run()
        assertEquals(ParkOutcome.NotConfigured, outcome)
    }
}
```

Adjust imports at the top of the test file as needed (`FreeZone`, `PhoneState`, etc. — the compiler will tell you).

- [ ] **Step 3: Run the reworked tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.parking.ParkDetectionUseCaseTest"`
Expected: 12 tests pass.

- [ ] **Step 4: Full run + commit**

Run: `.\gradlew.bat :app:testDebugUnitTest` — all green (the old free-zone test names are replaced by the new set; total goes up, nothing else breaks).

```bash
git add -A
git commit -m "feat: park decision table - zones, guard, give-back trigger, no blind claims"
```

---

### Task 10: Android shell — sync workers, reworked claim workers, receivers

This task lands the Phase 2 bug fixes in the shell: network constraints on
every network worker, claim retries that survive offline windows, stale-claim
cancellation on Bluetooth reconnect, and "Free here" reading a fresh location.
Shell classes hold no decision logic; they wire the pure core to WorkManager.
Verified by compilation + the manual on-device checklist (Task 13).

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/parking/android/SharedSync.kt`
- Modify: `android/app/src/main/java/dev/wasil/permit/parking/android/ParkWorkers.kt` (full rewrite)
- Modify: `android/app/src/main/java/dev/wasil/permit/parking/android/CarBluetoothReceiver.kt`
- Modify: `android/app/src/main/java/dev/wasil/permit/parking/android/ParkActionReceiver.kt`

**Interfaces:**
- Consumes: `PermitApp.sharedStateStore()`, `PermitApp.guardedClaim()`, `PermitApp.zoneResolver()`, `PermitApp.tariffAreas` (created in Task 11 — write the calls now; the project compiles at the END of Task 11, so Tasks 10+11 are committed together only after both are done. Do NOT run the build between them.)
- Produces: `SharedSync.requestSync(context)`, `SharedSync.requestGiveBack(context, executeNow: Boolean = false)`, `SharedSync.requestFreeHere(context)`, `SharedSync.ensureHeartbeat(context, parkedOutside)`, `SharedSync.cancelClaimChain(context)`; `ParkWorkers.enqueueDetection(context)`, `ParkWorkers.enqueueClaim(context, force: Boolean = false, userInitiated: Boolean = true)`; `WorkManagerScheduler(context) : ParkScheduler`.

- [ ] **Step 1: Write SharedSync.kt**

```kotlin
package dev.wasil.permit.parking.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.wasil.permit.PermitApp
import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GiveBackResult
import dev.wasil.permit.parking.MyCar
import dev.wasil.permit.parking.ParkScheduler
import dev.wasil.permit.parking.PrefsFreeZoneStore
import dev.wasil.permit.parking.PrefsParkStateStore
import dev.wasil.permit.parking.label
import dev.wasil.permit.parking.other
import dev.wasil.permit.parking.shared.ClaimGuard
import dev.wasil.permit.parking.shared.PhoneState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object SharedSync {
    const val SYNC_WORK = "sync_state"
    const val HEARTBEAT_WORK = "heartbeat"
    const val GIVE_BACK_WORK = "give_back"
    const val FREE_HERE_WORK = "mark_free_zone"

    private fun connected() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun requestSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncStateWorker>()
            .setConstraints(connected())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(SYNC_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun requestGiveBack(context: Context, executeNow: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<GiveBackWorker>()
            .setConstraints(connected())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf("executeNow" to executeNow))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(GIVE_BACK_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun requestFreeHere(context: Context) {
        val request = OneTimeWorkRequestBuilder<MarkFreeZoneWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(FREE_HERE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /** Heartbeat runs only while parked outside; KEEP avoids resetting the period. */
    fun ensureHeartbeat(context: Context, parkedOutside: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (parkedOutside) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
                .setConstraints(connected())
                .build()
            wm.enqueueUniquePeriodicWork(HEARTBEAT_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            wm.cancelUniqueWork(HEARTBEAT_WORK)
        }
    }

    /** Back in the car: any pending detection/claim/give-back is now stale. */
    fun cancelClaimChain(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(ParkWorkers.DETECTION_WORK)
        wm.cancelUniqueWork(ParkWorkers.CLAIM_WORK)
        wm.cancelUniqueWork(GIVE_BACK_WORK)
    }
}

class WorkManagerScheduler(private val context: Context) : ParkScheduler {
    override fun requestSync() = SharedSync.requestSync(context)
    override fun requestGiveBack() = SharedSync.requestGiveBack(context)
}

/** Pushes my current phone node; keeps the heartbeat schedule in step. */
class SyncStateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PermitApp
        val store = PrefsParkStateStore.from(applicationContext)
        val shared = app.sharedStateStore()
        if (!shared.configured) return Result.success()
        return try {
            val location = store.lastParkLocation.takeIf { store.parkedOutside }
            shared.writeMine(PhoneState(
                parkedOutside = store.parkedOutside,
                lat = location?.lat,
                lng = location?.lng,
                accuracyM = location?.accuracyM?.toDouble(),
                zoneCode = store.lastZoneCode.takeIf { store.parkedOutside },
                parkedAtMs = store.parkedAtMs,
            ))
            SharedSync.ensureHeartbeat(applicationContext, store.parkedOutside)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/** While parked outside: refresh my heartbeat and watch for permit takeovers. */
class HeartbeatWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PermitApp
        val store = PrefsParkStateStore.from(applicationContext)
        if (!store.parkedOutside) {
            SharedSync.ensureHeartbeat(applicationContext, false)
            return Result.success()
        }
        val shared = app.sharedStateStore()
        if (!shared.configured) return Result.success()
        return try {
            shared.heartbeat()
            val permit = shared.readPermit()
            val me = store.myCar
            if (permit != null && me != null && permit.holder != me.name.lowercase() &&
                permit.claimedAtMs > store.lastAlertedClaimMs
            ) {
                ParkNotifications(applicationContext).takeover(me.other().label())
                store.lastAlertedClaimMs = permit.claimedAtMs
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/**
 * Give the permit back after parking free. Auto-claim ON executes directly;
 * OFF only asks (notification) when a give-back would actually apply.
 * The "Give back" notification action re-runs this with executeNow = true.
 */
class GiveBackWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PermitApp
        val store = PrefsParkStateStore.from(applicationContext)
        val shared = app.sharedStateStore()
        if (!shared.configured) return Result.success()
        val executeNow = inputData.getBoolean("executeNow", false)

        if (store.autoClaim || executeNow) {
            return when (app.guardedClaim().giveBack()) {
                is GiveBackResult.Given -> Result.success()
                GiveBackResult.NothingToDo -> Result.success()
                GiveBackResult.Failed -> Result.retry()
            }
        }
        // Ask-first mode: check the conditions, then only notify.
        return try {
            val other = shared.readOther() ?: return Result.success()
            val fresh = other.parkedOutside &&
                System.currentTimeMillis() - other.heartbeatAtMs <= ClaimGuard.STALE_AFTER_MS
            if (!fresh) return Result.success()
            val config = app.credentialStore.load() ?: return Result.success()
            val mine = store.myCar ?: return Result.success()
            val myPlate = if (mine == MyCar.WASIL) config.wasilPlate else config.walidPlate
            if (app.repository.activePlate() == myPlate) {
                ParkNotifications(applicationContext).askGiveBack(mine.other().label())
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/** "Free here": read a FRESH location at tap time (Phase 2 stored a stale one). */
class MarkFreeZoneWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val point = PlayServicesSignals(applicationContext).currentLocation()
        val notifications = ParkNotifications(applicationContext)
        if (point == null) {
            notifications.eventNote("Couldn't get a location — free zone not marked. Try again outdoors.")
            return Result.success()
        }
        val store = PrefsParkStateStore.from(applicationContext)
        val date = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
        PrefsFreeZoneStore(
            applicationContext.getSharedPreferences("park_state", android.content.Context.MODE_PRIVATE),
        ).add(FreeZone(point.lat, point.lng, 60.0, "Marked $date"))
        store.parkedOutside = false
        store.lastZoneCode = null
        SharedSync.requestSync(applicationContext)
        notifications.statusParkedNoClaim("in a free zone (just marked)")
        return Result.success()
    }
}
```

- [ ] **Step 2: Rewrite ParkWorkers.kt**

```kotlin
package dev.wasil.permit.parking.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.core.app.NotificationCompat
import dev.wasil.permit.PermitApp
import dev.wasil.permit.parking.GuardedResult
import dev.wasil.permit.parking.ParkDetectionUseCase
import dev.wasil.permit.parking.ParkOutcome
import dev.wasil.permit.parking.PrefsParkStateStore

object ParkWorkers {
    const val DETECTION_WORK = "park_detection"
    const val CLAIM_WORK = "claim_permit"

    /** Detection needs no network; a failed claim is handed to enqueueClaim. */
    fun enqueueDetection(context: Context) {
        val request = OneTimeWorkRequestBuilder<ParkDetectionWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DETECTION_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * The claim retries HERE, behind a CONNECTED constraint: after the Phase 2
     * DNS failure, blind backoff retries fired while still offline and the
     * chain outlived the outage uselessly. Now WorkManager holds attempts
     * until connectivity is actually back.
     */
    fun enqueueClaim(context: Context, force: Boolean = false, userInitiated: Boolean = true) {
        val request = OneTimeWorkRequestBuilder<ClaimPermitWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .setInputData(workDataOf("force" to force, "userInitiated" to userInitiated))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(CLAIM_WORK, ExistingWorkPolicy.REPLACE, request)
    }
}

private fun foregroundInfo(context: Context): ForegroundInfo {
    val notification = NotificationCompat.Builder(context, ParkNotifications.CHANNEL_STATUS)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("Checking whether you parked…")
        .build()
    return ForegroundInfo(ParkNotifications.STATUS_ID, notification)
}

class ParkDetectionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext)

    override suspend fun doWork(): Result {
        val app = applicationContext as PermitApp
        val outcome = ParkDetectionUseCase(
            signals = PlayServicesSignals(applicationContext),
            stateStore = PrefsParkStateStore.from(applicationContext),
            zoneResolver = app.zoneResolver(),
            guardedClaim = app.guardedClaim(),
            notifier = ParkNotifications(applicationContext),
            scheduler = WorkManagerScheduler(applicationContext),
        ).run()
        // Retry the CLAIM, not the detection: we know we're parked.
        if (outcome == ParkOutcome.SwitchFailed) {
            ParkWorkers.enqueueClaim(applicationContext, userInitiated = false)
        }
        return Result.success()
    }
}

class ClaimPermitWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext)

    override suspend fun doWork(): Result {
        val app = applicationContext as PermitApp
        val store = PrefsParkStateStore.from(applicationContext)
        // Situation changed since this was enqueued (drove off / reset): stale claim must not fire.
        if (!store.parked) return Result.success()

        val force = inputData.getBoolean("force", false)
        val userInitiated = inputData.getBoolean("userInitiated", true)
        val zoneText = store.lastZoneCode?.let { code ->
            app.tariffAreas?.firstOrNull { it.code == code }
                ?.let { "${it.tariffText} zone ${it.code}" }
        }
        val notifications = ParkNotifications(applicationContext)

        return when (val result = app.guardedClaim()
            .claim(force = force, userInitiated = userInitiated, zoneText = zoneText)) {
            is GuardedResult.Blocked -> {
                notifications.blockedByOther(
                    result.otherLabel, result.other.parkedAtMs, result.other.heartbeatAtMs,
                )
                Result.success()
            }
            is GuardedResult.Done -> when (result.outcome) {
                ParkOutcome.SwitchFailed -> Result.retry()
                ParkOutcome.ManualNeeded -> {
                    notifications.askManualDecision()
                    Result.success()
                }
                else -> {
                    result.guardSkippedNote?.let { notifications.eventNote(it) }
                    SharedSync.requestSync(applicationContext)
                    Result.success()
                }
            }
        }
    }
}
```

- [ ] **Step 3: Update CarBluetoothReceiver**

Replace the `when (intent.action)` block:

```kotlin
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> ParkWorkers.enqueueDetection(context)
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                // Back in the car: driving again, everything pending is stale.
                SharedSync.cancelClaimChain(context)
                store.parked = false
                store.parkedOutside = false
                store.lastZoneCode = null
                SharedSync.requestSync(context)
                ParkNotifications.dismissEvents(context)
            }
        }
```

Remove the now-unused `import androidx.work.WorkManager`.

- [ ] **Step 4: Update ParkActionReceiver**

Replace the `onReceive` body (keep the companion constants from Task 8):

```kotlin
        when (intent.action) {
            ACTION_CLAIM -> ParkWorkers.enqueueClaim(context)
            ACTION_CLAIM_FORCE -> ParkWorkers.enqueueClaim(context, force = true)
            ACTION_GIVE_BACK -> {
                SharedSync.requestGiveBack(context, executeNow = true)
                ParkNotifications.dismissEvents(context)
            }
            ACTION_IGNORE -> ParkNotifications.dismissEvents(context)
            ACTION_FREE_HERE -> {
                SharedSync.requestFreeHere(context)
                ParkNotifications.dismissEvents(context)
            }
        }
```

Remove the now-unused imports (`FreeZone`, `PrefsFreeZoneStore`, `PrefsParkStateStore`, `SimpleDateFormat`, `Date`, `Locale`).

- [ ] **Step 5: Do NOT build yet**

This task references `PermitApp.sharedStateStore()` / `guardedClaim()` / `zoneResolver()` / `tariffAreas`, which Task 11 creates. Continue straight into Task 11; build and commit happen there.

---

### Task 11: Wiring — PermitApp, MainViewModel, MainScreen, MainActivity

**Files:**
- Modify: `android/app/src/main/java/dev/wasil/permit/PermitApp.kt`
- Modify: `android/app/src/main/java/dev/wasil/permit/ui/MainViewModel.kt`
- Modify: `android/app/src/main/java/dev/wasil/permit/ui/MainScreen.kt`
- Modify: `android/app/src/main/java/dev/wasil/permit/MainActivity.kt`

**Interfaces:**
- Consumes: everything from Tasks 3–10.
- Produces: `PermitApp.sharedStateStore(): SharedStateStore`, `PermitApp.guardedClaim(): GuardedClaim`, `PermitApp.zoneResolver(): ZoneResolver`, `PermitApp.tariffAreas: List<TariffArea>?`; `MainViewModel(repository, credentialStore, stateStore, guardedClaim: () -> GuardedClaim, sharedStore: () -> SharedStateStore)`; `UiState` gains `otherStatus: String?` and `blocked: BlockedInfo?`; `data class BlockedInfo(option: PlateOption, otherLabel: String, parkedAtMs: Long, heartbeatAtMs: Long)`.

- [ ] **Step 1: Extend PermitApp**

Add to `PermitApp` (new imports: `GuardedClaim`, `ClaimPermit`, `MyCar` extensions, `RtdbSharedStateStore`, `SharedStateStore`, `UnconfiguredSharedStateStore`, `roomIdFor`, `TariffArea`, `TariffAreas`, `ZoneResolver`, `ParkNotifications`, `toHttpUrlOrNull`, `OkHttpClient`):

```kotlin
    /** Plain client for RTDB — the permit client's auth/headers must not leak there. */
    private val plainHttp by lazy { okhttp3.OkHttpClient() }

    /** Bundled Amsterdam tariff areas; null when the asset is missing/corrupt. */
    val tariffAreas: List<TariffArea>? by lazy {
        runCatching {
            assets.open("amsterdam_tarieven.json").bufferedReader().use { it.readText() }
        }.mapCatching { TariffAreas.parse(it).takeIf { areas -> areas.isNotEmpty() } }
            .getOrNull()
    }

    /** Rebuilt per call: settings (URL, my car, credentials) can change at runtime. */
    fun sharedStateStore(): SharedStateStore {
        val url = parkStateStore.syncUrl?.toHttpUrlOrNull() ?: return UnconfiguredSharedStateStore
        val username = credentialStore.load()?.username ?: return UnconfiguredSharedStateStore
        val me = parkStateStore.myCar ?: return UnconfiguredSharedStateStore
        return RtdbSharedStateStore(
            baseUrl = url,
            room = roomIdFor(username),
            me = me.key(),
            other = me.other().key(),
            client = plainHttp,
        )
    }

    fun guardedClaim(): GuardedClaim {
        val notifications = ParkNotifications(this)
        return GuardedClaim(
            repository, credentialStore, parkStateStore, sharedStateStore(),
            ClaimPermit(repository, credentialStore, parkStateStore, notifications),
        )
    }

    fun zoneResolver(): ZoneResolver =
        ZoneResolver(parkStateStore.homeZone, freeZoneStore.all(), tariffAreas)
```

- [ ] **Step 2: Extend MainViewModel**

Replace the file's `UiState`/constructor/`switchTo`/`refresh` (keep `saveSetup`, `consumeMessage`, `PlateOption`, `toOptions`):

```kotlin
data class BlockedInfo(
    val option: PlateOption,
    val otherLabel: String,
    val parkedAtMs: Long,
    val heartbeatAtMs: Long,
)

data class UiState(
    val needsSetup: Boolean = false,
    val loading: Boolean = false,
    val switching: String? = null,
    val activeVrn: String? = null,
    val options: List<PlateOption> = emptyList(),
    val message: String? = null,
    val otherStatus: String? = null,
    val blocked: BlockedInfo? = null,
)

class MainViewModel(
    private val repository: PermitRepository,
    private val credentialStore: CredentialStore,
    private val stateStore: ParkStateStore,
    private val guardedClaim: () -> GuardedClaim,
    private val sharedStore: () -> SharedStateStore,
) : ViewModel() {
```

New imports: `dev.wasil.permit.parking.*` (`GuardedClaim`, `GuardedResult`, `MyCar`, `ParkOutcome`, `ParkStateStore`, `label`, `other`), `dev.wasil.permit.parking.shared.ClaimGuard`, `dev.wasil.permit.parking.shared.SharedStateStore`, `java.text.SimpleDateFormat`, `java.util.Date`, `java.util.Locale`.

```kotlin
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { repository.activePlate() }
                .onSuccess { active ->
                    _state.update { it.copy(loading = false, activeVrn = active) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, message = "Couldn't load permit state: ${e.message}")
                    }
                }
            _state.update { it.copy(otherStatus = loadOtherStatus()) }
        }
    }

    private suspend fun loadOtherStatus(): String? {
        val store = sharedStore()
        if (!store.configured) return null
        val label = stateStore.myCar?.other()?.label() ?: return null
        return runCatching {
            val other = store.readOther()
            val time = { ms: Long -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms)) }
            when {
                other == null -> "$label: no data yet"
                !other.parkedOutside -> "$label: not parked outside"
                System.currentTimeMillis() - other.heartbeatAtMs > ClaimGuard.STALE_AFTER_MS ->
                    "$label: parked (stale — last seen ${time(other.heartbeatAtMs)})"
                else -> "$label: parked outside since ${time(other.parkedAtMs)}"
            }
        }.getOrDefault("$label: status unavailable")
    }

    fun switchTo(option: PlateOption, force: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(switching = option.vrn, blocked = null) }
            val target = if (option.label == "Wasil") MyCar.WASIL else MyCar.WALID
            when (val result = guardedClaim().claim(
                target = target, force = force, userInitiated = true)) {
                is GuardedResult.Blocked -> _state.update {
                    it.copy(switching = null, blocked = BlockedInfo(
                        option, result.otherLabel,
                        result.other.parkedAtMs, result.other.heartbeatAtMs))
                }
                is GuardedResult.Done -> when (val outcome = result.outcome) {
                    is ParkOutcome.Claimed -> _state.update {
                        it.copy(
                            switching = null, activeVrn = outcome.vrn,
                            message = listOfNotNull(
                                "Permit confirmed on ${option.label}'s car (${outcome.vrn})",
                                result.guardSkippedNote,
                            ).joinToString(" — "),
                        )
                    }
                    is ParkOutcome.MismatchDetected -> _state.update {
                        it.copy(
                            switching = null, activeVrn = outcome.serverVrn,
                            message = "WARNING: server reports ${outcome.serverVrn ?: "no plate"} active - check the website!",
                        )
                    }
                    ParkOutcome.NotConfigured -> _state.update {
                        it.copy(switching = null, message = "Finish setup first (credentials + whose phone in Settings)")
                    }
                    else -> _state.update {
                        it.copy(switching = null, message = "Switch failed. Permit NOT changed - retry.")
                    }
                }
            }
        }
    }

    fun confirmBlockedSwitch() {
        state.value.blocked?.let { switchTo(it.option, force = true) }
    }

    fun dismissBlocked() = _state.update { it.copy(blocked = null) }
```

- [ ] **Step 3: MainScreen — status row, blocked dialog, map button**

New composable parameters and content. Update the signature:

```kotlin
@Composable
fun MainScreen(
    state: UiState,
    onSwitch: (PlateOption) -> Unit,
    onRefresh: () -> Unit,
    onMessageShown: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMap: () -> Unit,
    onConfirmBlocked: () -> Unit,
    onDismissBlocked: () -> Unit,
)
```

Inside the `Column`, after the plate buttons `forEach` block, add:

```kotlin
            state.otherStatus?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
```

Change the trailing buttons block to:

```kotlin
            TextButton(onClick = onRefresh, enabled = !state.loading && state.switching == null) {
                Text("Refresh")
            }
            TextButton(onClick = onOpenMap) { Text("Map") }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
```

At the end of the `Scaffold` content (still inside the lambda, after the `Column`), add the dialog:

```kotlin
        state.blocked?.let { blocked ->
            val time = { ms: Long ->
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(ms))
            }
            AlertDialog(
                onDismissRequest = onDismissBlocked,
                title = { Text("${blocked.otherLabel}'s car is parked") },
                text = {
                    Text(
                        "${blocked.otherLabel} parked at ${time(blocked.parkedAtMs)} " +
                            "(last seen ${time(blocked.heartbeatAtMs)}) and the permit is on their car. " +
                            "Claiming now would leave it unpermitted — that's a fine if it's still there.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = onConfirmBlocked) { Text("Claim anyway") }
                },
                dismissButton = {
                    TextButton(onClick = onDismissBlocked) { Text("Cancel") }
                },
            )
        }
```

Add `import androidx.compose.material3.AlertDialog`.

- [ ] **Step 4: MainActivity — factory, map navigation, takeover deep-link**

Replace the ViewModel factory's `create` body and the `setContent` block:

```kotlin
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as PermitApp
                return MainViewModel(
                    app.repository, app.credentialStore, app.parkStateStore,
                    guardedClaim = { app.guardedClaim() },
                    sharedStore = { app.sharedStateStore() },
                ) as T
            }
```

```kotlin
        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }
                var showMap by remember { mutableStateOf(false) }
                when {
                    state.needsSetup -> SetupScreen(onSave = viewModel::saveSetup)
                    showSettings -> {
                        BackHandler { showSettings = false }
                        SettingsScreen(
                            stateStore = app.parkStateStore,
                            freeZoneStore = app.freeZoneStore,
                            sharedStore = { app.sharedStateStore() },
                            onBack = { showSettings = false },
                        )
                    }
                    showMap -> {
                        BackHandler { showMap = false }
                        MapScreen(
                            stateStore = app.parkStateStore,
                            onBack = { showMap = false },
                        )
                    }
                    else -> MainScreen(
                        state = state,
                        onSwitch = viewModel::switchTo,
                        onRefresh = viewModel::refresh,
                        onMessageShown = viewModel::consumeMessage,
                        onOpenSettings = { showSettings = true },
                        onOpenMap = { showMap = true },
                        onConfirmBlocked = viewModel::confirmBlockedSwitch,
                        onDismissBlocked = viewModel::dismissBlocked,
                    )
                }
            }
        }
```

Add imports `dev.wasil.permit.ui.MapScreen`. `MapScreen` and the new
`SettingsScreen` parameter don't exist until Task 12 — to keep this task
buildable, create a minimal placeholder now in
`android/app/src/main/java/dev/wasil/permit/ui/MapScreen.kt` (Task 12 replaces
its body):

```kotlin
package dev.wasil.permit.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.wasil.permit.parking.ParkStateStore

@Composable
fun MapScreen(stateStore: ParkStateStore, onBack: () -> Unit) {
    Text("Map coming in Task 12")
}
```

and add the `sharedStore: () -> dev.wasil.permit.parking.shared.SharedStateStore` parameter to `SettingsScreen` now (unused until Task 12):

```kotlin
fun SettingsScreen(
    stateStore: ParkStateStore,
    freeZoneStore: FreeZoneStore,
    sharedStore: () -> dev.wasil.permit.parking.shared.SharedStateStore,
    onBack: () -> Unit,
) {
```

- [ ] **Step 5: Build + full test run**

Run: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, everything green (Tasks 10 + 11 compile together).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: android shell + wiring - constrained retry workers, sync/heartbeat/give-back, guarded main-screen switching"
```

---

### Task 12: Map screen + Settings additions

**Files:**
- Modify: `android/app/src/main/java/dev/wasil/permit/ui/MapScreen.kt` (replace placeholder)
- Modify: `android/app/src/main/java/dev/wasil/permit/ui/SettingsScreen.kt`

**Interfaces:**
- Consumes: `ParkStateStore` (incl. `homeZone`, `syncUrl`), `PlayServicesSignals.currentLocation()`, `SharedStateStore.heartbeat()`, osmdroid `MapView`/`Marker`.
- Produces: final `MapScreen(stateStore, onBack)`; SettingsScreen sections for home zone, sync URL + test, battery optimization.

- [ ] **Step 1: Implement MapScreen**

Replace `MapScreen.kt` entirely:

```kotlin
package dev.wasil.permit.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.ParkStateStore
import dev.wasil.permit.parking.android.PlayServicesSignals
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Personal map: my car's last parked spot + my current position. Nothing here
 * is shared with the other phone (deliberate — see the Phase 3 spec).
 */
@SuppressLint("MissingPermission")
@Composable
fun MapScreen(stateStore: ParkStateStore, onBack: () -> Unit) {
    val context = LocalContext.current
    var me by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(Unit) {
        // One-shot read while the screen is open; no tracking.
        me = PlayServicesSignals(context).currentLocation()
    }
    val car = stateStore.lastParkLocation
    val parkedAt = stateStore.parkedAtMs.takeIf { it > 0 && stateStore.parked }
        ?.let { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(it)) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Map", style = MaterialTheme.typography.headlineSmall)
        Text(
            when {
                car == null -> "No parked location recorded yet."
                parkedAt != null -> "Car parked $parkedAt."
                else -> "Last known car position shown."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(16.0)
                    val center = car ?: me
                    controller.setCenter(org.osmdroid.util.GeoPoint(
                        center?.lat ?: 52.3702, center?.lng ?: 4.8952))
                }
            },
            update = { map ->
                map.overlays.removeAll { it is Marker }
                car?.let {
                    map.overlays.add(Marker(map).apply {
                        position = org.osmdroid.util.GeoPoint(it.lat, it.lng)
                        title = "My car"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                }
                me?.let {
                    map.overlays.add(Marker(map).apply {
                        position = org.osmdroid.util.GeoPoint(it.lat, it.lng)
                        title = "Me"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                }
                map.invalidate()
            },
        )
        Text("© OpenStreetMap contributors", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}
```

- [ ] **Step 2: Settings — home zone, sync, battery sections**

In `SettingsScreen.kt` (signature already has `sharedStore` from Task 11), add
imports:

```kotlin
import android.os.PowerManager
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.runtime.rememberCoroutineScope
import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.android.PlayServicesSignals
import dev.wasil.permit.parking.android.SharedSync
import kotlinx.coroutines.launch
```

Add state near the other `remember` blocks:

```kotlin
    val scope = rememberCoroutineScope()
    var homeZone by remember { mutableStateOf(stateStore.homeZone) }
    var homeStatus by remember { mutableStateOf<String?>(null) }
    var syncUrl by remember { mutableStateOf(stateStore.syncUrl ?: "") }
    var syncStatus by remember { mutableStateOf<String?>(null) }
```

Insert a **Home zone** section between the "Auto-claim" row and "Free zones":

```kotlin
        Text("Home zone", style = MaterialTheme.typography.titleMedium)
        Text(
            homeZone?.let { "Home set: %.5f, %.5f (radius %.0f m)".format(it.lat, it.lng, it.radiusM) }
                ?: "Not set. Parking inside your home zone never claims the permit.",
            style = MaterialTheme.typography.bodyMedium,
        )
        homeZone?.let { zone ->
            Text("Radius: %.0f m".format(zone.radiusM))
            Slider(
                value = zone.radiusM.toFloat(),
                onValueChange = {
                    val updated = zone.copy(radiusM = it.toDouble())
                    stateStore.homeZone = updated
                    homeZone = updated
                },
                valueRange = 30f..200f,
            )
        }
        Button(onClick = {
            homeStatus = "Getting location…"
            scope.launch {
                val point = PlayServicesSignals(context).currentLocation()
                if (point == null) {
                    homeStatus = "Couldn't get a location — check location permission and try outdoors."
                } else {
                    val zone = FreeZone(point.lat, point.lng, homeZone?.radiusM ?: 60.0, "Home")
                    stateStore.homeZone = zone
                    homeZone = zone
                    homeStatus = "Home saved."
                }
            }
        }) { Text(if (homeZone == null) "Set home to current location" else "Move home here") }
        if (homeZone != null) {
            TextButton(onClick = {
                stateStore.homeZone = null
                homeZone = null
                homeStatus = null
            }) { Text("Clear home zone") }
        }
        homeStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
```

Insert a **Shared state** section right after (before "Free zones"):

```kotlin
        Text("Shared state (Firebase)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Database URL from SETUP_FIREBASE.md. Both phones must use the same URL.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = syncUrl,
            onValueChange = { syncUrl = it },
            label = { Text("https://…firebasedatabase.app") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            Button(onClick = {
                stateStore.syncUrl = syncUrl.trim().ifBlank { null }
                syncStatus = "Saved."
                SharedSync.requestSync(context)
            }) { Text("Save") }
            TextButton(onClick = {
                syncStatus = "Testing…"
                scope.launch {
                    syncStatus = runCatching { sharedStore().heartbeat() }
                        .fold({ "Connection OK." }, { "FAILED: ${it.message}" })
                }
            }) { Text("Test connection") }
        }
        syncStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
```

Insert a **Battery** section just before the final "Back" button:

```kotlin
        Text("Battery", style = MaterialTheme.typography.titleMedium)
        val powerManager = context.getSystemService(PowerManager::class.java)
        val ignoring = remember(refresh) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        }
        Text(
            if (ignoring) "Battery optimization is OFF for this app — good."
            else "Samsung/Android may put this app to sleep and miss park events. Turn optimization off.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!ignoring) {
            Button(onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
                refresh++
            }) { Text("Disable battery optimization") }
        }
        HorizontalDivider()
```

Note: `refresh++` requires `refresh` to be `var refresh by remember { mutableIntStateOf(0) }` — it already is.

- [ ] **Step 3: Build + tests**

Run: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: personal map (osmdroid) + settings for home zone, sync url, battery optimization"
```

---

### Task 13: Docs, version bump, final verification

**Files:**
- Create: `SETUP_FIREBASE.md` (repo root)
- Modify: `README.md` (data attribution + Phase 3 summary — adapt to the README's existing structure)
- Modify: `android/app/build.gradle.kts` (`versionCode = 3`, `versionName = "0.3"`)
- Create: `docs/phase3-manual-test-checklist.md`

- [ ] **Step 1: Write SETUP_FIREBASE.md**

```markdown
# One-time Firebase setup (Wasil does this once)

The app shares "who is parked where" between the two phones through a free
Firebase Realtime Database, accessed directly over HTTPS. No Firebase SDK, no
google-services.json — only a database URL entered in the app.

**The database URL is a secret. Never commit it to this repo (repo and APKs
are public). It lives only in the app's Settings on the two phones.**

1. Go to https://console.firebase.google.com → **Add project**. Name it
   anything (e.g. `permit-switcher`). Disable Analytics. Free Spark plan — no
   credit card.
2. In the left menu: **Build → Realtime Database → Create Database**. Choose
   **Belgium (europe-west1)**. Start in **locked mode**.
3. Open the **Rules** tab, replace the contents with exactly this, and publish:

   ```json
   { "rules": { "rooms": { "$room": { ".read": true, ".write": true } } } }
   ```

   The root stays unreadable, so nobody can list rooms. Each room path is
   derived from the permit username (128-bit hash) — unguessable in practice.
4. Copy the database URL shown at the top of the Data tab. It looks like
   `https://permit-switcher-default-rtdb.europe-west1.firebasedatabase.app`.
5. On BOTH phones: app → Settings → **Shared state (Firebase)** → paste the
   URL → Save → **Test connection** must say "Connection OK."

Both phones must be on app version 0.3+ and have the same permit credentials;
the shared room is derived from the username automatically.

What is stored there: for each phone, whether it is parked in a paid zone,
the parked coordinates, and timestamps; plus which plate last claimed the
permit. Anyone who somehow learned the URL AND the room hash could read that.
Acceptable for a two-person family tool; revisit with Firebase Auth if that
ever changes.
```

- [ ] **Step 2: Version bump**

In `android/app/build.gradle.kts`: `versionCode = 3`, `versionName = "0.3"`.
(If Phase 2 already bumped to 2/0.2, this is one step up from that; make the
result 3/"0.3" regardless.)

- [ ] **Step 3: README updates**

Add to the README (fit its existing tone/sections):
- Phase 3 summary: shared parked state via Firebase RTDB REST, collision
  guard + override, home zone, Amsterdam tariff areas, give-back, personal
  map, and the Phase 2 fixes (offline-safe retries, no blind claims without
  GPS, fresh-location "Free here", stale-claim cancellation).
- Data attribution: "Parking tariff areas: © Gemeente Amsterdam,
  parkeertarieven dataset (maps.amsterdam.nl), CC-BY 4.0, snapshot downloaded
  2026-07-29 from
  https://amsterdam-maps.bma-collective.com/embed/parkeren/deploy_data/tarieven.json —
  bundled as `android/app/src/main/assets/amsterdam_tarieven.json`."
  and "Map tiles: © OpenStreetMap contributors."
- Pointer to `SETUP_FIREBASE.md`.

- [ ] **Step 4: Manual test checklist**

Write `docs/phase3-manual-test-checklist.md`:

```markdown
# Phase 3 on-device checklist (needs both phones on 0.3)

Setup: Firebase URL saved + "Connection OK" on both; home zone set on both;
battery optimization disabled on both.

1. **Paid-zone park:** drive somewhere paid, park, walk away. Expect:
   status notification "Permit on <me>'s car" with €-rate + zone code.
   Other phone's main screen shows "<me>: parked outside since HH:MM".
2. **Collision:** with car 1 still parked, park car 2 in a paid zone.
   Expect on phone 2: "…'s car is parked — permit NOT claimed" with
   Claim anyway / Ignore. Permit unchanged on the website.
3. **Override + takeover alert:** tap Claim anyway on phone 2. Phone 1 gets
   "<other> took the permit" within ~15 min. Reclaim from that notification
   works (and warns, since car 2 is now the parked holder).
4. **Give-back:** phone 2 parked+holding, phone 1 drives home and parks in
   the home zone. Expect phone 1: "Parked — permit untouched (at home)";
   permit switches to car 2 automatically ("Permit on <other>'s car").
5. **Home zone:** park at home. Expect no claim, no block ("at home").
6. **Free street:** park outside any paid polygon (e.g. far suburb).
   Expect "free street parking (outside paid zones)", no claim.
7. **Offline retry (the Phase 2 bug):** airplane mode ON, park in a paid
   zone, keep airplane mode for 5+ min, turn it off. Expect the claim to
   fire shortly after connectivity returns, without tapping anything.
8. **Free here fresh location:** on a manual-decision notification tap
   "Free here" while standing at the spot. Settings → Free zones shows a
   zone at the CURRENT location.
9. **Main-screen guard:** with the other car parked+holding, tap "Set to
   my car" on the main screen. Expect the warning dialog; Cancel leaves the
   permit; Claim anyway switches it.
10. **Back in car:** reconnect Bluetooth. Status stays, park notifications
    dismissed, other phone sees "not parked outside" within a minute.
```

- [ ] **Step 5: Full clean verification**

Run: `.\gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, every test green. Note the total test count.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "docs: Firebase setup guide, attribution, manual checklist; version 0.3"
```

---

## Plan Self-Review Notes

- Spec coverage: collision guard (T7–T9, T11), override + forced record
  (T8, T10), home zone (T6, T12), tariff polygons (T4–T6), give-back
  (T8–T10), staleness (T7), heartbeat + takeover (T10), map (T12), sync
  URL + room derivation (T2, T3, T11, T12), all five Phase 2 bug fixes
  (T9 fix 2; T10 fixes 1, 3, 4; T8+T10 fix 5), setup doc (T13). ✔
- Type consistency: `SharedStateStore` methods, `GuardedResult`,
  `GiveBackResult`, `ParkScheduler`, store fields — names match across
  Tasks 3/7/8/9/10/11. ✔
- Tasks 10+11 are one compile unit (single build+commit at end of 11),
  stated explicitly in both tasks. ✔






