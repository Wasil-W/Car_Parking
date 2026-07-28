# Phase 2 Parked Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-detect parking via car-Bluetooth disconnect confirmed by Activity Recognition/GPS, auto-claim the permit for this phone's plate with read-after-write verification, keep a persistent status notification, and clear state on Bluetooth reconnect.

**Architecture:** A manifest-registered receiver catches the car's ACL disconnect and enqueues an expedited WorkManager job. The job runs `ParkDetectionUseCase` — a pure-Kotlin orchestrator (poll `DetectionSignals` → `ParkDecisionEngine` → free-zone check → `ClaimPermit`) that is fully unit-tested with fakes. Android-specific classes (receivers, Play Services signals, workers, notifications, Settings UI) stay thin shells around it.

**Tech Stack:** Everything from Phase 1, plus `androidx.work:work-runtime-ktx` 2.10.0 and `com.google.android.gms:play-services-location` 21.3.0.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-28-phase2-parked-detection-design.md`. Decision table thresholds: confidence ≥ 70, STILL only after ≥ 5000 ms, walk displacement > 10 m with accuracy ≤ 25 m, timeout 90 000 ms, free-zone radius 60 m.
- Never auto-switch on `Unclear` or `FalseAlarm`. A failed switch must never be silent.
- There is no "release permit" API call; "Free here" only stores a local free zone and skips switching.
- All new pure logic lives under `dev.wasil.permit.parking` and must have no Android imports.
- Package `dev.wasil.permit`; build commands run from `android/`; all Phase 1 tests must stay green.
- Branch: `phase2-parked-detection`.

---

### Task 1: Dependencies, permissions, manifest plumbing

**Files:**
- Modify: `android/gradle/libs.versions.toml`, `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: buildable project with WorkManager + play-services-location on the classpath and all Phase 2 permissions/receiver declarations in the manifest (receiver classes arrive in Task 7; manifest entries are added in Task 7 with them so every commit compiles).

- [ ] **Step 1: Version catalog additions** (`[versions]` and `[libraries]`):

```toml
workManager = "2.10.0"
playServicesLocation = "21.3.0"

androidx-work-runtime = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workManager" }
play-services-location = { group = "com.google.android.gms", name = "play-services-location", version.ref = "playServicesLocation" }
```

- [ ] **Step 2: app dependencies** (add to `dependencies` block):

```kotlin
implementation(libs.androidx.work.runtime)
implementation(libs.play.services.location)
```

- [ ] **Step 3: Manifest permissions** (above `<application>`):

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

- [ ] **Step 4: Build**: `.\gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat: Phase 2 dependencies and permissions`.

---

### Task 2: Domain models + haversine distance

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/parking/ParkSignals.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/parking/ParkSignalsTest.kt`

**Interfaces:**
- Produces: `ActivityType { IN_VEHICLE, STILL, ON_FOOT, OTHER }`, `ActivitySample(type, confidence, elapsedMs)`, `GeoPoint(lat, lng, accuracyM)`, `fun distanceMeters(a: GeoPoint, b: GeoPoint): Double`.

- [ ] **Step 1: Failing test**

```kotlin
package dev.wasil.permit.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkSignalsTest {
    @Test
    fun `distance between identical points is zero`() {
        val p = GeoPoint(52.3702, 4.8952, 10f)
        assertEquals(0.0, distanceMeters(p, p), 0.001)
    }

    @Test
    fun `distance of roughly 111m per thousandth of latitude`() {
        val a = GeoPoint(52.3702, 4.8952, 10f)
        val b = GeoPoint(52.3712, 4.8952, 10f)
        assertEquals(111.2, distanceMeters(a, b), 2.0)
    }

    @Test
    fun `ten meter walk is measurable`() {
        val a = GeoPoint(52.370200, 4.895200, 5f)
        val b = GeoPoint(52.370290, 4.895200, 5f) // ~10 m north
        assertTrue(distanceMeters(a, b) in 8.0..12.0)
    }
}
```

- [ ] **Step 2: Run red** — `--tests "dev.wasil.permit.parking.ParkSignalsTest"` fails to compile.
- [ ] **Step 3: Implementation**

```kotlin
package dev.wasil.permit.parking

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class ActivityType { IN_VEHICLE, STILL, ON_FOOT, OTHER }

