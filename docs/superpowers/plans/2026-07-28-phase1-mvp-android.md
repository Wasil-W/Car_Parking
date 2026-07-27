# Phase 1 MVP Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A one-screen native Kotlin Android app that shows which plate currently holds the Amsterdam parking permit and switches it between Wasil's and Walid's cars, with read-after-write verification and transparent re-login on token expiry.

**Architecture:** MVVM. A Retrofit/OkHttp data layer talks to the permit API (`api.parkeervergunningen.egisparkingservices.nl`); an OkHttp `Authenticator` re-logs-in on 401 and retries once; credentials and the two plate configs live in EncryptedSharedPreferences behind a `CredentialStore` interface (so tests use an in-memory fake). A single `MainViewModel` exposes a `StateFlow<UiState>` rendered by one Jetpack Compose screen (plus a first-run setup screen for entering credentials/plates — nothing secret ever lives in the repo).

**Tech Stack:** Kotlin 2.1.0, AGP 8.7.3, Gradle 8.11.1, Jetpack Compose (BOM 2024.12.01), Retrofit 2.11 + kotlinx.serialization converter, OkHttp 4.12, androidx.security-crypto (EncryptedSharedPreferences), JUnit4 + MockWebServer + kotlinx-coroutines-test for JVM unit tests.

## Global Constraints

- **API base URL:** `https://api.parkeervergunningen.egisparkingservices.nl/api/` (note trailing slash; Retrofit paths are relative, no leading slash).
- **PRODUCT_ID:** `5807976` (constant).
- **Endpoints (from permit.py, the source of truth):**
  - `POST ssp/login_check` body `{"username","password"}` → `{"token": "<JWT>"}`
  - `GET v1/client_product/{PRODUCT_ID}` → object containing `vrns: [{vrn, has_parking_session, ...}]`
  - `POST v1/ssp/parking_session/activate` body `{"client_product_id", "vrn"}` → `{"parking_session_id"}`
- **Critical gotcha:** every request MUST carry browser-like headers or the API 403s:
  `origin: https://parkeervergunningen.amsterdam.nl`, `referer: https://parkeervergunningen.amsterdam.nl/`, a Chrome-like `user-agent`, and `accept: application/json, text/plain, */*`.
- **Plate format:** no dashes, uppercase (`RH950F` not `RH-950-F`). Normalize all user input.
- **Read-after-write always:** every activate is followed by a state re-fetch; UI only shows success if the re-read confirms the target plate is active.
- **Token dies hourly, no refresh token:** re-login on 401 and retry the original request once — never surface "please log in again".
- **Only two plates ever offered** (Wasil's and Walid's, entered at setup). The permit's third plate must never be selectable.
- **No credentials or plates in the repo** — all entered in-app, stored encrypted.
- **JSON parsing must ignore unknown keys** (the state response is a large object; we only model what we use).
- Package: `dev.wasil.permit`. minSdk 26, compileSdk/targetSdk 35.
- JVM for Gradle: Android Studio JBR (`C:\Program Files\Android\Android Studio\jbr`, JDK 21) — system Java 25 is too new for AGP 8.7.
- All build commands run from `C:\Users\wasil\Dev\Car_Parking\android` (the Gradle project root is a subdirectory of the repo).

---

