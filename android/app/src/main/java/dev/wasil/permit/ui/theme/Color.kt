package dev.wasil.permit.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Every identity and state colour has its own value per mode — nothing is
 * shared between the palettes. The light fills are a step darker so that white
 * text on them reaches ~6:1; reusing the dark mid-tones gave only 3.16:1 for
 * text sitting on the fill. See the spec's "How the light palette was derived".
 */
/**
 * The hand-over button's fill — deliberately deeper than `*Strong`.
 *
 * A button is not an arc. `*Strong` is tuned to read brightly as a thin stroke
 * on a card; the same value across a full-width slab is the loudest thing on
 * the screen, and it forced black label text (white measured only 4.34:1 on it),
 * which shouts twice over. These deeper fills carry white at 7.1:1 and 6.8:1 —
 * better contrast from a quieter colour.
 */
val WasilActionDark = Color(0xFF3F5B72)
val WalidActionDark = Color(0xFF7A5238)
val OnActionAll = Color(0xFFFFFFFF)

val WasilStrongDark = Color(0xFF5A7D9A)
val WasilContainerDark = Color(0xFF1E2A33)
val WasilOnContainerDark = Color(0xFFA8C0D4)
val WasilStrongLight = Color(0xFF45657F)
val WasilContainerLight = Color(0xFFE8EDF2)
val WasilOnContainerLight = Color(0xFF2F4A5F)

val WalidStrongDark = Color(0xFFB07B55)
val WalidContainerDark = Color(0xFF2C2118)
val WalidOnContainerDark = Color(0xFFD9B48F)
val WalidStrongLight = Color(0xFF8A5C39)
val WalidContainerLight = Color(0xFFF3E9E0)
val WalidOnContainerLight = Color(0xFF6B4529)

// Lightened/darkened from the first pass (#3E3E39 / #C9C6BC), which measured
// only 1.36:1 / 1.41:1 against the hero card containers — invisible as a
// dimmed arc. These reach >=3:1 (the non-text-graphic floor) against BOTH
// brothers' containers in their own mode, while staying below the contrast
// a lit identity arc gets against its own container, so the mark still
// reads as "one lit, one dimmed" rather than "two equally-strong arcs":
//   Dark:  vs WasilContainerDark ~3.28:1, vs WalidContainerDark ~3.52:1
//          (lit-on-own-container is 3.37:1 / 4.33:1)
//   Light: vs WasilContainerLight ~3.59:1, vs WalidContainerLight ~3.54:1
//          (lit-on-own-container is 5.21:1 / 4.78:1)
val ArcInactiveDark = Color(0xFF78786E)
val ArcInactiveLight = Color(0xFF7D7B75)

// Content colour for the hand-over button, which is filled with the target's
// identity "strong" colour (WasilStrong/WalidStrong). Computed, not guessed:
// the previous cream-on-blue dark-mode pairing (#DEDCD4 on #5A7D9A) measured
// 3.17:1, and even white only reaches 4.35:1 on that fill — neither clears
// the 4.5:1 text minimum. Both dark-mode identity fills are, perhaps
// unintuitively, light enough relative to true black that dark text clears
// 4.5:1 while light text does not:
//   Dark:  black on WasilStrongDark 4.84:1, on WalidStrongDark 5.80:1
//   Light: white on WasilStrongLight 6.14:1, on WalidStrongLight 5.72:1
// No existing near-black neutral (SurfaceDark etc.) is dark enough — they
// carry a warm tint that costs just enough luminance to fall short (e.g.
// SurfaceDark only reaches 4.13:1 on WasilStrongDark) — so this is a new,
// purpose-built pair rather than a reused surface/text token.
val OnIdentityStrongDark = Color(0xFF000000)
val OnIdentityStrongLight = Color(0xFFFFFFFF)

val FineDark = Color(0xFF5C8A54)
val FineLight = Color(0xFF4C7645)
val AlertDark = Color(0xFFB3503C)
val AlertLight = Color(0xFF96412F)

// Muted rust containers for the ColorScheme error family — same alert hue as
// AlertDark/AlertLight, just desaturated toward a fill, mirroring how
// Wasil/WalidContainer already relate to Wasil/WalidStrong.
val AlertContainerDark = Color(0xFF2E1D18)
val AlertOnContainerDark = Color(0xFFD9A08F)
val AlertContainerLight = Color(0xFFF5E4DF)
val AlertOnContainerLight = Color(0xFF6B2E1F)

// The dark ground, warmed in v0.7.0.
//
// Wasil: *"sometimes i feel like it is too dull and dosent have any vibe to
// it."* Counted before changing anything: on the map screen the tiles are about
// three quarters of the pixels and they are stock OpenStreetMap; of the rest,
// everything is a warm grey except the walk route. Identity colour — six tokens
// per brother, argued out over three versions — reaches exactly two elements,
// and neither is on that screen. So the palette is not dull, it is barely worn.
//
// The neutrals were #171715 / #201F1C / #2E2D28: red exceeding blue by 2, 4 and
// 6 points. That is warmth you can measure and not warmth you can see, which is
// the definition of a ground that has not decided anything. Pushing blue down
// until it reads costs no new token, no new hue and no rule, and it governs
// about nine tenths of the non-map pixels — the most effect available for the
// least risk.
//
// Checked rather than assumed: against the existing text colours the warmer
// ground is very slightly *better*, not worse.
//   primary text   13.07:1 -> 13.49:1
//   secondary      5.48:1  -> 5.66:1
// Computed from the sRGB relative-luminance formula.
//
// Warm and not cool. A blue-black was drawn and rejected: light mode is already
// a cream #FAF9F5, so a cool dark would make the two themes feel like two
// different apps, and it drifts toward Wasil's own blue-grey, which is the one
// hue on this screen that already means something.
val SurfaceDark = Color(0xFF16130E)
val CardDark = Color(0xFF221D15)
val HairlineDark = Color(0xFF332B20)
val TextPrimaryDark = Color(0xFFDEDCD4)
val TextSecondaryDark = Color(0xFF918E85)