data class ActivitySample(val type: ActivityType, val confidence: Int, val elapsedMs: Long)

data class GeoPoint(val lat: Double, val lng: Double, val accuracyM: Float)

/** Haversine great-circle distance. */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}
```

- [ ] **Step 4: Run green.** **Step 5: Commit** `feat: parking domain models and haversine distance`.

---

### Task 3: Decision engine

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/parking/ParkDecisionEngine.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/parking/ParkDecisionEngineTest.kt`

**Interfaces:**
- Produces: `sealed interface Decision` (`FalseAlarm`, `ParkedInCar`, `ParkedWalkedAway`, `Unclear` — all objects) and `object ParkDecisionEngine { fun decide(samples: List<ActivitySample>, disconnectPoint: GeoPoint?, latestPoint: GeoPoint?, elapsedMs: Long): Decision? }`. `null` = keep sampling. Constants: `CONFIDENCE = 70`, `MIN_STILL_DELAY_MS = 5_000L`, `WALK_DISTANCE_M = 10.0`, `MAX_ACCURACY_M = 25f`, `TIMEOUT_MS = 90_000L`.

- [ ] **Step 1: Failing test**

```kotlin
package dev.wasil.permit.parking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParkDecisionEngineTest {
    private val here = GeoPoint(52.3702, 4.8952, 10f)
    private val tenMetersAway = GeoPoint(52.370290, 4.8952, 10f)

    private fun decide(
        samples: List<ActivitySample> = emptyList(),
        from: GeoPoint? = null,
        to: GeoPoint? = null,
        elapsed: Long = 10_000,
    ) = ParkDecisionEngine.decide(samples, from, to, elapsed)

    @Test
    fun `no evidence keeps sampling`() = assertNull(decide())

    @Test
    fun `in-vehicle activity means bluetooth blip - false alarm`() {
        assertEquals(Decision.FalseAlarm,
            decide(listOf(ActivitySample(ActivityType.IN_VEHICLE, 80, 6_000))))
    }

    @Test
    fun `low confidence in-vehicle is ignored`() {
        assertNull(decide(listOf(ActivitySample(ActivityType.IN_VEHICLE, 40, 6_000))))
    }

    @Test
    fun `still after five seconds means parked in car`() {
        assertEquals(Decision.ParkedInCar,
            decide(listOf(ActivitySample(ActivityType.STILL, 85, 6_000))))
    }

    @Test
    fun `still too early is ignored - could be a red light blip`() {
        assertNull(decide(listOf(ActivitySample(ActivityType.STILL, 85, 3_000))))
    }

    @Test
    fun `walking means parked and walked away`() {
        assertEquals(Decision.ParkedWalkedAway,
            decide(listOf(ActivitySample(ActivityType.ON_FOOT, 75, 8_000))))
    }

    @Test
    fun `gps displacement over ten meters means walked away`() {
        assertEquals(Decision.ParkedWalkedAway, decide(from = here, to = tenMetersAway))
    }

    @Test
    fun `gps displacement with in-vehicle activity is not a walk`() {
        assertEquals(Decision.FalseAlarm,
            decide(listOf(ActivitySample(ActivityType.IN_VEHICLE, 90, 6_000)), here, tenMetersAway))
    }

    @Test
    fun `inaccurate gps fix is not trusted`() {
        assertNull(decide(from = here, to = tenMetersAway.copy(accuracyM = 50f)))
    }

    @Test
    fun `timeout with nothing conclusive is unclear`() {
        assertEquals(Decision.Unclear, decide(elapsed = 90_000))
    }
}
```

- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implementation**

