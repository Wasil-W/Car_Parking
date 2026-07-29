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

val ArcInactiveDark = Color(0xFF3E3E39)
val ArcInactiveLight = Color(0xFFC9C6BC)

val FineDark = Color(0xFF5C8A54)
val FineLight = Color(0xFF4C7645)
val AlertDark = Color(0xFFB3503C)
val AlertLight = Color(0xFF96412F)

val SurfaceDark = Color(0xFF171715)
val CardDark = Color(0xFF201F1C)
val HairlineDark = Color(0xFF2E2D28)
val TextPrimaryDark = Color(0xFFDEDCD4)
val TextSecondaryDark = Color(0xFF918E85)

val SurfaceLight = Color(0xFFFAF9F5)
val CardLight = Color(0xFFFFFFFF)
val HairlineLight = Color(0xFFE3E1D9)
val TextPrimaryLight = Color(0xFF26241F)
val TextSecondaryLight = Color(0xFF6B6862)
