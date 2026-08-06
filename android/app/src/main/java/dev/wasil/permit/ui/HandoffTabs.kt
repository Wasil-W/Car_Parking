package dev.wasil.permit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import dev.wasil.permit.PermitApp
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.android.PlayServicesSignals
import dev.wasil.permit.parking.android.reverseGeocodePlace
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import dev.wasil.permit.ui.theme.HandoffShapes
import dev.wasil.permit.ui.theme.LocalHandoffColors

/**
 * The first tab used to be called "Permit", and that was the last place the app
 * assumed a permit exists. It is the wrong word for a screen that may have no
 * permit on it — and after this release it often will not, because a spot's
 * rate and hours are worth showing to someone who never had one.
 *
 * "Now" rather than "Parked": the screen is true when you are not parked too
 * ("Not parked", and what to expect when you are), whereas a tab called Parked
 * would be lying at exactly that moment. It also reads as a set with the other
 * three — what is true now, where it is, what already happened, how it is
 * configured.
 */
private enum class Tab(val label: String, val icon: ImageVector) {
    NOW("Now", Icons.Filled.Home),
    MAP("Map", Icons.Filled.LocationOn),
    HISTORY("History", Icons.AutoMirrored.Filled.List),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/**
 * Four destinations behind a bottom bar. They were previously three bare icons
 * floating in the middle of the main screen, belonging to nothing — as tabs they
 * get a surface, labels and a selected state, and the map becomes one tap away
 * rather than a detour.
 *
 * The fourth arrives now and not before, on the rule from `USER-MODEL.md`: a
 * tab when there is something to list, never a room built in the hope of
 * finding furniture for it.
 */
@SuppressLint("MissingPermission")
@Composable
fun HandoffTabs(
    app: PermitApp,
    state: UiState,
    onSwitch: (PlateOption) -> Unit,
    onRefresh: () -> Unit,
    onMessageShown: () -> Unit,
    onSavePermit: (String, String, String, String) -> Unit,
    onConfirmBlocked: () -> Unit,
    onDismissBlocked: () -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.NOW) }
    // Read once, here rather than inside MapScreen, so the preview map on the
    // Now tab can frame both pins too — Wasil's ask was that neither screen
    // make him go hunting for the car.
    val context = LocalContext.current
    var me by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(Unit) { me = PlayServicesSignals(context).currentLocation() }
    val snackbar = remember { SnackbarHostState() }
    val colors = LocalHandoffColors.current

    // Which of the three permit screens is true. Recomputed on every tab change
    // so adding a permit in Settings is visible the moment you come back.
    val permitView = permitViewFor(
        permitAdded = remember(tab) { app.credentialStore.load() != null },
        sharingConfigured = remember(tab) { !app.parkStateStore.syncUrl.isNullOrBlank() },
    )

    // The parked spot's name, for the Now screen. Same geocoder and same
    // two-level name as the map header, so the two screens cannot describe one
    // place differently. Null while it resolves and null if it fails — the
    // wording below is written to read correctly without it.
    val parkedPoint = app.parkStateStore.lastParkLocation
        .takeUnless { app.parkStateStore.carLinkConnected }
    var place by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(parkedPoint) {
        place = parkedPoint?.let { point ->
            reverseGeocodePlace(context, point)
                ?.let { listOfNotNull(it.district, it.detail).joinToString(" · ") }
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(
        // Material's default snackbar uses the *inverse* surface for contrast,
        // which in a dark app means a stark white slab — the loudest thing on
        // any screen it appears on, for a message that is usually incidental.
        // A raised surface of our own reads as part of the app.
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = HandoffShapes.Control,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    actionColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        // The indicator means "selected", not "Wasil" — so it is
                        // a neutral. Using an identity colour here is exactly
                        // the collision the palette exists to prevent.
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSurface,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.NOW -> MainScreen(
                    state = state,
                    view = permitView,
                    myCar = app.parkStateStore.myCar,
                    // Hidden while driving, for the same reason as the map tab.
                    car = parkedPoint,
                    parked = app.parkStateStore.parked,
                    me = me,
                    demand = spotDemand(app),
                    place = place,
                    parkedSince = app.parkStateStore.parkedAtMs
                        .takeIf { it > 0 && app.parkStateStore.parked }
                        ?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) },
                    onSwitch = onSwitch,
                    onRefresh = onRefresh,
                    onOpenMap = { tab = Tab.MAP },
                    onOpenSettings = { tab = Tab.SETTINGS },
                    onConfirmBlocked = onConfirmBlocked,
                    onDismissBlocked = onDismissBlocked,
                )
                // Read on every visit rather than held in state: the log is
                // written by background workers, so a cached list would be
                // stale exactly when you look at it after a drive.
                Tab.HISTORY -> HistoryScreen(records = remember(tab) { app.parkLogStore.all() })
                Tab.MAP -> MapScreen(
                    stateStore = app.parkStateStore,
                    freeZoneStore = app.freeZoneStore,
                    me = me,
                    // null when the bundled asset is missing or corrupt — the
                    // overlay then simply has nothing to draw, mirroring how
                    // ZoneResolver falls back to "assume paid".
                    tariffAreas = app.tariffAreas.orEmpty(),
                    zoneResolver = { app.zoneResolver() },
                    routeClient = app.routeClient,
                    // Owned here rather than in MapScreen so that a position
                    // read by the locate-me button also reaches the preview map
                    // on the Permit tab — same reason `me` is read here at all.
                    // Null when the phone cannot say, which the map is careful
                    // to report rather than paper over.
                    onLocate = { PlayServicesSignals(context).currentLocation()?.also { me = it } },
                )
                Tab.SETTINGS -> SettingsScreen(
                    stateStore = app.parkStateStore,
                    freeZoneStore = app.freeZoneStore,
                    credentialStore = app.credentialStore,
                    sharedStore = { app.sharedStateStore() },
                    onSavePermit = onSavePermit,
                    onOpenMap = { tab = Tab.MAP },
                )
            }
        }
    }
}

/**
 * Resolves the parked spot into an obligation, reusing the same [ZoneResolver]
 * the claim decision uses so the screen can never disagree with the app about
 * whether a spot is free.
 *
 * Thin on purpose. Everything that used to be decided here — which zone counts
 * as free, what an unreadable tariff means, what to say outside charging
 * hours — moved into [spotDemandFor], where it is one total function over
 * [ZoneInfo] and a test can walk every branch of it. What is left is reading
 * the clock, which is the one thing that cannot be pure.
 *
 * Note the parked-with-no-position case: it hands `zone = null` through rather
 * than reporting "not parked", which is what this file used to do a line above
 * a card saying "Parked — location unknown".
 */
private fun spotDemand(app: PermitApp): SpotDemand {
    val store = app.parkStateStore
    val car = store.lastParkLocation
    val now = Calendar.getInstance()
    return spotDemandFor(
        parked = store.parked,
        zone = car?.let { app.zoneResolver().resolve(it) },
        dayIndex = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7,
        minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE),
    )
}