```kotlin
package dev.wasil.permit.parking

sealed interface Decision {
    data object FalseAlarm : Decision
    data object ParkedInCar : Decision
    data object ParkedWalkedAway : Decision
    data object Unclear : Decision
}

/**
 * Pure decision table over accumulated signals. Returns null while evidence is
 * insufficient - the caller keeps sampling until TIMEOUT_MS, then gets Unclear.
 * Auto-switching is only ever allowed on ParkedInCar / ParkedWalkedAway.
 */
object ParkDecisionEngine {
    const val CONFIDENCE = 70
    const val MIN_STILL_DELAY_MS = 5_000L
    const val WALK_DISTANCE_M = 10.0
    const val MAX_ACCURACY_M = 25f
    const val TIMEOUT_MS = 90_000L

    fun decide(
        samples: List<ActivitySample>,
        disconnectPoint: GeoPoint?,
        latestPoint: GeoPoint?,
        elapsedMs: Long,
    ): Decision? {
        val confident = samples.filter { it.confidence >= CONFIDENCE }
        // Driving again beats everything: a BT drop mid-drive must never switch.
        if (confident.any { it.type == ActivityType.IN_VEHICLE }) return Decision.FalseAlarm
        if (confident.any { it.type == ActivityType.ON_FOOT }) return Decision.ParkedWalkedAway
        if (confident.any { it.type == ActivityType.STILL && it.elapsedMs >= MIN_STILL_DELAY_MS }) {
            return Decision.ParkedInCar
        }
        if (disconnectPoint != null && latestPoint != null &&
            disconnectPoint.accuracyM <= MAX_ACCURACY_M && latestPoint.accuracyM <= MAX_ACCURACY_M &&
            distanceMeters(disconnectPoint, latestPoint) > WALK_DISTANCE_M
        ) {
            return Decision.ParkedWalkedAway
        }
        return if (elapsedMs >= TIMEOUT_MS) Decision.Unclear else null
    }
}
```

- [ ] **Step 4: Run green.** **Step 5: Commit** `feat: park decision engine`.

---

### Task 4: Free zones

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/parking/FreeZones.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/parking/PrefsFreeZoneStore.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/parking/FreeZonesTest.kt`
- Test fixture: `android/app/src/test/java/dev/wasil/permit/parking/FakeFreeZoneStore.kt`

**Interfaces:**
- Produces:

```kotlin
@Serializable data class FreeZone(val lat: Double, val lng: Double, val radiusM: Double = 60.0, val label: String = "")
fun isInFreeZone(point: GeoPoint, zones: List<FreeZone>): Boolean
interface FreeZoneStore { fun all(): List<FreeZone>; fun add(zone: FreeZone); fun removeAt(index: Int) }
class PrefsFreeZoneStore(prefs: SharedPreferences) : FreeZoneStore  // JSON list under key "free_zones"
class FakeFreeZoneStore(zones: MutableList<FreeZone> = mutableListOf()) : FreeZoneStore
```

- [ ] **Step 1: Failing test**

```kotlin
package dev.wasil.permit.parking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeZonesTest {
    private val home = FreeZone(52.3702, 4.8952, radiusM = 60.0, label = "Home")

    @Test
    fun `point inside zone radius is free`() {
        assertTrue(isInFreeZone(GeoPoint(52.3703, 4.8952, 10f), listOf(home))) // ~11 m away
    }

    @Test
    fun `point outside radius is not free`() {
        assertFalse(isInFreeZone(GeoPoint(52.3712, 4.8952, 10f), listOf(home))) // ~111 m away
    }

    @Test
    fun `no zones means never free`() {
        assertFalse(isInFreeZone(GeoPoint(52.3702, 4.8952, 10f), emptyList()))
    }
}
```

- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implementation** — `FreeZones.kt`:

```kotlin
package dev.wasil.permit.parking

import kotlinx.serialization.Serializable

@Serializable
data class FreeZone(
    val lat: Double,
    val lng: Double,
    val radiusM: Double = 60.0,
    val label: String = "",
)

fun isInFreeZone(point: GeoPoint, zones: List<FreeZone>): Boolean =
    zones.any { distanceMeters(point, GeoPoint(it.lat, it.lng, 0f)) <= it.radiusM }

interface FreeZoneStore {
    fun all(): List<FreeZone>
    fun add(zone: FreeZone)
    fun removeAt(index: Int)
}
```

`PrefsFreeZoneStore.kt`:

```kotlin
package dev.wasil.permit.parking

import android.content.SharedPreferences
import dev.wasil.permit.data.api.PermitJson
import kotlinx.serialization.encodeToString

class PrefsFreeZoneStore(private val prefs: SharedPreferences) : FreeZoneStore {
    override fun all(): List<FreeZone> {
        val json = prefs.getString("free_zones", null) ?: return emptyList()
        return runCatching { PermitJson.decodeFromString<List<FreeZone>>(json) }.getOrDefault(emptyList())
    }

