# v0.5 — Correctable Parked Pin and Tariff Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the two location beliefs the app holds silently — where the car is, and what that place costs — visible on the map, and let the first one be corrected by hand.

**Architecture:** All decision logic goes into two pure, JVM-testable files in the `ui` package (`MapZones.kt` for hit-testing, `PinCorrection.kt` for the correction rules). `MapScreen` holds only state and wiring; `MapCanvas` only draws. The tariff polygons are already parsed and in memory — nothing new is fetched, parsed or stored.

**Tech Stack:** Kotlin 2.1, Jetpack Compose + Material 3, osmdroid (maps), JUnit 4, Gradle wrapper at `android/gradlew`.

## Global Constraints

- **Test command:** `cd android && ./gradlew testDebugUnitTest` — all 185 existing tests must still pass at every commit.
- **Never commit the Firebase database URL.** The repo and the APKs are public.
- **Identity colour (Wasil's blue, Walid's terracotta) never appears in a generic `ColorScheme` slot** and never on the map for anything that is not a person. Map features use the quiet zone/tariff neutrals only. See the comment block at `Theme.kt:78-87`.
- **Map colours are mode-independent.** osmdroid's MAPNIK tiles stay light whatever the app theme does, so a dark-mode-only value can vanish. See `Color.kt:97-102`.
- **Correction cap is 300 m**, named `CORRECTION_MAX_M`.
- **A correction never claims or releases the permit on its own** — it only ever offers.
- **Copy is calm and sentence-cased** in the app UI (notification copy is a separate, known inconsistency — do not follow it).
- Commit after every task. Do not push. Do not merge to master.

---

## File Structure

| File | Responsibility |
|---|---|
| `ui/MapZones.kt` (modify) | Pure hit-testing for taps on bare map: zones, then tariff areas. Plus tariff display formatting. |
| `ui/PinCorrection.kt` (create) | Pure rules for a pin correction: the distance cap, zone re-resolution, and whether the paid/free answer flipped. |
| `ui/MapCanvas.kt` (modify) | Drawing only — tariff polygons (cached), the ghost marker, the car-marker tap hook. |
| `ui/MapScreen.kt` (modify) | State and wiring: move mode, the tariff chip, the info card, applying a correction. |
| `ui/HandoffTabs.kt` (modify) | Passes `tariffAreas` and `zoneResolver` down to `MapScreen`. |
| `ui/theme/Color.kt`, `ui/theme/Theme.kt` (modify) | One new mode-independent map neutral, `TariffBoundary`. |
| `test/ui/MapZonesTest.kt` (modify) | Hit precedence. |
| `test/ui/PinCorrectionTest.kt` (create) | Cap, re-resolution, flip detection, and that nothing claims. |

**Branch:** work on `v05-design`, which already carries the spec and the recorded decisions.

---

### Task 1: Unified map hit-testing

The map tap handler is about to serve three purposes. This gives it one rule in one tested place.

Note the car marker is deliberately **not** a rung here: osmdroid does marker hit-testing itself and the car marker's own click listener consumes the tap first (Task 5). `mapHitAt` decides only what a tap on bare map means. Placing mode is also not a rung — `MapScreen` never calls this while placing.

Passing an empty `tariffAreas` list is how "the overlay is switched off" is expressed, so the toggle needs no branch of its own.

**Files:**
- Modify: `android/app/src/main/java/dev/wasil/permit/ui/MapZones.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/ui/MapZonesTest.kt`

**Interfaces:**
- Consumes: `zoneHitAt(point, home, freeZones): ZoneRef?` and `ZoneRef` (already in this file); `TariffArea(code, name, tariffText, polygons)`, `ZonePolygon`, `LatLng`, `pointInPolygon` from `dev.wasil.permit.parking.zones`.
- Produces: `sealed interface MapHit` with `MapHit.Zone(ref: ZoneRef)` and `MapHit.Tariff(area: TariffArea)`; `mapHitAt(point: GeoPoint, home: FreeZone?, freeZones: List<FreeZone>, tariffAreas: List<TariffArea>): MapHit?`; `tariffAreaAt(point: GeoPoint, areas: List<TariffArea>): TariffArea?`; `tariffSummary(area: TariffArea): String`.

- [ ] **Step 1: Write the failing tests**

Append to `android/app/src/test/java/dev/wasil/permit/ui/MapZonesTest.kt`, and add these imports at the top of the file:

```kotlin
import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.ZonePolygon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
```