### Task 1: Toolchain bootstrap + buildable project skeleton

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/libs.versions.toml`, `android/local.properties`, `android/.gitignore`
- Create: `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/dev/wasil/permit/MainActivity.kt`, `android/app/src/main/res/values/strings.xml`, `android/app/src/main/res/values/themes.xml`
- Create (generated): `android/gradlew.bat`, `android/gradle/wrapper/*`

**Interfaces:**
- Produces: a Gradle project where `.\gradlew.bat :app:assembleDebug` and `.\gradlew.bat :app:testDebugUnitTest` succeed. Version catalog aliases used by all later tasks.

- [ ] **Step 1: Install the Android SDK (one-time machine setup)**

```powershell
# ~150 MB download from Google's official repository
$sdk = "C:\Android\sdk"
New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
$zip = "$env:TEMP\cmdline-tools.zip"
Invoke-WebRequest "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $zip
Expand-Archive $zip "$sdk\cmdline-tools\tmp" -Force
Move-Item "$sdk\cmdline-tools\tmp\cmdline-tools" "$sdk\cmdline-tools\latest"
Remove-Item "$sdk\cmdline-tools\tmp" -Recurse -Force
```

Then (licenses must be accepted; pipe `y` responses):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& "C:\Android\sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
# license acceptance: run  sdkmanager.bat --licenses  and answer y to each prompt
```

Expected: `C:\Android\sdk\platforms\android-35` exists.

- [ ] **Step 2: Bootstrap the Gradle wrapper**

No Gradle is installed, so download the distribution once and use it only to generate the wrapper:

```powershell
$gz = "$env:TEMP\gradle-8.11.1-bin.zip"
Invoke-WebRequest "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip" -OutFile $gz  # ~135 MB
Expand-Archive $gz "$env:TEMP\gradle-dist" -Force
```

After writing the project files in Step 3, run from `android/`:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& "$env:TEMP\gradle-dist\gradle-8.11.1\bin\gradle.bat" wrapper --gradle-version 8.11.1
```

Expected: `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` created.

- [ ] **Step 3: Write the project files**

`android/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "PermitSwitcher"
include(":app")
```

`android/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

`android/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2g
org.gradle.java.home=C\:\\Program Files\\Android\\Android Studio\\jbr
android.useAndroidX=true
kotlin.code.style=official
```

`android/local.properties` (gitignored):

```properties
sdk.dir=C\:\\Android\\sdk
```

`android/.gitignore`:

```
.gradle/
build/
local.properties
*.iml
.idea/
```

`android/gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
coroutines = "1.9.0"
serialization = "1.7.3"
composeBom = "2024.12.01"
activityCompose = "1.9.3"
lifecycle = "2.8.7"
retrofit = "2.11.0"
okhttp = "4.12.0"
securityCrypto = "1.1.0-alpha06"
junit = "4.13.2"

[libraries]
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-kotlinx-serialization = { group = "com.squareup.retrofit2", name = "converter-kotlinx-serialization", version.ref = "retrofit" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "securityCrypto" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

`android/app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.wasil.permit"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wasil.permit"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
```

`android/app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:label="@string/app_name"
        android:icon="@android:drawable/sym_def_app_icon"
        android:theme="@style/Theme.PermitSwitcher">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

(The manifest deliberately uses the built-in `@android:drawable/sym_def_app_icon` — no mipmap resources needed for the MVP.)

`android/app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">Permit Switcher</string>
</resources>
```

`android/app/src/main/res/values/themes.xml`:

```xml
<resources>
    <style name="Theme.PermitSwitcher" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

`android/app/src/main/java/dev/wasil/permit/MainActivity.kt` (placeholder until Task 8):

```kotlin
package dev.wasil.permit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("Permit Switcher") }
    }
}
```

- [ ] **Step 4: Verify the build**

Run from `android/`: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 5: Commit**

```bash
git add android/ docs/
git commit -m "feat: Android project skeleton (Kotlin, Compose, Retrofit deps)"
```

---

### Task 2: API DTOs with strict-enough JSON parsing

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/data/api/Dtos.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/data/api/DtosTest.kt`

**Interfaces:**
- Produces: `LoginRequest(username, password)`, `LoginResponse(token)`, `VrnEntry(vrn, hasParkingSession)`, `ClientProductResponse(vrns: List<VrnEntry>)`, `ActivateRequest(clientProductId, vrn)`, `ActivateResponse(parkingSessionId)`, and `PermitJson` — the shared `Json` instance with `ignoreUnknownKeys = true`.

- [ ] **Step 1: Write the failing test**

`DtosTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.data.api.DtosTest"`
Expected: compilation FAILURE (`Dtos.kt` types don't exist yet).

- [ ] **Step 3: Write minimal implementation**

`Dtos.kt`:

```kotlin
package dev.wasil.permit.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared JSON config: the API returns far more fields than we model. */
val PermitJson: Json = Json { ignoreUnknownKeys = true }

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String)

@Serializable
data class VrnEntry(
    val vrn: String,
    @SerialName("has_parking_session") val hasParkingSession: Boolean,
)

@Serializable
data class ClientProductResponse(val vrns: List<VrnEntry>)

@Serializable
data class ActivateRequest(
    @SerialName("client_product_id") val clientProductId: Long,
    val vrn: String,
)