    override fun add(zone: FreeZone) = save(all() + zone)

    override fun removeAt(index: Int) = save(all().filterIndexed { i, _ -> i != index })

    private fun save(zones: List<FreeZone>) {
        prefs.edit().putString("free_zones", PermitJson.encodeToString(zones)).apply()
    }
}
```

`FakeFreeZoneStore.kt` (test sources):

```kotlin
package dev.wasil.permit.parking

class FakeFreeZoneStore(val zones: MutableList<FreeZone> = mutableListOf()) : FreeZoneStore {
    override fun all(): List<FreeZone> = zones.toList()
    override fun add(zone: FreeZone) { zones += zone }
    override fun removeAt(index: Int) { zones.removeAt(index) }
}
```

- [ ] **Step 4: Run green.** **Step 5: Commit** `feat: local free zones with radius hit-test`.

---

### Task 5: Park state store

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/parking/ParkStateStore.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/parking/PrefsParkStateStore.kt`
- Test fixture: `android/app/src/test/java/dev/wasil/permit/parking/FakeParkStateStore.kt`

**Interfaces:**
- Produces:

```kotlin
enum class MyCar { WASIL, WALID }
interface ParkStateStore {
    var carMac: String?
    var carName: String?
    var myCar: MyCar?
    var autoClaim: Boolean          // default true
    var parked: Boolean             // default false
    var lastParkLocation: GeoPoint? // where the last confirmed park happened
}
class PrefsParkStateStore(prefs: SharedPreferences) : ParkStateStore {
    companion object { fun from(context: Context): PrefsParkStateStore } // "park_state" prefs file
}
class FakeParkStateStore : ParkStateStore
```

No dedicated test (pure property storage, no logic); the fake is exercised heavily in Task 6.

- [ ] **Step 1: Implementation** — `ParkStateStore.kt`:

```kotlin
package dev.wasil.permit.parking

enum class MyCar { WASIL, WALID }

interface ParkStateStore {
    var carMac: String?
    var carName: String?
    var myCar: MyCar?
    var autoClaim: Boolean
    var parked: Boolean
    var lastParkLocation: GeoPoint?
}
```

`PrefsParkStateStore.kt`:

```kotlin
package dev.wasil.permit.parking

import android.content.Context
import android.content.SharedPreferences

class PrefsParkStateStore(private val prefs: SharedPreferences) : ParkStateStore {

    companion object {
        fun from(context: Context): PrefsParkStateStore =
            PrefsParkStateStore(context.getSharedPreferences("park_state", Context.MODE_PRIVATE))
    }

    override var carMac: String?
        get() = prefs.getString("car_mac", null)
        set(value) { prefs.edit().putString("car_mac", value).apply() }

    override var carName: String?
        get() = prefs.getString("car_name", null)
        set(value) { prefs.edit().putString("car_name", value).apply() }

    override var myCar: MyCar?
        get() = prefs.getString("my_car", null)?.let { runCatching { MyCar.valueOf(it) }.getOrNull() }
        set(value) { prefs.edit().putString("my_car", value?.name).apply() }

    override var autoClaim: Boolean
        get() = prefs.getBoolean("auto_claim", true)
        set(value) { prefs.edit().putBoolean("auto_claim", value).apply() }

    override var parked: Boolean
        get() = prefs.getBoolean("parked", false)
        set(value) { prefs.edit().putBoolean("parked", value).apply() }

    override var lastParkLocation: GeoPoint?
        get() {
            val lat = prefs.getString("last_lat", null)?.toDoubleOrNull() ?: return null
            val lng = prefs.getString("last_lng", null)?.toDoubleOrNull() ?: return null
            return GeoPoint(lat, lng, prefs.getFloat("last_acc", 0f))
        }
        set(value) {
            prefs.edit()
                .putString("last_lat", value?.lat?.toString())
                .putString("last_lng", value?.lng?.toString())
                .putFloat("last_acc", value?.accuracyM ?: 0f)
                .apply()
        }
}
```

`FakeParkStateStore.kt` (test sources):

