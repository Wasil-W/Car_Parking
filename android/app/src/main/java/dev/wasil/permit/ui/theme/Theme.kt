package dev.wasil.permit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.wasil.permit.parking.MyCar

/**
 * Colours ColorScheme has no slot for. Identity (wasil/walid) is only ever a
 * large fill; state (fine/alert) is only ever a small icon or label. Keeping
 * the two apart by hue AND scale is what stops "green" meaning both "Wasil"
 * and "OK" at once.
 */
data class HandoffColors(
    val wasilStrong: Color,
    val wasilContainer: Color,
    val wasilOnContainer: Color,
    val walidStrong: Color,
    val walidContainer: Color,
    val walidOnContainer: Color,
    val arcInactive: Color,
    val fine: Color,
    val alert: Color,
    val dot: Color,
) {
    fun strongFor(car: MyCar) = if (car == MyCar.WASIL) wasilStrong else walidStrong
    fun containerFor(car: MyCar) = if (car == MyCar.WASIL) wasilContainer else walidContainer
    fun onContainerFor(car: MyCar) = if (car == MyCar.WASIL) wasilOnContainer else walidOnContainer
}

val LocalHandoffColors = staticCompositionLocalOf<HandoffColors> {
    error("HandoffColors not provided — wrap content in HandoffTheme")
}

private val DarkColors = HandoffColors(
    wasilStrong = WasilStrongDark,
    wasilContainer = WasilContainerDark,
    wasilOnContainer = WasilOnContainerDark,
    walidStrong = WalidStrongDark,
    walidContainer = WalidContainerDark,
    walidOnContainer = WalidOnContainerDark,
    arcInactive = ArcInactiveDark, fine = FineDark, alert = AlertDark,
    dot = TextPrimaryDark,
)

private val LightColors = HandoffColors(
    wasilStrong = WasilStrongLight,
    wasilContainer = WasilContainerLight,
    wasilOnContainer = WasilOnContainerLight,
    walidStrong = WalidStrongLight,
    walidContainer = WalidContainerLight,
    walidOnContainer = WalidOnContainerLight,
    arcInactive = ArcInactiveLight, fine = FineLight, alert = AlertLight,
    // The dot must contrast with the hero card, which is a pale tint in light
    // mode — a white dot would vanish on it.
    dot = TextPrimaryLight,
)

// Every slot below is set explicitly so nothing falls back to Material 3's
// stock baseline purple. Secondary and tertiary resolve to neutrals (never a
// new hue, never Walid's terracotta in a generic slot); primaryContainer is
// the one generic slot that legitimately carries Wasil's blue, because
// primary already does. See docs/superpowers/specs for the full rationale.
private val DarkScheme = darkColorScheme(
    primary = WasilStrongDark, onPrimary = TextPrimaryDark,
    primaryContainer = WasilContainerDark, onPrimaryContainer = WasilOnContainerDark,
    inversePrimary = WasilStrongLight,
    secondary = TextSecondaryDark, onSecondary = SurfaceDark,
    secondaryContainer = CardDark, onSecondaryContainer = TextPrimaryDark,
    tertiary = ArcInactiveDark, onTertiary = TextPrimaryDark,
    tertiaryContainer = HairlineDark, onTertiaryContainer = TextPrimaryDark,
    background = SurfaceDark, onBackground = TextPrimaryDark,
    surface = SurfaceDark, onSurface = TextPrimaryDark,
    surfaceVariant = CardDark, onSurfaceVariant = TextSecondaryDark,
    surfaceTint = WasilStrongDark,
    inverseSurface = SurfaceLight, inverseOnSurface = TextPrimaryLight,
    error = AlertDark, onError = TextPrimaryDark,
    errorContainer = AlertContainerDark, onErrorContainer = AlertOnContainerDark,
    outline = HairlineDark, outlineVariant = HairlineDark,
    scrim = SurfaceContainerLowestDark,
    surfaceBright = HairlineDark, surfaceDim = SurfaceDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceDark,
    surfaceContainer = CardDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = HairlineDark,
)

private val LightScheme = lightColorScheme(
    primary = WasilStrongLight, onPrimary = CardLight,
    primaryContainer = WasilContainerLight, onPrimaryContainer = WasilOnContainerLight,
    inversePrimary = WasilStrongDark,
    secondary = TextSecondaryLight, onSecondary = CardLight,
    secondaryContainer = HairlineLight, onSecondaryContainer = TextPrimaryLight,
    tertiary = ArcInactiveLight, onTertiary = TextPrimaryLight,
    tertiaryContainer = SurfaceContainerLight, onTertiaryContainer = TextPrimaryLight,
    background = SurfaceLight, onBackground = TextPrimaryLight,
    surface = SurfaceLight, onSurface = TextPrimaryLight,
    surfaceVariant = CardLight, onSurfaceVariant = TextSecondaryLight,
    surfaceTint = WasilStrongLight,
    inverseSurface = SurfaceDark, inverseOnSurface = TextPrimaryDark,
    error = AlertLight, onError = CardLight,
    errorContainer = AlertContainerLight, onErrorContainer = AlertOnContainerLight,
    outline = HairlineLight, outlineVariant = HairlineLight,
    scrim = TextPrimaryLight,
    surfaceBright = SurfaceLight, surfaceDim = HairlineLight,
    surfaceContainerLowest = CardLight,
    surfaceContainerLow = SurfaceContainerLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = HairlineLight,
)

/** Deliberately NOT dynamic colour: both phones must agree what blue means. */
@Composable
fun HandoffTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalHandoffColors provides if (darkTheme) DarkColors else LightColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = HandoffTypography,
            content = content,
        )
    }
}