```kotlin
    // A square covering roughly 52.3705..52.3715 N, 4.8895..4.8905 E — big
    // enough to contain the home zone above, so the precedence test is real.
    private val paidArea = TariffArea(
        code = "T13B",
        name = "Basistarief TC3 ma-za 09-24",
        tariffText = "€5,37/h",
        polygons = listOf(
            ZonePolygon(
                listOf(
                    LatLng(52.3600, 4.8800),
                    LatLng(52.3600, 4.9100),
                    LatLng(52.3800, 4.9100),
                    LatLng(52.3800, 4.8800),
                ),
            ),
        ),
    )

    @Test
    fun `a tap inside both a zone and a tariff area hits the zone`() {
        val tap = GeoPoint(52.3702, 4.8952, 0f) // centre of home, inside paidArea
        assertEquals(
            MapHit.Zone(ZoneRef.Home),
            mapHitAt(tap, home, emptyList(), listOf(paidArea)),
        )
    }

    @Test
    fun `a tap on a tariff area with no zone under it hits the tariff area`() {
        val tap = GeoPoint(52.3650, 4.8850, 0f) // inside paidArea, outside every zone
        assertEquals(
            MapHit.Tariff(paidArea),
            mapHitAt(tap, home, listOf(zoneA, zoneB), listOf(paidArea)),
        )
    }

    @Test
    fun `an empty tariff list is how the overlay is switched off`() {
        val tap = GeoPoint(52.3650, 4.8850, 0f) // inside paidArea, outside every zone
        assertNull(mapHitAt(tap, home, emptyList(), emptyList()))
    }

    @Test
    fun `a tap outside everything hits nothing`() {
        assertNull(mapHitAt(GeoPoint(10.0, 10.0, 0f), home, listOf(zoneA), listOf(paidArea)))
    }

    @Test
    fun `zone precedence still uses the nearest centre inside mapHitAt`() {
        val big = FreeZone(52.0, 4.0, radiusM = 200.0)
        val small = FreeZone(52.0009, 4.0, radiusM = 30.0)
        val tap = GeoPoint(52.0009, 4.0, 0f)
        assertEquals(
            MapHit.Zone(ZoneRef.Free(1)),
            mapHitAt(tap, null, listOf(big, small), emptyList()),
        )
    }

    @Test
    fun `tariff summary leads with the rate then the description`() {
        assertEquals("€5,37/h · Basistarief TC3 ma-za 09-24", tariffSummary(paidArea))
    }

    @Test
    fun `tariff summary omits a missing rate rather than showing an empty separator`() {
        assertEquals("Basistarief TC3 ma-za 09-24", tariffSummary(paidArea.copy(tariffText = "")))
    }

    @Test
    fun `tariffAreaAt finds the containing area`() {
        assertEquals(paidArea, tariffAreaAt(GeoPoint(52.3650, 4.8850, 0f), listOf(paidArea)))
        assertNull(tariffAreaAt(GeoPoint(10.0, 10.0, 0f), listOf(paidArea)))
    }

    @Test
    fun `the real bundled asset still parses to the expected shape`() {
        // Guards against a bad asset swap: the overlay and the claim decision
        // both read this file, and an empty parse silently disables both.
        val json = java.io.File("src/main/assets/amsterdam_tarieven.json").readText()
        val areas = dev.wasil.permit.parking.zones.TariffAreas.parse(json)
        assertEquals(29, areas.size)
        assertTrue(areas.all { it.polygons.isNotEmpty() })
        assertTrue(areas.all { area -> area.polygons.all { it.outer.size >= 3 } })
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "dev.wasil.permit.ui.MapZonesTest"`

Expected: FAIL — `Unresolved reference: MapHit`, `Unresolved reference: mapHitAt`, `Unresolved reference: tariffSummary`, `Unresolved reference: tariffAreaAt`.

- [ ] **Step 3: Implement**

Append to `android/app/src/main/java/dev/wasil/permit/ui/MapZones.kt`, adding these imports at the top:

```kotlin
import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.pointInPolygon
```

```kotlin
/** What a tap on bare map landed on. The car marker is absent on purpose:
 * osmdroid hit-tests markers itself and the car marker's own click listener
 * consumes the tap before the map events overlay sees it. */
sealed interface MapHit {
    data class Zone(val ref: ZoneRef) : MapHit
    data class Tariff(val area: TariffArea) : MapHit
}

/**
 * The single precedence rule for a tap on bare map:
 *
 *  1. Zone circles — home and free — by the existing nearest-centre rule.
 *  2. Tariff areas, and only those passed in.
 *  3. Nothing.
 *
 * Zones beat tariff areas because a zone is something placed by hand while a
 * tariff area is neighbourhood-sized and will almost always be under the tap
 * as well: the specific thing beats the ambient thing.
 *
 * Callers pass an empty [tariffAreas] when the overlay is switched off, so the
 * toggle needs no branch of its own. Callers must not call this at all while
 * placing a zone candidate or moving the car pin — in those modes every tap is
 * a placement.
 */
fun mapHitAt(
    point: GeoPoint,
    home: FreeZone?,
    freeZones: List<FreeZone>,
    tariffAreas: List<TariffArea>,
): MapHit? {
    zoneHitAt(point, home, freeZones)?.let { return MapHit.Zone(it) }
    return tariffAreaAt(point, tariffAreas)?.let { MapHit.Tariff(it) }
}

/** The first tariff area containing [point], matching how [dev.wasil.permit.parking.zones.ZoneResolver]
 * picks one, so the map cannot disagree with the claim decision about which area you are in. */
fun tariffAreaAt(point: GeoPoint, areas: List<TariffArea>): TariffArea? {
    val p = LatLng(point.lat, point.lng)
    return areas.firstOrNull { area -> area.polygons.any { pointInPolygon(p, it) } }
}

/** Rate first, then the description that carries the hours — the rate is the
 * part worth reading at a glance. Amsterdam's own comma decimal is kept. */
fun tariffSummary(area: TariffArea): String =
    listOf(area.tariffText, area.name).filter { it.isNotBlank() }.joinToString(" · ")
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "dev.wasil.permit.ui.MapZonesTest"`

Expected: PASS, all tests in the class.

- [ ] **Step 5: Run the whole suite**

Run: `cd android && ./gradlew testDebugUnitTest`