```kotlin
package dev.wasil.permit.parking

class FakeParkStateStore : ParkStateStore {
    override var carMac: String? = "AA:BB:CC:DD:EE:FF"
    override var carName: String? = "Car stereo"
    override var myCar: MyCar? = MyCar.WASIL
    override var autoClaim: Boolean = true
    override var parked: Boolean = false
    override var lastParkLocation: GeoPoint? = null
}
```

- [ ] **Step 2: Build + full tests green.** **Step 3: Commit** `feat: park state store`.

---

### Task 6: ClaimPermit + ParkDetectionUseCase (the core)

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/parking/ClaimPermit.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/parking/ParkDetectionUseCase.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/parking/ParkDetectionUseCaseTest.kt`

**Interfaces:**
- Consumes: `PermitRepository`, `CredentialStore` (Phase 1), everything from Tasks 2–5.
- Produces:

```kotlin
interface DetectionSignals {
    suspend fun start()
    suspend fun stop()
    fun activitySamples(): List<ActivitySample>
    suspend fun currentLocation(): GeoPoint?
}

interface ParkNotifier {
    fun statusPermitOn(label: String, vrn: String)
    fun statusFreeZone()
    fun askManualDecision()
    fun switchFailed(reason: String?)
    fun mismatchWarning(serverVrn: String?)
}

sealed interface ParkOutcome {
    data object NotConfigured : ParkOutcome
    data object FalseAlarm : ParkOutcome
    data object FreeZoneParked : ParkOutcome
    data class Claimed(val vrn: String) : ParkOutcome
    data object ManualNeeded : ParkOutcome
    data object SwitchFailed : ParkOutcome
    data class MismatchDetected(val serverVrn: String?) : ParkOutcome
}

class ClaimPermit(repository, credentialStore, stateStore, notifier) {
    suspend fun claim(): ParkOutcome   // Claimed / MismatchDetected / SwitchFailed / NotConfigured
}

class ParkDetectionUseCase(
    signals, stateStore, freeZones, claimPermit, notifier,
    pollIntervalMs: Long = 5_000,
) { suspend fun run(): ParkOutcome }
```

- Behavior of `run()`: guard config → `signals.start()` → capture disconnect point → poll engine every `pollIntervalMs` accumulating elapsed until non-null decision or `TIMEOUT_MS` (then `Unclear`) → `signals.stop()` → map to outcome per spec (free zone check first, then auto-claim gate, then claim). Sets `stateStore.parked = true` and `lastParkLocation` on any confirmed park.

- [ ] **Step 1: Failing test**

```kotlin
package dev.wasil.permit.parking

import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.api.ActivateRequest
import dev.wasil.permit.data.api.ActivateResponse
import dev.wasil.permit.data.api.ClientProductResponse
import dev.wasil.permit.data.api.LoginRequest
import dev.wasil.permit.data.api.LoginResponse
import dev.wasil.permit.data.api.PermitApi
import dev.wasil.permit.data.api.VrnEntry
import dev.wasil.permit.data.store.FakeCredentialStore
import dev.wasil.permit.data.store.PermitConfig
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class ScriptedSignals(
    /** samples that "arrive" at the given poll round (0-based). */
    private val script: Map<Int, ActivitySample> = emptyMap(),
    private val locations: MutableList<GeoPoint?> = mutableListOf(null),
) : DetectionSignals {
    var round = -1
    private val seen = mutableListOf<ActivitySample>()
    var started = false; var stopped = false

    override suspend fun start() { started = true }
    override suspend fun stop() { stopped = true }
    override fun activitySamples(): List<ActivitySample> {
        round++
        script[round]?.let { seen += it }
        return seen.toList()
    }
    override suspend fun currentLocation(): GeoPoint? =
        if (locations.size > 1) locations.removeAt(0) else locations.first()
}

private class RecordingNotifier : ParkNotifier {
    val calls = mutableListOf<String>()
    override fun statusPermitOn(label: String, vrn: String) { calls += "status:$label:$vrn" }
    override fun statusFreeZone() { calls += "freezone" }
    override fun askManualDecision() { calls += "manual" }
    override fun switchFailed(reason: String?) { calls += "failed" }
    override fun mismatchWarning(serverVrn: String?) { calls += "mismatch:$serverVrn" }
}

