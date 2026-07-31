package dev.wasil.permit.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.wasil.permit.parking.GeoPoint
import dev.wasil.permit.parking.MyCar
import dev.wasil.permit.parking.label
import dev.wasil.permit.ui.theme.LocalHandoffColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Answers one question — who has the permit — and offers the single action that
 * can change it. With exactly two cars the permit can only move to one place,
 * so one button is right where two would be noise.
 */
@Composable
fun MainScreen(
    state: UiState,
    myCar: MyCar?,
    car: GeoPoint?,
    onSwitch: (PlateOption) -> Unit,
    onRefresh: () -> Unit,
    onOpenMap: () -> Unit,
    onConfirmBlocked: () -> Unit,
    onDismissBlocked: () -> Unit,
) {
    val colors = LocalHandoffColors.current
    val holder = holderFor(state.activeVrn, state.options)

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HandoffMark(markStateFor(holder), size = 20.dp)
                Text(
                    "  Handoff",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onRefresh,
                enabled = !state.loading && state.switching == null,
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
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
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.loading) {
                    CircularProgressIndicator(color = onCard)
                } else {
                    HandoffMark(markStateFor(holder), size = 60.dp)
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
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.strongFor(action.target),
                    contentColor = colors.onStrong,
                ),
            ) {
                Text(
                    if (state.switching != null) "Switching…" else action.label,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        state.otherStatus?.let { status ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    status,
                    modifier = Modifier.fillMaxWidth().padding(13.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // The car's location without leaving this screen — tap opens the map
        // tab. Takes the remaining height so the screen fills rather than
        // trailing off into empty space.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 16.dp)
                .clickable(onClick = onOpenMap),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            if (car == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No parked location yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))) {
                    MapCanvas(car = car, me = null, interactive = false, zoom = 16.0)
                }
            }
        }
    }

    state.blocked?.let { blocked ->
        val time = { ms: Long -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms)) }
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