@Serializable
data class ActivateResponse(
    @SerialName("parking_session_id") val parkingSessionId: Long,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.data.api.DtosTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src
git commit -m "feat: permit API DTOs with lenient JSON parsing"
```

---

### Task 3: Retrofit service + browser-header interceptor

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/data/api/PermitApi.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/data/api/BrowserHeadersInterceptor.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/data/api/PermitApiTest.kt`

**Interfaces:**
- Consumes: DTOs + `PermitJson` from Task 2.
- Produces:
  - `interface PermitApi` with `suspend fun login(body: LoginRequest): LoginResponse`, `suspend fun getClientProduct(productId: Long): ClientProductResponse`, `suspend fun activate(body: ActivateRequest): ActivateResponse`
  - `object ApiConstants { const val BASE_URL; const val PRODUCT_ID = 5807976L; const val LOGIN_PATH = "/api/ssp/login_check" }`
  - `class BrowserHeadersInterceptor : Interceptor`
  - `fun buildPermitApi(baseUrl: HttpUrl, client: OkHttpClient): PermitApi`

- [ ] **Step 1: Write the failing test**

`PermitApiTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.data.api.PermitApiTest"`
Expected: compilation FAILURE (`PermitApi`, `BrowserHeadersInterceptor`, `buildPermitApi` missing).

- [ ] **Step 3: Write minimal implementation**

`BrowserHeadersInterceptor.kt`:

```kotlin
package dev.wasil.permit.data.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * The permit API rejects requests (403) that don't look like they come from
 * the official web frontend, even with valid credentials. These three headers
 * plus accept are mandatory on every call.
 */
class BrowserHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("accept", "application/json, text/plain, */*")
            .header("origin", "https://parkeervergunningen.amsterdam.nl")
            .header("referer", "https://parkeervergunningen.amsterdam.nl/")
            .header(
                "user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
            )
            .build()
        return chain.proceed(request)
    }
}
```

`PermitApi.kt`:

```kotlin
package dev.wasil.permit.data.api

import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

object ApiConstants {
    const val BASE_URL = "https://api.parkeervergunningen.egisparkingservices.nl/api/"
    const val PRODUCT_ID = 5807976L
    const val LOGIN_PATH = "/api/ssp/login_check"
}

interface PermitApi {
    @POST("ssp/login_check")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @GET("v1/client_product/{productId}")
    suspend fun getClientProduct(@Path("productId") productId: Long): ClientProductResponse

    @POST("v1/ssp/parking_session/activate")
    suspend fun activate(@Body body: ActivateRequest): ActivateResponse
}

fun buildPermitApi(baseUrl: HttpUrl, client: OkHttpClient): PermitApi =
    Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(PermitJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(PermitApi::class.java)
```

Note: `converter-kotlinx-serialization` is Retrofit's official first-party converter as of 2.11 (package `retrofit2.converter.kotlinx.serialization`), replacing the old `com.jakewharton` artifact.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.data.api.PermitApiTest"`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src
git commit -m "feat: Retrofit PermitApi with mandatory browser headers"
```

---

### Task 4: Credential/config storage (interface + encrypted impl + fake)

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/data/store/CredentialStore.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/data/store/EncryptedCredentialStore.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/data/store/PlateNormalizer.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/data/store/PlateNormalizerTest.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/data/store/FakeCredentialStore.kt` (test fixture used by Tasks 5–7)

**Interfaces:**
- Produces:

```kotlin
data class PermitConfig(
    val username: String,
    val password: String,
    val wasilPlate: String,   // normalized, e.g. "RH950F"
    val walidPlate: String,
)

interface CredentialStore {
    fun load(): PermitConfig?      // null until setup completed
    fun save(config: PermitConfig)
    fun clear()
}

fun normalizePlate(raw: String): String  // "rh-950-f" -> "RH950F"
```

- `EncryptedCredentialStore(context: Context) : CredentialStore` — EncryptedSharedPreferences-backed, keys `username`/`password`/`wasil_plate`/`walid_plate`. Thin wrapper, no logic, not unit-tested (needs a device; verified manually in Task 8).
- `FakeCredentialStore(var config: PermitConfig?) : CredentialStore` — in-memory, lives in test sources.

- [ ] **Step 1: Write the failing test**

`PlateNormalizerTest.kt`:

```kotlin
package dev.wasil.permit.data.store

import org.junit.Assert.assertEquals
import org.junit.Test

class PlateNormalizerTest {
    @Test
    fun `strips dashes and spaces and uppercases`() {
        assertEquals("RH950F", normalizePlate("rh-950-f"))
        assertEquals("RH950F", normalizePlate(" RH 950 F "))
        assertEquals("XX123Y", normalizePlate("XX123Y"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.data.store.PlateNormalizerTest"`
Expected: compilation FAILURE.

- [ ] **Step 3: Write minimal implementation**

`PlateNormalizer.kt`:

```kotlin
package dev.wasil.permit.data.store

/** API rejects formatted plates: "RH-950-F" must be sent as "RH950F". */
fun normalizePlate(raw: String): String =
    raw.filter { it.isLetterOrDigit() }.uppercase()
```

`CredentialStore.kt`:

```kotlin
package dev.wasil.permit.data.store

data class PermitConfig(
    val username: String,
    val password: String,
    val wasilPlate: String,
    val walidPlate: String,
)

interface CredentialStore {
    fun load(): PermitConfig?
    fun save(config: PermitConfig)
    fun clear()
}
```

`EncryptedCredentialStore.kt`:

```kotlin
package dev.wasil.permit.data.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedCredentialStore(context: Context) : CredentialStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "permit_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun load(): PermitConfig? {
        val username = prefs.getString("username", null) ?: return null
        val password = prefs.getString("password", null) ?: return null
        val wasil = prefs.getString("wasil_plate", null) ?: return null
        val walid = prefs.getString("walid_plate", null) ?: return null
        return PermitConfig(username, password, wasil, walid)
    }

    override fun save(config: PermitConfig) {
        prefs.edit()
            .putString("username", config.username)
            .putString("password", config.password)
            .putString("wasil_plate", normalizePlate(config.wasilPlate))
            .putString("walid_plate", normalizePlate(config.walidPlate))
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}
```

`FakeCredentialStore.kt` (in `app/src/test/...`):

```kotlin
package dev.wasil.permit.data.store

class FakeCredentialStore(var config: PermitConfig? = null) : CredentialStore {
    override fun load(): PermitConfig? = config
    override fun save(config: PermitConfig) { this.config = config }
    override fun clear() { config = null }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.data.store.PlateNormalizerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src
git commit -m "feat: credential store (encrypted impl + in-memory fake) and plate normalizer"
```

---

### Task 5: Token handling — TokenStore, auth interceptor, 401 re-login authenticator

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/data/auth/TokenStore.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/data/auth/AuthInterceptor.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/data/auth/PermitAuthenticator.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/data/auth/PermitAuthenticatorTest.kt`

**Interfaces:**
- Consumes: `BrowserHeadersInterceptor`, `PermitJson`, `LoginRequest`/`LoginResponse` (Tasks 2–3), `CredentialStore`/`FakeCredentialStore` (Task 4).
- Produces:
  - `class TokenStore { @Volatile var token: String? }`
  - `class AuthInterceptor(tokenStore: TokenStore) : Interceptor` — adds `Authorization: Bearer <token>` to every request except the login path, when a token exists.
  - `class BlockingLoginClient(baseUrl: HttpUrl, client: OkHttpClient)` with `fun login(username: String, password: String): String?` — synchronous login used only by the authenticator (OkHttp authenticators are synchronous by contract).
  - `class PermitAuthenticator(credentialStore, tokenStore, loginClient) : okhttp3.Authenticator` — on 401: re-login with stored credentials, store new token, retry original request once. Gives up (returns null) if: request was the login call itself, a retry already happened, or no credentials stored.
  - `fun buildAuthenticatedClient(baseUrl: HttpUrl, tokenStore: TokenStore, credentialStore: CredentialStore): OkHttpClient` — wires BrowserHeadersInterceptor + AuthInterceptor + PermitAuthenticator.

- [ ] **Step 1: Write the failing test**

`PermitAuthenticatorTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.data.auth.PermitAuthenticatorTest"`
Expected: compilation FAILURE.

- [ ] **Step 3: Write minimal implementation**

`TokenStore.kt`:

```kotlin
package dev.wasil.permit.data.auth

/** In-memory only: the JWT lives 1 hour, persisting it buys nothing. */
class TokenStore {
    @Volatile
    var token: String? = null
}
```

`AuthInterceptor.kt`:

```kotlin
package dev.wasil.permit.data.auth

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokenStore.token
        if (token == null || request.url.encodedPath.endsWith("/ssp/login_check")) {
            return chain.proceed(request)
        }
        return chain.proceed(
            request.newBuilder().header("Authorization", "Bearer $token").build()
        )
    }
}
```

`PermitAuthenticator.kt`:

```kotlin
package dev.wasil.permit.data.auth

import dev.wasil.permit.data.api.BrowserHeadersInterceptor
import dev.wasil.permit.data.api.LoginRequest
import dev.wasil.permit.data.api.LoginResponse
import dev.wasil.permit.data.api.PermitJson
import dev.wasil.permit.data.store.CredentialStore
import kotlinx.serialization.encodeToString
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route

/** Synchronous login used only from the Authenticator (which runs off the main thread). */
class BlockingLoginClient(private val baseUrl: HttpUrl, private val client: OkHttpClient) {
    fun login(username: String, password: String): String? {
        val body = PermitJson.encodeToString(LoginRequest(username, password))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("ssp/login_check").build())
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val text = resp.body?.string() ?: return null
            return runCatching {
                PermitJson.decodeFromString<LoginResponse>(text).token
            }.getOrNull()
        }
    }
}

/**
 * The JWT expires after exactly 1 hour with no refresh token. On any 401 we
 * re-login with the stored credentials and replay the original request once.
 * The user must never see "please log in again".
 */
class PermitAuthenticator(
    private val credentialStore: CredentialStore,
    private val tokenStore: TokenStore,
    private val loginClient: BlockingLoginClient,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // A 401 from the login endpoint means bad credentials - retrying loops forever.
        if (response.request.url.encodedPath.endsWith("/ssp/login_check")) return null
        // Only one retry per request.
        if (response.priorResponse != null) return null

        val config = credentialStore.load() ?: return null
        val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

        val newToken = synchronized(this) {
            val current = tokenStore.token
            // Another request may have refreshed the token while we waited.
            if (current != null && current != failedToken) {
                current
            } else {
                loginClient.login(config.username, config.password)
                    ?.also { tokenStore.token = it }
            }
        } ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }
}

fun buildAuthenticatedClient(
    baseUrl: HttpUrl,
    tokenStore: TokenStore,
    credentialStore: CredentialStore,
): OkHttpClient {
    val bareClient = OkHttpClient.Builder()
        .addInterceptor(BrowserHeadersInterceptor())
        .build()
    return OkHttpClient.Builder()
        .addInterceptor(BrowserHeadersInterceptor())
        .addInterceptor(AuthInterceptor(tokenStore))
        .authenticator(
            PermitAuthenticator(credentialStore, tokenStore, BlockingLoginClient(baseUrl, bareClient))
        )
        .build()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.data.auth.PermitAuthenticatorTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src
git commit -m "feat: transparent re-login on 401 via OkHttp Authenticator"
```

---

### Task 6: PermitRepository with read-after-write verification

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/data/PermitRepository.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/data/PermitRepositoryTest.kt`

**Interfaces:**
- Consumes: `PermitApi`, `ApiConstants.PRODUCT_ID`, DTOs (Tasks 2–3).
- Produces:

```kotlin
class PermitRepository(private val api: PermitApi) {
    /** vrn currently holding the permit, or null if none/unknown. */
    suspend fun activePlate(): String?

    sealed interface SwitchResult {
        data class Confirmed(val activeVrn: String) : SwitchResult
        /** activate returned 200 but the re-read shows a different plate - do NOT trust the write. */
        data class Mismatch(val serverActiveVrn: String?) : SwitchResult
    }

    /** Activates [vrn], then re-fetches state to confirm the switch landed. */
    suspend fun switchTo(vrn: String): SwitchResult
}
```

- Errors (IOException, HTTP failures) propagate as exceptions; the ViewModel maps them to UI state.

- [ ] **Step 1: Write the failing test**

`PermitRepositoryTest.kt`:

```kotlin
package dev.wasil.permit.data

import dev.wasil.permit.data.api.ActivateRequest
import dev.wasil.permit.data.api.ActivateResponse
import dev.wasil.permit.data.api.ClientProductResponse
import dev.wasil.permit.data.api.LoginRequest
import dev.wasil.permit.data.api.LoginResponse
import dev.wasil.permit.data.api.PermitApi
import dev.wasil.permit.data.api.VrnEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePermitApi(
    /** Each getClientProduct call pops the next state - lets tests script activate side effects. */
    val states: ArrayDeque<List<VrnEntry>>,
) : PermitApi {
    val activated = mutableListOf<ActivateRequest>()

    override suspend fun login(body: LoginRequest) = LoginResponse("tok")

    override suspend fun getClientProduct(productId: Long) =
        ClientProductResponse(states.removeFirst())

    override suspend fun activate(body: ActivateRequest): ActivateResponse {
        activated += body
        return ActivateResponse(1L)
    }
}

class PermitRepositoryTest {

    @Test
    fun `activePlate returns the vrn with a parking session`() = runTest {
        val api = FakePermitApi(
            ArrayDeque(listOf(listOf(VrnEntry("RH950F", false), VrnEntry("XX123Y", true))))
        )
        assertEquals("XX123Y", PermitRepository(api).activePlate())
    }

    @Test
    fun `activePlate returns null when nothing is active`() = runTest {
        val api = FakePermitApi(ArrayDeque(listOf(listOf(VrnEntry("RH950F", false)))))
        assertEquals(null, PermitRepository(api).activePlate())
    }

    @Test
    fun `switchTo activates then re-reads and confirms`() = runTest {
        val api = FakePermitApi(
            ArrayDeque(listOf(listOf(VrnEntry("RH950F", true), VrnEntry("XX123Y", false))))
        )
        val result = PermitRepository(api).switchTo("RH950F")
        assertEquals(PermitRepository.SwitchResult.Confirmed("RH950F"), result)
        assertEquals(listOf("RH950F"), api.activated.map { it.vrn })
        assertEquals(5807976L, api.activated.single().clientProductId)
    }

    @Test
    fun `switchTo reports mismatch when server disagrees after activate`() = runTest {
        // Server said 200 to activate, but re-read shows the OTHER plate still active.
        val api = FakePermitApi(
            ArrayDeque(listOf(listOf(VrnEntry("RH950F", false), VrnEntry("XX123Y", true))))
        )
        val result = PermitRepository(api).switchTo("RH950F")
        assertTrue(result is PermitRepository.SwitchResult.Mismatch)
        assertEquals("XX123Y", (result as PermitRepository.SwitchResult.Mismatch).serverActiveVrn)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.data.PermitRepositoryTest"`
Expected: compilation FAILURE.

- [ ] **Step 3: Write minimal implementation**

`PermitRepository.kt`:

```kotlin
package dev.wasil.permit.data

import dev.wasil.permit.data.api.ActivateRequest
import dev.wasil.permit.data.api.ApiConstants
import dev.wasil.permit.data.api.PermitApi

class PermitRepository(private val api: PermitApi) {

    suspend fun activePlate(): String? =
        api.getClientProduct(ApiConstants.PRODUCT_ID)
            .vrns.firstOrNull { it.hasParkingSession }?.vrn

    sealed interface SwitchResult {
        data class Confirmed(val activeVrn: String) : SwitchResult
        data class Mismatch(val serverActiveVrn: String?) : SwitchResult
    }

    /**
     * Never trust the write: a 200 from activate is not proof the permit moved.
     * Always re-read state and compare (a wrong permit is a parking fine).
     */
    suspend fun switchTo(vrn: String): SwitchResult {
        api.activate(ActivateRequest(ApiConstants.PRODUCT_ID, vrn))
        val nowActive = activePlate()
        return if (nowActive == vrn) SwitchResult.Confirmed(vrn)
        else SwitchResult.Mismatch(nowActive)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.data.PermitRepositoryTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src
git commit -m "feat: PermitRepository with read-after-write verification"
```

---

### Task 7: MainViewModel

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/ui/MainViewModel.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/ui/MainViewModelTest.kt`

**Interfaces:**
- Consumes: `PermitRepository` + `SwitchResult` (Task 6), `CredentialStore`/`PermitConfig`/`normalizePlate` (Task 4).
- Produces:

```kotlin
data class PlateOption(val label: String, val vrn: String)  // label "Wasil" / "Walid"

data class UiState(
    val needsSetup: Boolean = false,
    val loading: Boolean = false,        // initial/refresh state fetch
    val switching: String? = null,       // vrn currently being switched to, null if idle
    val activeVrn: String? = null,
    val options: List<PlateOption> = emptyList(),
    val message: String? = null,         // transient success/error text for a snackbar
)

class MainViewModel(
    private val repository: PermitRepository,
    private val credentialStore: CredentialStore,
) : ViewModel() {
    val state: StateFlow<UiState>
    fun refresh()
    fun switchTo(option: PlateOption)
    fun saveSetup(username: String, password: String, wasilPlate: String, walidPlate: String)
    fun consumeMessage()
}
```

- Behavior: on init, `needsSetup = (credentialStore.load() == null)`; if configured, `refresh()` runs automatically. `switchTo` sets `switching`, calls repository, and on `Mismatch` shows a warning message including the server's plate ("check the website"). All repository exceptions become `message` values, never crashes. `saveSetup` normalizes plates, saves, flips `needsSetup` off, and refreshes.

- [ ] **Step 1: Write the failing test**

`MainViewModelTest.kt`:

```kotlin
package dev.wasil.permit.ui

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class ScriptedApi : PermitApi {
    var active: String? = "RH950F"
    var failNextGet = false
    override suspend fun login(body: LoginRequest) = LoginResponse("tok")
    override suspend fun getClientProduct(productId: Long): ClientProductResponse {
        if (failNextGet) { failNextGet = false; throw IOException("offline") }
        return ClientProductResponse(
            listOf(
                VrnEntry("RH950F", active == "RH950F"),
                VrnEntry("XX123Y", active == "XX123Y"),
            )
        )
    }
    override suspend fun activate(body: ActivateRequest): ActivateResponse {
        active = body.vrn
        return ActivateResponse(1L)
    }
}

class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: ScriptedApi
    private val config = PermitConfig("u", "p", "RH950F", "XX123Y")

    @Before fun setUp() { Dispatchers.setMain(dispatcher); api = ScriptedApi() }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(store: FakeCredentialStore = FakeCredentialStore(config)) =
        MainViewModel(PermitRepository(api), store)

    @Test
    fun `unconfigured store shows setup screen`() = runTest(dispatcher) {
        val vm = vm(FakeCredentialStore(null))
        assertTrue(vm.state.value.needsSetup)
    }

    @Test
    fun `configured store loads active plate and both options on init`() = runTest(dispatcher) {
        val vm = vm()
        dispatcher.scheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals("RH950F", s.activeVrn)
        assertEquals(listOf("Wasil", "Walid"), s.options.map { it.label })
        assertEquals(listOf("RH950F", "XX123Y"), s.options.map { it.vrn })
    }

    @Test
    fun `switchTo updates active plate after confirmed switch`() = runTest(dispatcher) {
        val vm = vm()
        dispatcher.scheduler.advanceUntilIdle()
        vm.switchTo(PlateOption("Walid", "XX123Y"))
        dispatcher.scheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals("XX123Y", s.activeVrn)
        assertNull(s.switching)
    }

    @Test
    fun `network failure surfaces message instead of crashing`() = runTest(dispatcher) {
        val vm = vm()
        dispatcher.scheduler.advanceUntilIdle()
        api.failNextGet = true
        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value.message != null)
    }

    @Test
    fun `saveSetup normalizes plates and leaves setup mode`() = runTest(dispatcher) {
        val store = FakeCredentialStore(null)
        val vm = vm(store)
        vm.saveSetup("u", "p", "rh-950-f", "xx 123 y")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("RH950F", store.config!!.wasilPlate)
        assertEquals("XX123Y", store.config!!.walidPlate)
        assertTrue(!vm.state.value.needsSetup)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.ui.MainViewModelTest"`
Expected: compilation FAILURE.

- [ ] **Step 3: Write minimal implementation**

`MainViewModel.kt`:

```kotlin
package dev.wasil.permit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.store.CredentialStore
import dev.wasil.permit.data.store.PermitConfig
import dev.wasil.permit.data.store.normalizePlate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlateOption(val label: String, val vrn: String)

data class UiState(
    val needsSetup: Boolean = false,
    val loading: Boolean = false,
    val switching: String? = null,
    val activeVrn: String? = null,
    val options: List<PlateOption> = emptyList(),
    val message: String? = null,
)

class MainViewModel(
    private val repository: PermitRepository,
    private val credentialStore: CredentialStore,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val config = credentialStore.load()
        if (config == null) {
            _state.update { it.copy(needsSetup = true) }
        } else {
            _state.update { it.copy(options = config.toOptions()) }
            refresh()
        }
    }

    private fun PermitConfig.toOptions() = listOf(
        PlateOption("Wasil", wasilPlate),
        PlateOption("Walid", walidPlate),
    )

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
        }
    }

    fun switchTo(option: PlateOption) {
        viewModelScope.launch {
            _state.update { it.copy(switching = option.vrn) }
            runCatching { repository.switchTo(option.vrn) }
                .onSuccess { result ->
                    when (result) {
                        is PermitRepository.SwitchResult.Confirmed -> _state.update {
                            it.copy(
                                switching = null,
                                activeVrn = result.activeVrn,
                                message = "Permit confirmed on ${option.label}'s car (${result.activeVrn})",
                            )
                        }
                        is PermitRepository.SwitchResult.Mismatch -> _state.update {
                            it.copy(
                                switching = null,
                                activeVrn = result.serverActiveVrn,
                                message = "WARNING: server reports ${result.serverActiveVrn ?: "no plate"} active - check the website!",
                            )
                        }
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(switching = null, message = "Switch failed: ${e.message}. Permit NOT changed - retry.")
                    }
                }
        }
    }

    fun saveSetup(username: String, password: String, wasilPlate: String, walidPlate: String) {
        val config = PermitConfig(
            username = username.trim(),
            password = password,
            wasilPlate = normalizePlate(wasilPlate),
            walidPlate = normalizePlate(walidPlate),
        )
        credentialStore.save(config)
        _state.update { it.copy(needsSetup = false, options = config.toOptions()) }
        refresh()
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wasil.permit.ui.MainViewModelTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src
git commit -m "feat: MainViewModel with setup, refresh and verified-switch flows"
```

---

### Task 8: Compose UI + dependency wiring

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/PermitApp.kt` (Application subclass: builds TokenStore, EncryptedCredentialStore, OkHttp client, PermitApi, PermitRepository)
- Create: `android/app/src/main/java/dev/wasil/permit/ui/MainScreen.kt`
- Create: `android/app/src/main/java/dev/wasil/permit/ui/SetupScreen.kt`
- Modify: `android/app/src/main/java/dev/wasil/permit/MainActivity.kt` (replace placeholder)
- Modify: `android/app/src/main/AndroidManifest.xml` (register `android:name=".PermitApp"` on `<application>`)

**Interfaces:**
- Consumes: everything from Tasks 3–7. `buildAuthenticatedClient` (Task 5), `buildPermitApi` (Task 3), `MainViewModel`/`UiState`/`PlateOption` (Task 7).

- [ ] **Step 1: Write the wiring**

`PermitApp.kt`:

```kotlin
package dev.wasil.permit

import android.app.Application
import dev.wasil.permit.data.PermitRepository
import dev.wasil.permit.data.api.ApiConstants
import dev.wasil.permit.data.api.buildPermitApi
import dev.wasil.permit.data.auth.TokenStore
import dev.wasil.permit.data.auth.buildAuthenticatedClient
import dev.wasil.permit.data.store.CredentialStore
import dev.wasil.permit.data.store.EncryptedCredentialStore
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Composition root - two users, one screen: no DI framework needed. */
class PermitApp : Application() {
    lateinit var credentialStore: CredentialStore
        private set
    lateinit var repository: PermitRepository
        private set

    override fun onCreate() {
        super.onCreate()
        credentialStore = EncryptedCredentialStore(this)
        val baseUrl = ApiConstants.BASE_URL.toHttpUrl()
        val client = buildAuthenticatedClient(baseUrl, TokenStore(), credentialStore)
        repository = PermitRepository(buildPermitApi(baseUrl, client))
    }
}
```

Manifest change: add `android:name=".PermitApp"` to the `<application>` element.

- [ ] **Step 2: Write the screens**

`SetupScreen.kt`:

```kotlin
package dev.wasil.permit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SetupScreen(onSave: (String, String, String, String) -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var wasilPlate by rememberSaveable { mutableStateOf("") }
    var walidPlate by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("One-time setup", style = MaterialTheme.typography.headlineSmall)
        Text("Stored encrypted on this phone only.")
        OutlinedTextField(username, { username = it }, label = { Text("Permit username") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Permit password") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(wasilPlate, { wasilPlate = it }, label = { Text("Wasil's plate") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(walidPlate, { walidPlate = it }, label = { Text("Walid's plate") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { onSave(username, password, wasilPlate, walidPlate) },
            enabled = username.isNotBlank() && password.isNotBlank() &&
                wasilPlate.isNotBlank() && walidPlate.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
    }
}
```

`MainScreen.kt`:

```kotlin
package dev.wasil.permit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(
    state: UiState,
    onSwitch: (PlateOption) -> Unit,
    onRefresh: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Permit is on", style = MaterialTheme.typography.titleMedium)
            if (state.loading) {
                CircularProgressIndicator()
            } else {
                val activeLabel = state.options.firstOrNull { it.vrn == state.activeVrn }?.label
                Text(
                    when {
                        state.activeVrn == null -> "No plate active"
                        activeLabel != null -> "$activeLabel's car (${state.activeVrn})"
                        else -> state.activeVrn
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
            state.options.forEach { option ->
                val isActive = option.vrn == state.activeVrn
                val isSwitching = state.switching == option.vrn
                Button(
                    onClick = { onSwitch(option) },
                    enabled = !isActive && state.switching == null && !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            isSwitching -> "Switching…"
                            isActive -> "${option.label}'s car (active)"
                            else -> "Set to ${option.label}'s car"
                        }
                    )
                }
            }
            TextButton(onClick = onRefresh, enabled = !state.loading && state.switching == null) {
                Text("Refresh")
            }
        }
    }
}
```

`MainActivity.kt` (replace placeholder):

```kotlin
package dev.wasil.permit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wasil.permit.ui.MainScreen
import dev.wasil.permit.ui.MainViewModel
import dev.wasil.permit.ui.SetupScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as PermitApp
                return MainViewModel(app.repository, app.credentialStore) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                if (state.needsSetup) {
                    SetupScreen(onSave = viewModel::saveSetup)
                } else {
                    MainScreen(
                        state = state,
                        onSwitch = viewModel::switchTo,
                        onRefresh = viewModel::refresh,
                        onMessageShown = viewModel::consumeMessage,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: Build + full test suite**

Run: `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all unit tests pass, APK produced.

- [ ] **Step 4: Manual smoke test (requires Wasil, on a real phone)**

1. Install: `adb install app\build\outputs\apk\debug\app-debug.apk` (or copy the APK to the phone).
2. First launch → setup screen → enter permit username/password + both plates → Save.
3. Main screen shows the currently active plate (cross-check against parkeervergunningen.amsterdam.nl).
4. Tap the inactive car's button → "Switching…" → confirm snackbar + label update.
5. Verify on the website that the plate actually switched.
6. Switch back. Kill and relaunch the app — no login prompt, state loads.

- [ ] **Step 5: Commit**

```bash
git add android/app/src
git commit -m "feat: Compose UI - setup screen and one-tap permit switching"
```

---

### Task 9: README + final verification

**Files:**
- Create: `README.md` (repo root)

**Interfaces:**
- Consumes: everything; documents the repo for future phases.

- [ ] **Step 1: Write README.md**

```markdown
# Permit Switcher

Switches the shared Amsterdam visitor parking permit between Wasil's and
Walid's cars. See `PROJECT_BRIEF.md` for the full roadmap.

- `permit.py` — Phase 0 Python CLI proof of the permit API (source of truth
  for request/response shapes).
- `android/` — Phase 1 native Kotlin app (Compose, MVVM, Retrofit).

## Build

Requires Android SDK (`C:\Android\sdk`, see `android/local.properties`) and
JDK 21 (Android Studio's bundled JBR is configured in `gradle.properties`).

    cd android
    .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest

APK: `android/app/build/outputs/apk/debug/app-debug.apk`

## Security notes

- No credentials or plates live in this repo. Everything is entered on the
  in-app setup screen and stored in EncryptedSharedPreferences.
- The API JWT lives 1 hour; the app re-logs-in transparently on 401.
- Every switch is verified by re-reading permit state (`read-after-write`);
  a mismatch shows a loud warning instead of a false success.
```

- [ ] **Step 2: Full clean verification**

Run: `.\gradlew.bat clean :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, every unit test green.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: README for Phase 1"
```

---

## Deferred to later phases (do NOT build now)

- WorkManager retry for failed activates (brief lists it as a safety behavior; for Phase 1 the explicit error message + manual retry covers it — WorkManager comes with Phase 2's background triggers, where no human is watching the screen).
- Point-in-polygon zone checks from `permit.geo_json`, permit-expiry reminder (2026-11-19), Bluetooth/activity detection, Firebase shared state, free-zone map.