Expected: PASS. `zoneHitAt` is unchanged and still has its own tests.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/dev/wasil/permit/ui/MapZones.kt android/app/src/test/java/dev/wasil/permit/ui/MapZonesTest.kt
git commit -m "feat: one tested precedence rule for taps on the map"
```

---

### Task 2: The correction rules

Everything that decides what a pin correction *means*, with no Android and no Compose in it, so it can be tested on the JVM. `MapScreen` will hold state and call this.

The cap is the fat-finger guard described in the spec: a mis-tap that lands inside a free zone flips `parkedOutside` to false, which tells the other phone it is free to claim while the car sits on a paid street with no permit.

**Files:**
- Create: `android/app/src/main/java/dev/wasil/permit/ui/PinCorrection.kt`
- Test: `android/app/src/test/java/dev/wasil/permit/ui/PinCorrectionTest.kt`

**Interfaces:**
- Consumes: `GeoPoint`, `distanceMeters(a, b): Double` from `dev.wasil.permit.parking`; `ZoneResolver.resolve(point): ZoneInfo` and `ZoneInfo.Paid(area: TariffArea?)` from `dev.wasil.permit.parking.zones`.
- Produces: `const val CORRECTION_MAX_M = 300.0`; `enum class Flip { NONE, NOW_PAID, NOW_FREE }`; `sealed interface CorrectionResult` with `CorrectionResult.TooFar(distanceM: Double)` and `CorrectionResult.Ok(point: GeoPoint, zoneCode: String?, parkedOutside: Boolean, flip: Flip)`; `correctionFor(from: GeoPoint, to: GeoPoint, wasParkedOutside: Boolean, resolver: ZoneResolver): CorrectionResult`.

- [ ] **Step 1: Write the failing tests**

Create `android/app/src/test/java/dev/wasil/permit/ui/PinCorrectionTest.kt`:

```kotlin
package dev.wasil.permit.ui

