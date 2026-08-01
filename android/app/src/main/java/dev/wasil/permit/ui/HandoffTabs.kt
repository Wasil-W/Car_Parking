package dev.wasil.permit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import dev.wasil.permit.ui.theme.HandoffShapes
import dev.wasil.permit.ui.theme.LocalHandoffColors

private enum class Tab(val label: String, val icon: ImageVector) {
    PERMIT("Permit", Icons.Filled.Home),
    MAP("Map", Icons.Filled.LocationOn),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/**
 * Three destinations behind a bottom bar. They were previously three bare icons
 * floating in the middle of the main screen, belonging to nothing — as tabs they
 * get a surface, labels and a selected state, and the map becomes one tap away
 * rather than a detour.
 */
@SuppressLint("MissingPermission")
@Composable
fun HandoffTabs(
    app: PermitApp,
    state: UiState,
    onSwitch: (PlateOption) -> Unit,
    onRefresh: () -> Unit,
    onMessageShown: () -> Unit,
    onConfirmBlocked: () -> Unit,
    onDismissBlocked: () -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.PERMIT) }
    // Read once, here rather than inside MapScreen, so the preview map on the
    // Permit tab can frame both pins too — Wasil's ask was that neither screen
    // make him go hunting for the car.
    val context = LocalContext.current
    var me by remember { mutableStateOf<GeoPoint?>(null) }
    LaunchedEffect(Unit) { me = PlayServicesSignals(context).currentLocation() }
    val snackbar = remember { SnackbarHostState() }
    val colors = LocalHandoffColors.current

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
                Tab.PERMIT -> MainScreen(
                    state = state,
                    myCar = app.parkStateStore.myCar,
                    car = app.parkStateStore.lastParkLocation,
                    me = me,
                    onSwitch = onSwitch,
                    onRefresh = onRefresh,
                    onOpenMap = { tab = Tab.MAP },
                    onConfirmBlocked = onConfirmBlocked,
                    onDismissBlocked = onDismissBlocked,
                )
                Tab.MAP -> MapScreen(
                    stateStore = app.parkStateStore,
                    freeZoneStore = app.freeZoneStore,
                    me = me,
                    // null when the bundled asset is missing or corrupt — the
                    // overlay then simply has nothing to draw, mirroring how
                    // ZoneResolver falls back to "assume paid".
                    tariffAreas = app.tariffAreas.orEmpty(),
                    zoneResolver = { app.zoneResolver() },
                )
                Tab.SETTINGS -> SettingsScreen(
                    stateStore = app.parkStateStore,
                    freeZoneStore = app.freeZoneStore,
                    sharedStore = { app.sharedStateStore() },
                    onOpenMap = { tab = Tab.MAP },
                )
            }
        }
    }
}