// Extra neutral steps for the ColorScheme surface-container ramp. Same warm
// dark/light neutral family as Surface/Card/Hairline above, just filling the
// gaps at the darkest (dark mode) and mid-tone (light mode) ends so no
// container slot has to fall back to Material 3's default purple.
//
// Warmed with the rest in v0.7.0. Left on the old near-neutral values these
// would have been the two steps in the ramp that did not belong to it — and
// SurfaceContainerHighDark is the *pressed* state of every map control, so the
// mismatch would have shown up exactly where a control is being looked at.
val SurfaceContainerLowestDark = Color(0xFF0E0B07)
val SurfaceContainerHighDark = Color(0xFF2A241A)

val SurfaceLight = Color(0xFFFAF9F5)
val CardLight = Color(0xFFFFFFFF)
val HairlineLight = Color(0xFFE3E1D9)
val TextPrimaryLight = Color(0xFF26241F)
val TextSecondaryLight = Color(0xFF6B6862)

val SurfaceContainerLight = Color(0xFFF2F0E9)
val SurfaceContainerHighLight = Color(0xFFEAE8DE)

// Zones (home zone, free zones) are places, not identity or state — neither
// brother's colour, and not fine/alert. A third, deliberately quiet neutral,
// drawn from the same warm-grey chrome family as Hairline/TextSecondary
// above rather than a new hue, so a zone circle can never be misread as
// "whose car" or "is something wrong". Home is the darker, solid-ring
// treatment (singular, permanent); free zones are the lighter, dashed-ring
// treatment (plural, more casual); the candidate being placed is the darkest
// and thickest of the three so it visibly "pops" as unsaved.
//
// The "thickest" half of that was **untrue for two versions** and is only true
// again as of v0.7.0. v0.6.8 raised free zones 4f -> 9f to make them visible at
// all and left the candidate behind at 6f, so the unsaved ring — the one whose
// whole job is to stand out — was the *thinner* of the two. Nobody noticed,
// because the sentence above kept saying otherwise. Widths now live in
// MapCanvas.Line, in dp, where the candidate is genuinely the widest.
//
// Kept rather than quietly corrected, because this repo has a rule about
// trusting its own documents: a comment that describes what the code should do
// is not evidence that it does.
//
// These are the same value in both light and dark mode — deliberately not
// mode-paired like identity/state above. The map tile layer itself
// (osmdroid's MAPNIK source) doesn't change with the app's theme, so tying
// zone colour to dark/light would risk a dark-mode value vanishing against
// tiles that stay light regardless.
val ZoneHome = Color(0xFF4A463D)
val ZoneFree = Color(0xFF8C8676)
val ZoneCandidate = Color(0xFF211E19)

// Tariff-area boundaries: Amsterdam's own paid-parking regions, a fourth map
// category beside the three zone colours above. Named TariffBoundary and not
// TariffArea because dev.wasil.permit.parking.zones.TariffArea is a data class
// and the two are imported together.
//
// Same reasoning as the zone colours — one value for both modes, because the
// MAPNIK tiles underneath stay light whatever the app theme does. A grey-blue
// kept clear of both brothers' identity hues, so a boundary can never be
// misread as "whose car".
//
// **No fill.** This used to end "which is why the fill is drawn at 0.07 alpha",
// and v0.7.0 took the fill to zero without updating the sentence — the exact
// failure this release corrected twice elsewhere, committed here in the same
// breath. With 29 areas tiling the city that wash answered "is this ground
// inside some tariff area", which is nearly always yes, so it greyed the map
// without telling anyone anything; the chip names the area that matters in
// words. Wasil, shown both magnified side by side: *"no fill from now on."*
//
// The boundary itself carries the whole job now, as a 1.0dp dotted line — see
// MapCanvas.Line for why intermittent and not fainter.
val TariffBoundary = Color(0xFF5B6B7A)

// The selected part, which has to win against 28 others drawn in TariffBoundary
// plus whatever the map tiles are doing underneath. The first attempt reused
// TariffBoundary at a heavier stroke and was, in Wasil's words, "almost not
// noticable" — a weight change alone does not survive a busy tile layer.
// Near-black with a blue cast: unmistakably the same family, several steps
// darker, and far from either brother's identity hue so a selected region can
// never be misread as "whose car".
val TariffSelected = Color(0xFF16222B)

// The walking line back to the car. The one map colour allowed to be assertive
// — it is transient, it only appears when asked for, and its whole job is to be
// followed at a glance in the street. Still not either brother's hue: a route
// is a direction, not a person. Dashed, because it is a suggestion rather than
// a boundary, and the same value in both modes like every other map colour.
val WalkRoute = Color(0xFF2E7D6B)