import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.zones.LatLng
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.ZonePolygon
import dev.wasil.permit.parking.zones.ZoneResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinCorrectionTest {

    // A square from 52.3705 to 52.3715 N — 4.8895 to 4.8905 E.
    private val paidArea = TariffArea(
        code = "T13B",
        name = "Basistarief TC3 ma-za 09-24",
        tariffText = "€5,37/h",
        polygons = listOf(
            ZonePolygon(
                listOf(
                    LatLng(52.3705, 4.8895),
                    LatLng(52.3705, 4.8905),
                    LatLng(52.3715, 4.8905),
                    LatLng(52.3715, 4.8895),
                ),
            ),
        ),
    )

    // 60 m circle centred ~111 m south of the paid square, so the two never overlap.
    private val freeZone = FreeZone(52.3700, 4.8900, radiusM = 60.0, label = "Free spot")

    private val resolver = ZoneResolver(null, listOf(freeZone), listOf(paidArea))

    private val inFree = GeoPoint(52.3700, 4.8900, 0f)
    private val inPaid = GeoPoint(52.3710, 4.8900, 0f)

    /** One degree of latitude is ~111,195 m under the app's haversine radius. */
    private fun northOf(p: GeoPoint, metres: Double) =
        GeoPoint(p.lat + metres / 111_194.9, p.lng, 0f)

    @Test
    fun `a nudge within the cap is accepted`() {
        val result = correctionFor(inFree, northOf(inFree, 200.0), false, resolver)
        assertTrue(result is CorrectionResult.Ok)
    }

    @Test
    fun `a tap beyond the cap is refused and reports how far it was`() {
        val result = correctionFor(inFree, northOf(inFree, 500.0), false, resolver)
        assertTrue(result is CorrectionResult.TooFar)
        assertEquals(500.0, (result as CorrectionResult.TooFar).distanceM, 2.0)
    }

    @Test
    fun `correcting from a free zone onto a paid street flips to paid`() {
        val result = correctionFor(inFree, inPaid, false, resolver) as CorrectionResult.Ok
        assertEquals(inPaid, result.point)
        assertEquals("T13B", result.zoneCode)
        assertTrue(result.parkedOutside)
        assertEquals(Flip.NOW_PAID, result.flip)
    }

    @Test
    fun `correcting from a paid street into a free zone flips to free and clears the code`() {
        val result = correctionFor(inPaid, inFree, true, resolver) as CorrectionResult.Ok
        assertEquals(null, result.zoneCode)
        assertEquals(false, result.parkedOutside)
        assertEquals(Flip.NOW_FREE, result.flip)
    }

    @Test
    fun `a correction within the same paid area is not a flip`() {
        val nudged = northOf(inPaid, 20.0)
        val result = correctionFor(inPaid, nudged, true, resolver) as CorrectionResult.Ok
        assertEquals("T13B", result.zoneCode)
        assertTrue(result.parkedOutside)
        assertEquals(Flip.NONE, result.flip)
    }

    @Test
    fun `a correction onto an unmetered street is free but not a flip when it already was`() {
        val nowhere = GeoPoint(52.3690, 4.8800, 0f) // no zone, no tariff area
        val result = correctionFor(inFree, nowhere, false, resolver) as CorrectionResult.Ok
        assertEquals(null, result.zoneCode)
        assertEquals(false, result.parkedOutside)
        assertEquals(Flip.NONE, result.flip)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests "dev.wasil.permit.ui.PinCorrectionTest"`

Expected: FAIL — `Unresolved reference: correctionFor`, `Unresolved reference: CorrectionResult`, `Unresolved reference: Flip`.

- [ ] **Step 3: Implement**

Create `android/app/src/main/java/dev/wasil/permit/ui/PinCorrection.kt`:

```kotlin
package dev.wasil.permit.ui

import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.distanceMeters
import dev.wasil.permit.parking.zones.ZoneInfo
import dev.wasil.permit.parking.zones.ZoneResolver

/**
 * The furthest a correction may move the car pin.
 *
 * This is a fat-finger guard, not a GPS one. A mis-tap that keeps the car in a
 * paid area harms nothing — the other phone's guard reads the parkedOutside
 * flag, not the coordinates. A mis-tap that lands inside a free zone flips that
 * flag to false, which tells the other phone it is free to claim while the car
 * is actually on a paid street with no permit. That is a fine.
 *
 * 300 m is far beyond any accepted fix (ParkDecisionEngine rejects accuracy
 * worse than 25 m) and far short of a different neighbourhood. A tap further
 * away is not a correction at all — it is a different parking spot, and
 * detection will pick that up on its own.
 */
const val CORRECTION_MAX_M = 300.0

/** Whether re-resolving the zone changed the paid/free answer. */
enum class Flip { NONE, NOW_PAID, NOW_FREE }

sealed interface CorrectionResult {
    data class TooFar(val distanceM: Double) : CorrectionResult

    /** Not yet applied — the caller writes these to the store on confirm. */
    data class Ok(
        val point: GeoPoint,
        val zoneCode: String?,
        val parkedOutside: Boolean,
        val flip: Flip,
    ) : CorrectionResult
}

/**
 * What moving the car pin from [from] to [to] would mean.
 *
 * The zone is re-resolved rather than carried over, because that is the whole
 * point: 40 m is the difference between a paid street and the free zone around
 * the corner, so a correction can change whether the permit should be held at
 * all. Deciding is all this does — it never claims, releases or writes.
 */
fun correctionFor(
    from: GeoPoint,
    to: GeoPoint,
    wasParkedOutside: Boolean,
    resolver: ZoneResolver,
): CorrectionResult {
    val distanceM = distanceMeters(from, to)
    if (distanceM > CORRECTION_MAX_M) return CorrectionResult.TooFar(distanceM)

    val zone = resolver.resolve(to)
    val parkedOutside = zone is ZoneInfo.Paid
    return CorrectionResult.Ok(
        point = to,
        zoneCode = (zone as? ZoneInfo.Paid)?.area?.code,
        parkedOutside = parkedOutside,
        flip = when {
            parkedOutside == wasParkedOutside -> Flip.NONE
            parkedOutside -> Flip.NOW_PAID
            else -> Flip.NOW_FREE
        },
    )
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "dev.wasil.permit.ui.PinCorrectionTest"`

Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/wasil/permit/ui/PinCorrection.kt android/app/src/test/java/dev/wasil/permit/ui/PinCorrectionTest.kt
git commit -m "feat: pin correction rules — 300 m cap, zone re-resolution, flip detection"
```

---

### Task 3: Draw the tariff areas

Rendering only. 29 areas, 170 rings, 17,503 vertices — cheap to draw, expensive to *rebuild*, so the osmdroid `Polygon` objects are built once and re-added on each update pass rather than reconstructed like the markers are.

The highlight is a separate polygon drawn on top rather than a mutation of a cached one, so the cache stays immutable.

**Files:**
- Modify: `android/app/src/main/java/dev/wasil/permit/ui/theme/Color.kt`
- Modify: `android/app/src/main/java/dev/wasil/permit/ui/theme/Theme.kt`
- Modify: `android/app/src/main/java/dev/wasil/permit/ui/MapCanvas.kt`

**Interfaces:**
- Consumes: `TariffArea`, `ZonePolygon`, `LatLng` from `dev.wasil.permit.parking.zones`.
- Produces: `MapCanvas(..., tariffAreas: List<TariffArea> = emptyList(), highlightArea: TariffArea? = null, ...)`; `HandoffColors.tariffBoundary: Color`; `dev.wasil.permit.ui.theme.TariffBoundary`.

- [ ] **Step 1: Add the colour token**

Append to `android/app/src/main/java/dev/wasil/permit/ui/theme/Color.kt`:

```kotlin
// Tariff-area boundaries: Amsterdam's own paid-parking regions, a fourth map
// category beside the three zone colours above. Named TariffBoundary, not
// TariffArea, because dev.wasil.permit.parking.zones.TariffArea is a data
// class and the two are imported together.
//
// Same reasoning as the zone colours: one value for both modes, because the
// MAPNIK tiles underneath stay light whatever the app theme does. Kept clear
// of both brothers' identity hues so a boundary can never read as "whose car",
// and quiet enough that 29 of them at once cannot bury a zone circle — the
// fill is drawn at 0.07 alpha for exactly that reason.
val TariffBoundary = Color(0xFF5B6B7A)
```

- [ ] **Step 2: Expose it through the theme**

In `android/app/src/main/java/dev/wasil/permit/ui/theme/Theme.kt`, add the property to the `HandoffColors` data class, immediately after `val zoneCandidate: Color,` (line 39):

```kotlin
    val tariffBoundary: Color,
```

Then add it to both palettes. In `DarkColors`, change the zone line to:

```kotlin
    zoneHome = ZoneHome, zoneFree = ZoneFree, zoneCandidate = ZoneCandidate,
    tariffBoundary = TariffBoundary,
```

And make the identical change to the same line in `LightColors`.

- [ ] **Step 3: Draw them in MapCanvas**

In `android/app/src/main/java/dev/wasil/permit/ui/MapCanvas.kt`, add these imports:

```kotlin
import dev.wasil.permit.parking.zones.TariffArea
import dev.wasil.permit.parking.zones.ZonePolygon
```

Add two parameters to `MapCanvas`, after `candidateZone: FreeZone? = null,`:

```kotlin
    /** Every area to draw as a boundary. Empty means the overlay is off. */
    tariffAreas: List<TariffArea> = emptyList(),
    /** Outlined on top of the rest — the area the car is in, or a tapped one. */
    highlightArea: TariffArea? = null,
```

Inside the composable, next to `lastCentre`, add the cache:

```kotlin
    // The tariff polygons are 170 rings and ~17,500 vertices. Rebuilding them
    // on every update pass — as the markers and zone circles are, deliberately,
    // to avoid stale closures — would be the one expensive thing on this
    // screen. They never change, so they are built once and re-added. Identity
    // comparison is enough: PermitApp holds a single immutable list.
    val tariffCache = remember {
        mutableStateOf<Pair<List<TariffArea>, List<Polygon>>?>(null)
    }
```

In the `update` lambda, immediately after the `map.overlays.add(MapEventsOverlay(...))` block and **before** the `homeZone?.let { ... }` block — so boundaries render underneath the zone circles and markers — add:

```kotlin
            if (tariffAreas.isNotEmpty()) {
                val cached = tariffCache.value
                val polys = if (cached != null && cached.first === tariffAreas) {
                    cached.second
                } else {
                    tariffAreas.flatMap { area ->
                        area.polygons.map { ring ->
                            tariffPolygon(map, ring, colors.tariffBoundary, fillAlpha = 0.07f, strokeWidthPx = 2f)
                        }
                    }.also { tariffCache.value = tariffAreas to it }
                }
                polys.forEach { map.overlays.add(it) }
            }
            highlightArea?.polygons?.forEach { ring ->
                map.overlays.add(
                    tariffPolygon(map, ring, colors.tariffBoundary, fillAlpha = 0f, strokeWidthPx = 5f),
                )
            }
```

Then add the helper at the bottom of the file, beside `zoneCircle`:

```kotlin
/**
 * One ring of a tariff area. Holes are ignored: osmdroid's Polygon does support
 * them, but a boundary drawn at 0.07 alpha reads the same either way and the
 * app's own point-in-polygon test (which does honour holes) is what actually
 * decides anything. Returns false from its click listener for the same reason
 * zoneCircle does — [mapHitAt] is the single place that decides what a tap hit.
 */
private fun tariffPolygon(
    map: MapView,
    ring: ZonePolygon,
    color: Color,
    fillAlpha: Float,
    strokeWidthPx: Float,
): Polygon = Polygon(map).apply {
    points = ring.outer.map { OsmGeoPoint(it.lat, it.lng) }
    fillColor = color.copy(alpha = fillAlpha).toArgb()
    strokeColor = color.toArgb()
    strokeWidth = strokeWidthPx
    setOnClickListener { _, _, _ -> false }
    infoWindow = null
}
```

- [ ] **Step 4: Verify it compiles and nothing regressed**

Run: `cd android && ./gradlew testDebugUnitTest`

Expected: PASS, 185 + the new tests from Tasks 1 and 2.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/wasil/permit/ui/MapCanvas.kt android/app/src/main/java/dev/wasil/permit/ui/theme/Color.kt android/app/src/main/java/dev/wasil/permit/ui/theme/Theme.kt
git commit -m "feat: draw Amsterdam tariff-area boundaries on the map"
```

---

### Task 4: The tariff chip and the info card

Wires the overlay to a control and makes a tap on an area say something. Default off, with only the car's own area outlined — 29 filled polygons would bury the zone circles the map exists to show.

**Files:**
- Modify: `android/app/src/main/java/dev/wasil/permit/ui/MapScreen.kt`
- Modify: `android/app/src/main/java/dev/wasil/permit/ui/HandoffTabs.kt`

**Interfaces:**
- Consumes: `mapHitAt`, `MapHit`, `tariffAreaAt`, `tariffSummary` (Task 1); `MapCanvas(tariffAreas =, highlightArea =)` (Task 3); `PermitApp.tariffAreas: List<TariffArea>?`.
- Produces: `MapScreen(stateStore: ParkStateStore, freeZoneStore: FreeZoneStore, tariffAreas: List<TariffArea>)`.

- [ ] **Step 1: Widen the MapScreen signature**

In `android/app/src/main/java/dev/wasil/permit/ui/MapScreen.kt`, add the import:

```kotlin
import dev.wasil.permit.parking.zones.TariffArea
```

Change the signature (line 62) to:

```kotlin
fun MapScreen(
    stateStore: ParkStateStore,
    freeZoneStore: FreeZoneStore,
    tariffAreas: List<TariffArea>,
) {
```

In `android/app/src/main/java/dev/wasil/permit/ui/HandoffTabs.kt`, change the `Tab.MAP` branch (lines 101-104) to:

```kotlin
                Tab.MAP -> MapScreen(
                    stateStore = app.parkStateStore,
                    freeZoneStore = app.freeZoneStore,
                    // null when the bundled asset is missing or corrupt — the
                    // overlay simply has nothing to draw, same as ZoneResolver
                    // falling back to "assume paid".
                    tariffAreas = app.tariffAreas.orEmpty(),
                )
```

- [ ] **Step 2: Add the state and route the tap**

In `MapScreen`, beside the other `remember` declarations (after `zoneDialogTarget`, line 81), add:

```kotlin
    var showTariff by remember { mutableStateOf(false) }
    var selectedArea by remember { mutableStateOf<TariffArea?>(null) }

    // Off by default: only the area the car is in, outlined. That is the state
    // that answers "why did it claim here?" without changing what the map looks
    // like. The chip reveals the other 28.
    val visibleTariffAreas = if (showTariff) tariffAreas else emptyList()
    val highlightArea = selectedArea ?: car?.let { tariffAreaAt(it, tariffAreas) }
```

Replace the `onMapTap` lambda (lines 105-111) with:

```kotlin
                onMapTap = { point ->
                    if (addingKind != null) {
                        candidatePoint = point
                    } else {
                        when (val hit = mapHitAt(point, homeZone, freeZones, visibleTariffAreas)) {
                            is MapHit.Zone -> zoneDialogTarget = hit.ref
                            is MapHit.Tariff -> selectedArea = hit.area
                            null -> selectedArea = null
                        }
                    }
                },
```

And pass the new arguments to `MapCanvas`, after `candidateZone = candidateZone,`:

```kotlin
                tariffAreas = visibleTariffAreas,
                highlightArea = highlightArea,
```

- [ ] **Step 3: Add the chip and the info card**

In the bottom control `Card` (the `else ->` branch, lines 189-209), add a third button to the `Row`, after the "Add free zone" button:

```kotlin
                            OutlinedButton(
                                onClick = {
                                    showTariff = !showTariff
                                    if (!showTariff) selectedArea = null
                                },
                                modifier = Modifier.weight(1f),
                                enabled = tariffAreas.isNotEmpty(),
                            ) { Text(if (showTariff) "Hide tariffs" else "Tariffs") }
```

Then, immediately after that whole `when { ... }` block and before the `if (car != null && ...)` walk button, add the info card:

```kotlin
                selectedArea?.let { area ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(area.code, style = MaterialTheme.typography.titleMedium)
                            Text(
                                tariffSummary(area),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
```

- [ ] **Step 4: Verify**

Run: `cd android && ./gradlew testDebugUnitTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/dev/wasil/permit/ui/MapScreen.kt android/app/src/main/java/dev/wasil/permit/ui/HandoffTabs.kt
git commit -m "feat: tariff overlay chip and tap-to-inspect card"
```

---

### Task 5: Moving the car pin

The correction itself, end to end: tap the car marker, tap the true spot, confirm — and if the paid/free answer flipped, an explicit offer that the user has to accept.

Tap-to-place, not drag, for the reason in the spec: `MapCanvas` rebuilds its whole overlay list on every update pass, so a dragged marker would have to survive those rebuilds, and the app already teaches tap-then-confirm for zones.

**Files:**
- Modify: `android/app/src/main/java/dev/wasil/permit/ui/MapCanvas.kt`
- Modify: `android/app/src/main/java/dev/wasil/permit/ui/MapScreen.kt`

**Interfaces:**
- Consumes: `correctionFor`, `CorrectionResult`, `Flip`, `CORRECTION_MAX_M` (Task 2); `PermitApp.zoneResolver(): ZoneResolver`; `SharedSync.requestSync(context)`; `ParkActionReceiver.perform(context, action)` with `ACTION_CLAIM` / `ACTION_GIVE_BACK`.
- Produces: `MapCanvas(..., ghostCar: GeoPoint? = null, onCarTap: (() -> Unit)? = null)`; `MapScreen(..., zoneResolver: () -> ZoneResolver)`.

- [ ] **Step 1: Give MapCanvas a ghost marker and a car-tap hook**

In `android/app/src/main/java/dev/wasil/permit/ui/MapCanvas.kt`, add two more parameters after `highlightArea: TariffArea? = null,`:

```kotlin
    /** The proposed new car position while a correction is being confirmed. */
    ghostCar: GeoPoint? = null,
    /** Tapping the car marker; null leaves the marker inert as before. */
    onCarTap: (() -> Unit)? = null,
```

Replace the car marker's click listener (line 125) so the tap is usable. The comment above it explains why it returned `true`; keep suppressing the info window either way:

```kotlin
                    // Consumes the tap either way: osmdroid's stock InfoWindow
                    // bubble is off-centre and just repeats the title, so it is
                    // suppressed whether or not anyone is listening.
                    setOnMarkerClickListener { _, _ ->
                        onCarTap?.invoke()
                        true
                    }
```

Add the ghost marker immediately after the `car?.let { ... }` block and before `me?.let { ... }`:

```kotlin
            ghostCar?.let {
                map.overlays.add(Marker(map).apply {
                    position = OsmGeoPoint(it.lat, it.lng)
                    icon = ContextCompat.getDrawable(map.context, R.drawable.ic_marker_car)
                    // Half-strength so it reads as "proposed", not "there are
                    // two cars" — the same unsaved-candidate idea as the zone
                    // candidate circle, which is thicker and darker instead.
                    alpha = 0.5f
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ -> true }
                })
            }
```

- [ ] **Step 2: Take the resolver in MapScreen**

In `android/app/src/main/java/dev/wasil/permit/ui/MapScreen.kt`, add the imports:

```kotlin
import dev.wasil.permit.parking.android.ParkActionReceiver
import dev.wasil.permit.parking.android.SharedSync
import dev.wasil.permit.parking.zones.ZoneResolver
```

Extend the signature:

```kotlin
fun MapScreen(
    stateStore: ParkStateStore,
    freeZoneStore: FreeZoneStore,
    tariffAreas: List<TariffArea>,
    // A factory, not an instance: the resolver closes over the home zone and
    // the free-zone list, both of which this very screen can change.
    zoneResolver: () -> ZoneResolver,
) {
```

And in `HandoffTabs.kt`, add to the `Tab.MAP` branch:

```kotlin
                    zoneResolver = { app.zoneResolver() },
```

- [ ] **Step 3: Add the move-mode state**

In `MapScreen`, beside the other state:

```kotlin
    var movingPin by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<CorrectionResult.Ok?>(null) }
    var tooFarM by remember { mutableStateOf<Double?>(null) }
    var flipToConfirm by remember { mutableStateOf<Flip?>(null) }
```

- [ ] **Step 4: Route taps while moving**

Change the `onMapTap` lambda from Task 4 so move mode takes precedence over hit-testing — while moving, every tap is a placement:

```kotlin
                onMapTap = { point ->
                    when {
                        addingKind != null -> candidatePoint = point
                        movingPin && car != null -> {
                            when (val r = correctionFor(car, point, stateStore.parkedOutside, zoneResolver())) {
                                is CorrectionResult.TooFar -> {
                                    tooFarM = r.distanceM
                                    pending = null
                                }
                                is CorrectionResult.Ok -> {
                                    pending = r
                                    tooFarM = null
                                }
                            }
                        }
                        else -> when (val hit = mapHitAt(point, homeZone, freeZones, visibleTariffAreas)) {
                            is MapHit.Zone -> zoneDialogTarget = hit.ref
                            is MapHit.Tariff -> selectedArea = hit.area
                            null -> selectedArea = null
                        }
                    }
                },
```

Pass the ghost and the tap hook to `MapCanvas`:

```kotlin
                ghostCar = pending?.point,
                onCarTap = {
                    if (car != null && stateStore.parked) {
                        movingPin = true
                        selectedArea = null
                    }
                },
```

- [ ] **Step 5: Add the move card**

Add two branches to the `when { ... }` block that already picks between `ZoneCandidateCard`, `ZoneHintCard` and the button row. Put them **first**, above `candidatePoint != null ->`:

```kotlin
                    pending != null -> MovePinCard(
                        distanceM = distanceMeters(car ?: pending!!.point, pending!!.point),
                        flip = pending!!.flip,
                        onCancel = {
                            pending = null
                            movingPin = false
                        },
                        onConfirm = {
                            val result = pending!!
                            stateStore.lastParkLocation = result.point
                            stateStore.lastZoneCode = result.zoneCode
                            stateStore.parkedOutside = result.parkedOutside
                            // The other phone learns the corrected position
                            // through the path that already exists —
                            // SyncStateWorker reads exactly these three fields.
                            SharedSync.requestSync(context)
                            flipToConfirm = result.flip.takeIf { it != Flip.NONE }
                            pending = null
                            movingPin = false
                        },
                    )
                    movingPin -> ZoneHintCard(
                        text = tooFarM?.let { "%.0f m away — too far to be a correction".format(it) }
                            ?: "Tap where the car really is",
                        onCancel = {
                            movingPin = false
                            tooFarM = null
                        },
                    )
```

Add the import for `distanceMeters`:

```kotlin
import dev.wasil.permit.parking.distanceMeters
```

Then add the card composable at the bottom of the file, beside `ZoneCandidateCard`:

```kotlin
/**
 * Confirms a pin correction, and says plainly when it would change the answer
 * to "is this paid parking?" — because that answer is what decides whether the
 * permit should be here at all.
 */
@Composable
private fun MovePinCard(
    distanceM: Double,
    flip: Flip,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Move the car pin", style = MaterialTheme.typography.bodyLarge)
            Text(
                "%.0f m from where it was detected".format(distanceM),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (flip != Flip.NONE) {
                Text(
                    when (flip) {
                        Flip.NOW_PAID -> "This spot is paid parking. You will be asked about the permit."
                        Flip.NOW_FREE -> "This spot is not paid parking. You will be asked about the permit."
                        Flip.NONE -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = onConfirm) { Text("Confirm") }
            }
        }
    }
}
```

- [ ] **Step 6: Add the flip dialog**

At the bottom of the `MapScreen` composable body, after the existing `zoneDialogTarget?.let { ... }` block:

```kotlin
    // Never automatic. Detection auto-switches because the app is the only
    // party that noticed anything; a correction is the opposite — the user is
    // standing next to the car and just told the app something it did not know.
    flipToConfirm?.let { flip ->
        AlertDialog(
            onDismissRequest = { flipToConfirm = null },
            title = {
                Text(
                    if (flip == Flip.NOW_PAID) "This spot is paid parking" else "This spot is free",
                )
            },
            text = {
                Text(
                    if (flip == Flip.NOW_PAID) {
                        "The corrected position is in a paid area. Claim the permit for your car?"
                    } else {
                        "The corrected position is not in a paid area. Hand the permit back?"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ParkActionReceiver.perform(
                        context,
                        if (flip == Flip.NOW_PAID) {
                            ParkActionReceiver.ACTION_CLAIM
                        } else {
                            ParkActionReceiver.ACTION_GIVE_BACK
                        },
                    )
                    flipToConfirm = null
                }) { Text(if (flip == Flip.NOW_PAID) "Claim" else "Hand back") }
            },
            dismissButton = {
                TextButton(onClick = { flipToConfirm = null }) { Text("Not now") }
            },
        )
    }
```

- [ ] **Step 7: Verify**

Run: `cd android && ./gradlew testDebugUnitTest`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/dev/wasil/permit/ui/MapCanvas.kt android/app/src/main/java/dev/wasil/permit/ui/MapScreen.kt android/app/src/main/java/dev/wasil/permit/ui/HandoffTabs.kt
git commit -m "feat: correct the parked pin by tapping the true spot"
```

---

### Task 6: Look at it, then write it down

The house rule, and it is not optional: **every significant defect in the last five releases was caught by looking at the screen, not by tests or review.** 185 passing tests were blind to black-on-black text, an identity colour leaking app-wide, a white slab snackbar and two invisible controls. Both features in this release draw over map tiles, which is exactly where two of those bugs lived.

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/BACKLOG.md`
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: Build and install the debug APK**

```bash
cd android && ./gradlew installDebug
```

Expected: `BUILD SUCCESSFUL`, app installed on the running emulator.

- [ ] **Step 2: Screenshot the five things most likely to be wrong**

Use raw `adb` (deliberately not an MCP server with ADB access — see `docs/TOOLING.md`). For each: `adb exec-out screencap -p > <name>.png`, then read the PNG.

Check each one by eye:

1. **The tariff overlay toggled on, in dark mode** — do the boundaries read against light MAPNIK tiles, and can you still find the zone circles underneath?
2. **The same, in light mode** — the colour is mode-independent by design, so this is where a wrong value shows.
3. **The tariff info card** — code and rate legible, card sitting on its own surface rather than straight on tiles.
4. **The move-pin card with a ghost marker** — is the ghost distinguishable from the real car marker at 50% alpha, or does it just look like a rendering glitch?
5. **The flip dialog** — the one screen that only appears in an unusual sequence, and therefore the one most likely to ship broken.

- [ ] **Step 3: Fix anything the screenshots show, then re-shoot**

If a colour, contrast or layout problem appears, fix it and repeat Step 2 for that screen. Do not proceed on "it is probably fine".

- [ ] **Step 4: Update the changelog**

Add to `CHANGELOG.md`, above the v0.4 entry, matching the existing entry format:

```markdown
## v0.5

The map now shows what the app actually thinks, and lets you correct it.

- **Correct the parked pin.** Tap the car marker, tap where the car really is,
  confirm. GPS is accurate to about 25 m at best, which is enough to put the car
  on the wrong side of the street and send "Walk to car" the wrong way.
- The correction **re-checks whether that spot is paid parking**, because 40 m
  is the difference between a paid street and the free zone around the corner.
  If the answer changes, the app asks about the permit — it never decides for
  you. Corrections further than 300 m are refused: that is a different parking
  spot, not a correction.
- The corrected position reaches the other phone, so it is not just your view.
- **Tariff areas on the map.** Amsterdam's 29 paid-parking regions, which have
  been silently deciding whether the permit gets claimed since v0.2, are now
  something you can see. Tap one for its code, rate and hours. Off by default;
  the area your car is in is always outlined.
```

- [ ] **Step 5: Update the backlog and roadmap**

In `docs/BACKLOG.md`, replace the `## v0.5` heading line with `## v0.5 — shipped` and add beneath it:

```markdown
Shipped. Typing a tariff area code by hand was dropped during design: nothing
reads it. The nearest-payment-machine rebuild that replaces circles entirely is
`docs/v0.6-zone-registry.md`.
```

In `docs/ROADMAP.md`, update the "Shipped so far" line to include `v0.4` and `v0.5`.

- [ ] **Step 6: Commit**

```bash
git add CHANGELOG.md docs/BACKLOG.md docs/ROADMAP.md
git commit -m "docs: changelog and backlog for v0.5"
```

- [ ] **Step 7: Shut the emulator down**

```bash
adb emu kill
```

- [ ] **Step 8: Report, do not push**

Wasil's standing instruction is to commit but **not** push and **not** merge. Report what was built, attach the screenshots, and leave `v05-design` where it is for his review.

---

## Self-Review

**Spec coverage.** Part 1 (correctable pin) → Tasks 2 and 5. Part 2 (tariff overlay) → Tasks 3 and 4. "One hit-test, one precedence" → Task 1. The testing section → the JVM tests in Tasks 1–2 and the by-eye pass in Task 6. Decision 1 (writes through to shared state) → Task 5 Step 5, `SharedSync.requestSync`. Decision 2 (300 m) → Task 2. Decision 3 (overlay in full, default off) → Task 4 Step 2, `showTariff = false`. The dropped tariff-code entry is recorded in Task 6 Step 5 rather than built. **No gaps.**

**Type consistency.** `CorrectionResult.Ok(point, zoneCode, parkedOutside, flip)` is produced in Task 2 and destructured in Task 5 Step 5 with those exact names. `MapHit.Zone(ref)` / `MapHit.Tariff(area)` are produced in Task 1 and matched in Task 4 Step 2 and Task 5 Step 4. `MapCanvas` gains `tariffAreas` and `highlightArea` in Task 3 and `ghostCar` and `onCarTap` in Task 5; `MapScreen` gains `tariffAreas` in Task 4 and `zoneResolver` in Task 5, and `HandoffTabs` is updated in both. `tariffBoundary` is added to `HandoffColors` in Task 3 Step 2 and read in Task 3 Step 3.

**One thing the implementer should watch.** Task 5 Step 4 puts `movingPin` above the `mapHitAt` branch on purpose. If it is placed below, a tap that lands inside a zone circle while moving the pin will open the rename dialog instead of placing the correction, and the correction will appear to do nothing.
