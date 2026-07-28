package dev.wasil.permit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
    onOpenSettings: () -> Unit,
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
            Spacer(Modifier.height(16.dp))
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
            TextButton(onClick = onOpenSettings) {
                Text("Settings")
            }
        }
    }
}
