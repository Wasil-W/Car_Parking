package dev.wasil.permit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
    // Content colour for text/icons drawn on an identity `*Strong` fill (the
    // hand-over button). Computed per mode — see Color.kt — because neither
    // white nor the existing cream/charcoal text tokens clear 4.5:1 against
    // every strong fill in every mode.
    val onStrong: Color,
    // Zones on the map — a third category, neither identity nor state. See
    // Color.kt for why these are the same value in both modes.
    val zoneHome: Color,
    val zoneFree: Color,
    val zoneCandidate: Color,
    // Amsterdam's paid-parking region boundaries. Same mode-independence as the
    // zone colours above, and for the same reason: the map tiles never change.
    val tariffBoundary: Color,
    val tariffSelected: Color,
    val walkRoute: Color,
    // The hand-over button's fill, deliberately deeper than `*Strong` — see
    // Color.kt for why a slab and a stroke want different values.
    val wasilAction: Color,
    val walidAction: Color,
    val onAction: Color,
) {
    /**
     * Identity colour by **roster slot**, not by car.
     *
     * Two pairs, and there will never be a third. Identity by hue is a
     * two-body affordance: the palette already spends green on `fine`, rust on
     * `alert`, two neutrals on zones, a grey-blue on tariff boundaries and a
     * teal on the walk route, so there is no fourth safe hue — and colour stops
     * working as an identifier past two anyway, because nobody remembers which
     * car is the teal one. Past two,
     * [dev.wasil.permit.parking.Roster.identitySlotOf] returns null and every
     * caller falls back to the neutral branch it already had for "nobody is
     * holding it".
     *
     * The names are the two slots' seeded names and nothing more; slot 0 is
     * slot 0 on both phones because roster order comes from the permit account.
     */
    fun actionFor(slot: Int) = if (slot == 0) wasilAction else walidAction
    fun strongFor(slot: Int) = if (slot == 0) wasilStrong else walidStrong
    fun containerFor(slot: Int) = if (slot == 0) wasilContainer else walidContainer
    fun onContainerFor(slot: Int) = if (slot == 0) wasilOnContainer else walidOnContainer
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
    onStrong = OnIdentityStrongDark,
    wasilAction = WasilActionDark, walidAction = WalidActionDark, onAction = OnActionAll,
    zoneHome = ZoneHome, zoneFree = ZoneFree, zoneCandidate = ZoneCandidate,
    tariffBoundary = TariffBoundary,
    tariffSelected = TariffSelected,
    walkRoute = WalkRoute,
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
    onStrong = OnIdentityStrongLight,
    wasilAction = WasilStrongLight, walidAction = WalidStrongLight, onAction = OnActionAll,
    zoneHome = ZoneHome, zoneFree = ZoneFree, zoneCandidate = ZoneCandidate,
    tariffBoundary = TariffBoundary,
    tariffSelected = TariffSelected,
    walkRoute = WalkRoute,
)

// Every slot below is set explicitly so nothing falls back to Material 3's
// stock baseline purple — and every slot is neutral. Identity colour (Wasil's
// blue, Walid's terracotta) never appears in a generic ColorScheme slot,
// because every Button, Switch, Slider, RadioButton, focus ring and progress
// indicator reads `primary`/`primaryContainer` — if either carried Wasil's
// blue, it would render that way on Walid's phone too, and "blue" would mean
// "interactive" instead of "Wasil". Identity appears only where code asks for
// it explicitly, via HandoffColors.strongFor/containerFor/onContainerFor (see
// the hand-over button in MainScreen). See docs/superpowers/specs for the
// full rationale.
private val DarkScheme = darkColorScheme(
    primary = TextPrimaryDark, onPrimary = SurfaceDark,
    primaryContainer = SurfaceContainerHighDark, onPrimaryContainer = TextPrimaryDark,
    inversePrimary = TextPrimaryLight,
    secondary = TextSecondaryDark, onSecondary = SurfaceDark,
    secondaryContainer = CardDark, onSecondaryContainer = TextPrimaryDark,
    tertiary = ArcInactiveDark, onTertiary = TextPrimaryDark,
    tertiaryContainer = HairlineDark, onTertiaryContainer = TextPrimaryDark,
    background = SurfaceDark, onBackground = TextPrimaryDark,
    surface = SurfaceDark, onSurface = TextPrimaryDark,
    surfaceVariant = CardDark, onSurfaceVariant = TextSecondaryDark,
    surfaceTint = TextPrimaryDark,
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
    primary = TextPrimaryLight, onPrimary = CardLight,
    primaryContainer = SurfaceContainerLight, onPrimaryContainer = TextPrimaryLight,
    inversePrimary = TextPrimaryDark,
    secondary = TextSecondaryLight, onSecondary = CardLight,
    secondaryContainer = HairlineLight, onSecondaryContainer = TextPrimaryLight,
    tertiary = ArcInactiveLight, onTertiary = TextPrimaryLight,
    tertiaryContainer = SurfaceContainerLight, onTertiaryContainer = TextPrimaryLight,
    background = SurfaceLight, onBackground = TextPrimaryLight,
    surface = SurfaceLight, onSurface = TextPrimaryLight,
    surfaceVariant = CardLight, onSurfaceVariant = TextSecondaryLight,
    surfaceTint = TextPrimaryLight,
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
