package dev.wasil.permit.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wasil.permit.parking.MyCar
import dev.wasil.permit.parking.label
import dev.wasil.permit.ui.theme.LocalHandoffColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(
    state: UiState,
    myCar: MyCar?,
    onSwitch: (PlateOption) -> Unit,
    onRefresh: () -> Unit,
    onMessageShown: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMap: () -> Unit,
    onConfirmBlocked: () -> Unit,
    onDismissBlocked: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            onMessageShown()
        }
    }
    val colors = LocalHandoffColors.current
    val holder = holderFor(state.activeVrn, state.options)

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.loading) {
                CircularProgressIndicator()
            } else {
                // A tint with a strong border, never a slab of saturated colour.
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(
                        1.dp,
                        holder?.let(colors::strongFor) ?: MaterialTheme.colorScheme.outline,
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = holder?.let(colors::containerFor)
                            ?: MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    val onCard = holder?.let(colors::onContainerFor)
                        ?: MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HandoffMark(markStateFor(holder))
                        Text(
                            holder?.let { "${it.label()}'s car" } ?: "No plate active",
                            style = MaterialTheme.typography.headlineLarge,
                            color = onCard,
                        )
                        state.activeVrn?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = onCard)
                        }
                    }
                }
            }

            if (myCar != null && !state.loading) {
                val action = primaryActionFor(myCar, holder)
                val target = state.options.firstOrNull { it.label == action.target.label() }
                Button(
                    onClick = { target?.let(onSwitch) },
                    enabled = target != null && state.switching == null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    // The button carries whose car receives the permit — genuine
                    // identity information, not a generic accent — with a
                    // content colour computed to clear 4.5:1 against that fill
                    // in both modes (see Color.kt).
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.strongFor(action.target),
                        contentColor = colors.onStrong,
                    ),
                ) {
                    Text(if (state.switching != null) "Switching…" else action.label)
                }
            }

            state.otherStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                IconButton(onClick = onOpenMap) {
                    Icon(Icons.Filled.LocationOn, contentDescription = "Map")
                }
                IconButton(onClick = onRefresh, enabled = !state.loading && state.switching == null) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }
        }

        state.blocked?.let { blocked ->
            val time = { ms: Long ->
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
            }
            AlertDialog(
                onDismissRequest = onDismissBlocked,
                title = { Text("${blocked.otherLabel}'s car is parked") },
                text = {
                    Text(
                        "${blocked.otherLabel} parked at ${time(blocked.parkedAtMs)} " +
                            "(last seen ${time(blocked.heartbeatAtMs)}) and the permit is on their car. " +
                            "Claiming now would leave it unpermitted — that's a fine if it's still there.",
                    )
                },
                confirmButton = { TextButton(onClick = onConfirmBlocked) { Text("Claim anyway") } },
                dismissButton = { TextButton(onClick = onDismissBlocked) { Text("Cancel") } },
            )
        }
    }
}
