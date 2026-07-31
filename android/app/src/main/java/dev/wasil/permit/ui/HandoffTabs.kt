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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.wasil.permit.PermitApp
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
    val snackbar = remember { SnackbarHostState() }
    val colors = LocalHandoffColors.current

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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
                    onSwitch = onSwitch,
                    onRefresh = onRefresh,
                    onOpenMap = { tab = Tab.MAP },
                    onConfirmBlocked = onConfirmBlocked,
                    onDismissBlocked = onDismissBlocked,
                )
                Tab.MAP -> MapScreen(
                    stateStore = app.parkStateStore,
                    freeZoneStore = app.freeZoneStore,
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
