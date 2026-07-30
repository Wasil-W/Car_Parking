package dev.wasil.permit.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Every identity and state colour has its own value per mode — nothing is
 * shared between the palettes. The light fills are a step darker so that white
 * text on them reaches ~6:1; reusing the dark mid-tones gave only 3.16:1 for
 * text sitting on the fill. See the spec's "How the light palette was derived".
 */
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

val SurfaceDark = Color(0xFF171715)
val CardDark = Color(0xFF201F1C)
val HairlineDark = Color(0xFF2E2D28)
val TextPrimaryDark = Color(0xFFDEDCD4)
val TextSecondaryDark = Color(0xFF918E85)

// Extra neutral steps for the ColorScheme surface-container ramp. Same warm
// dark/light neutral family as Surface/Card/Hairline above, just filling the
// gaps at the darkest (dark mode) and mid-tone (light mode) ends so no
// container slot has to fall back to Material 3's default purple.
val SurfaceContainerLowestDark = Color(0xFF0F0F0E)
val SurfaceContainerHighDark = Color(0xFF272622)

val SurfaceLight = Color(0xFFFAF9F5)
val CardLight = Color(0xFFFFFFFF)
val HairlineLight = Color(0xFFE3E1D9)
val TextPrimaryLight = Color(0xFF26241F)
val TextSecondaryLight = Color(0xFF6B6862)

val SurfaceContainerLight = Color(0xFFF2F0E9)
val SurfaceContainerHighLight = Color(0xFFEAE8DE)