private class SwitchApi(var active: String? = "XX123Y", var fail: Boolean = false) : PermitApi {
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

class ParkDetectionUseCaseTest {
    private val config = PermitConfig("u", "p", "RH950F", "XX123Y")
    private val stillAt6s = ActivitySample(ActivityType.STILL, 85, 6_000)
    private val driving = ActivitySample(ActivityType.IN_VEHICLE, 85, 6_000)

    private fun useCase(
        signals: DetectionSignals,
        state: FakeParkStateStore = FakeParkStateStore(),
        zones: FakeFreeZoneStore = FakeFreeZoneStore(),
        api: SwitchApi = SwitchApi(),
        notifier: RecordingNotifier = RecordingNotifier(),
    ): ParkDetectionUseCase {
        val claim = ClaimPermit(PermitRepository(api), FakeCredentialStore(config), state, notifier)
        return ParkDetectionUseCase(signals, state, zones, claim, notifier)
    }

    @Test
    fun `confirmed park auto-claims my plate and updates status`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s))
        val state = FakeParkStateStore()
        val notifier = RecordingNotifier()
        val outcome = useCase(signals, state, notifier = notifier).run()
        assertEquals(ParkOutcome.Claimed("RH950F"), outcome)
        assertTrue(state.parked)
        assertTrue(signals.stopped)
        assertTrue(notifier.calls.contains("status:Wasil:RH950F"))
    }

    @Test
    fun `bluetooth blip while driving does nothing`() = runTest {
        val signals = ScriptedSignals(script = mapOf(0 to driving))
        val state = FakeParkStateStore()
        val api = SwitchApi()
        val outcome = useCase(signals, state, api = api).run()
        assertEquals(ParkOutcome.FalseAlarm, outcome)
        assertFalse(state.parked)
        assertEquals("XX123Y", api.active) // permit untouched
    }

    @Test
    fun `timeout with no evidence asks for a manual decision`() = runTest {
        val signals = ScriptedSignals()
        val notifier = RecordingNotifier()
        val api = SwitchApi()
        val outcome = useCase(signals, api = api, notifier = notifier).run()
        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertTrue(notifier.calls.contains("manual"))
        assertEquals("XX123Y", api.active)
    }

    @Test
    fun `park inside stored free zone never touches the permit`() = runTest {
        val home = GeoPoint(52.3702, 4.8952, 10f)
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s), locations = mutableListOf(home))
        val zones = FakeFreeZoneStore(mutableListOf(FreeZone(52.3702, 4.8952, 60.0, "Home")))
        val api = SwitchApi()
        val outcome = useCase(signals, zones = zones, api = api).run()
        assertEquals(ParkOutcome.FreeZoneParked, outcome)
        assertEquals("XX123Y", api.active)
    }

    @Test
    fun `auto-claim off falls back to manual notification`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s))
        val state = FakeParkStateStore().apply { autoClaim = false }
        val api = SwitchApi()
        val outcome = useCase(signals, state, api = api).run()
        assertEquals(ParkOutcome.ManualNeeded, outcome)
        assertEquals("XX123Y", api.active)
    }

    @Test
    fun `network failure during switch is loud and reported`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s))
        val notifier = RecordingNotifier()
        val outcome = useCase(signals, api = SwitchApi(fail = true), notifier = notifier).run()
        assertEquals(ParkOutcome.SwitchFailed, outcome)
        assertTrue(notifier.calls.contains("failed"))
    }

    @Test
    fun `server mismatch after activate warns loudly`() = runTest {
        // activate "succeeds" but the server still reports the other plate
        val api = object : PermitApi {
            override suspend fun login(body: LoginRequest) = LoginResponse("tok")
            override suspend fun getClientProduct(productId: Long) =
                ClientProductResponse(listOf(VrnEntry("RH950F", false), VrnEntry("XX123Y", true)))
            override suspend fun activate(body: ActivateRequest) = ActivateResponse(1)
        }
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s))
        val notifier = RecordingNotifier()
        val state = FakeParkStateStore()
        val claim = ClaimPermit(PermitRepository(api), FakeCredentialStore(config), state, notifier)
        val outcome = ParkDetectionUseCase(signals, state, FakeFreeZoneStore(), claim, notifier).run()
        assertEquals(ParkOutcome.MismatchDetected("XX123Y"), outcome)
        assertTrue(notifier.calls.contains("mismatch:XX123Y"))
    }

    @Test
    fun `walid phone claims walid plate`() = runTest {
        val signals = ScriptedSignals(script = mapOf(1 to stillAt6s))
        val state = FakeParkStateStore().apply { myCar = MyCar.WALID }
        val api = SwitchApi(active = "RH950F")
        val outcome = useCase(signals, state, api = api).run()
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

- [ ] **Step 2: Run red.**
- [ ] **Step 3: Implementation** — `ClaimPermit.kt`:

```kotlin
package dev.wasil.permit.parking

import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.store.CredentialStore

/** Shared by auto-claim and the notification "Claim"/"Retry" actions. */
class ClaimPermit(
    private val repository: PermitRepository,
    private val credentialStore: CredentialStore,
    private val stateStore: ParkStateStore,
    private val notifier: ParkNotifier,
) {
    suspend fun claim(): ParkOutcome {
        val config = credentialStore.load() ?: return ParkOutcome.NotConfigured
        val mine = stateStore.myCar ?: return ParkOutcome.NotConfigured
        val (label, plate) = when (mine) {
            MyCar.WASIL -> "Wasil" to config.wasilPlate
            MyCar.WALID -> "Walid" to config.walidPlate
        }
        return try {
            when (val result = repository.switchTo(plate)) {
                is PermitRepository.SwitchResult.Confirmed -> {
                    notifier.statusPermitOn(label, result.activeVrn)
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
}
```

`ParkDetectionUseCase.kt`:

```kotlin
package dev.wasil.permit.parking

import kotlinx.coroutines.delay

interface DetectionSignals {
    suspend fun start()
    suspend fun stop()
    fun activitySamples(): List<ActivitySample>
    suspend fun currentLocation(): GeoPoint?
}

interface ParkNotifier {
    fun statusPermitOn(label: String, vrn: String)
    fun statusFreeZone()
    fun askManualDecision()
    fun switchFailed(reason: String?)
    fun mismatchWarning(serverVrn: String?)
}

sealed interface ParkOutcome {
    data object NotConfigured : ParkOutcome
    data object FalseAlarm : ParkOutcome
    data object FreeZoneParked : ParkOutcome
    data class Claimed(val vrn: String) : ParkOutcome
    data object ManualNeeded : ParkOutcome
    data object SwitchFailed : ParkOutcome
    data class MismatchDetected(val serverVrn: String?) : ParkOutcome
}

class ParkDetectionUseCase(
    private val signals: DetectionSignals,
    private val stateStore: ParkStateStore,
    private val freeZones: FreeZoneStore,
    private val claimPermit: ClaimPermit,
    private val notifier: ParkNotifier,
    private val pollIntervalMs: Long = 5_000,
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
            Decision.ParkedInCar, Decision.ParkedWalkedAway -> {
                stateStore.parked = true
                stateStore.lastParkLocation = latestPoint
                val point = latestPoint
                when {
                    point != null && isInFreeZone(point, freeZones.all()) -> {
                        notifier.statusFreeZone()
                        ParkOutcome.FreeZoneParked
                    }
                    !stateStore.autoClaim -> {
                        notifier.askManualDecision()
                        ParkOutcome.ManualNeeded
                    }
                    else -> claimPermit.claim()
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run green** (delay is auto-skipped under `runTest`, including the 90 s timeout path).
- [ ] **Step 5: Commit** `feat: park detection use case with auto-claim`.

---

### Task 7: Android shell — receivers, signals, workers, notifications

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/parking/android/ParkNotifications.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/parking/android/PlayServicesSignals.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/parking/android/ActivityUpdatesReceiver.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/parking/android/CarBluetoothReceiver.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/parking/android/ParkActionReceiver.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/parking/android/ParkWorkers.kt`
- Modify: `AndroidManifest.xml` (receivers), `PermitApp.kt` (expose stores, init channels)

No unit tests (thin Android shells); verified by compilation + on-device smoke test. Key contents:

- `ParkNotifications`: channels `permit_status` (LOW, ongoing) and `park_events` (HIGH); implements `ParkNotifier`; event notifications carry actions **Claim** (`ACTION_CLAIM`), **Ignore** (`ACTION_IGNORE`), **Free here** (`ACTION_FREE_HERE`) as `PendingIntent.getBroadcast` to `ParkActionReceiver`; failure notification uses **Retry** (`ACTION_CLAIM`) + **Ignore**.
- `PlayServicesSignals implements DetectionSignals`: `start()` records a start timestamp in `park_state` prefs and calls `ActivityRecognitionClient.requestActivityUpdates(5000, pi)` (PendingIntent to `ActivityUpdatesReceiver`); `stop()` removes updates; `activitySamples()` reads the JSON buffer the receiver appends to prefs, mapping detected types to `ActivityType` (`IN_VEHICLE`→IN_VEHICLE, `STILL`→STILL, `WALKING`/`RUNNING`/`ON_FOOT`→ON_FOOT, else OTHER) and timestamps to elapsed; `currentLocation()` = `FusedLocationProviderClient.getCurrentLocation(PRIORITY_HIGH_ACCURACY)` awaited, null on missing permission/exception.
- `ActivityUpdatesReceiver`: extracts `ActivityRecognitionResult`, appends `(type, confidence, timestamp)` to the prefs buffer.
- `CarBluetoothReceiver`: manifest-registered for `ACTION_ACL_CONNECTED`/`ACTION_ACL_DISCONNECTED`; matches `EXTRA_DEVICE` address against `carMac` (SecurityException-safe); disconnect → enqueue unique expedited `ParkDetectionWorker` (`ExistingWorkPolicy.REPLACE`); connect → cancel that work, `parked = false`, cancel event notifications.
- `ParkWorkers`: `ParkDetectionWorker` (CoroutineWorker) builds `ParkDetectionUseCase` from `PermitApp` + `PlayServicesSignals` + prefs stores, runs it, returns `Result.retry()` on `SwitchFailed` (backoff), else `success()`; `ClaimPermitWorker` runs `ClaimPermit.claim()` for notification Claim/Retry actions with the same retry rule.
- `ParkActionReceiver`: `ACTION_CLAIM` → enqueue `ClaimPermitWorker`; `ACTION_IGNORE` → cancel event notification; `ACTION_FREE_HERE` → read `lastParkLocation`, `FreeZoneStore.add(FreeZone(lat, lng, 60.0, "Marked <date>"))`, cancel notification.
- Manifest: `CarBluetoothReceiver` exported=true with ACL intent filter; other receivers exported=false.
- `PermitApp`: expose `parkStateStore`, `freeZoneStore`, `notifications`; create channels in `onCreate`.

- [ ] **Step 1: Write all files.** **Step 2: `assembleDebug` + full test suite green.** **Step 3: Commit** `feat: bluetooth-triggered detection worker and notifications`.

---

### Task 8: Settings UI + wiring

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/ui/SettingsScreen.kt`
- Modify: `MainScreen.kt` (Settings entry point), `MainActivity.kt` (show/hide settings, runtime permission launcher)

Contents: car device picker (bonded devices via `BluetoothManager.adapter.bondedDevices`, guarded by `BLUETOOTH_CONNECT` check), Wasil/Walid radio, auto-claim switch, free-zone list with delete, permission request buttons (`POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT`, `ACTIVITY_RECOGNITION`, `ACCESS_FINE_LOCATION`) plus an "Open app settings" button for background location. Settings toggled via a `showSettings` boolean + `BackHandler` in `MainActivity` (no nav library).

- [ ] **Step 1: Write UI + wiring.** **Step 2: `assembleDebug :app:testDebugUnitTest` green.** **Step 3: Commit** `feat: settings screen for car pairing and detection options`.

---

### Task 9: Docs + verification + merge

- [ ] Update `README.md` (Phase 2 section: what's automated, permissions needed, smoke-test checklist).
- [ ] `clean :app:assembleDebug :app:testDebugUnitTest` — everything green.
- [ ] Commit `docs: Phase 2 README`, merge `phase2-parked-detection` → `master`, push to GitHub.

**On-device smoke test (requires Wasil):** grant all permissions incl. background location → pick car in Settings → drive & park staying in car (expect claim notification ≤ ~20 s) → park & walk away (expect claim) → mark home with "Free here", park there again (expect no switch) → verify every switch on the permit website.
