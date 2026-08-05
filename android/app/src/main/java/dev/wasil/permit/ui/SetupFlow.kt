package dev.wasil.permit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wasil.permit.parking.MyCar
import dev.wasil.permit.parking.ParkStateStore

/**
 * One question, and it is the only one the app cannot work without.
 *
 * It used to be two, and the first was a four-field permit form — username,
 * password and both plates — shown before anything else. That was the app
 * saying "no permit means I cannot work", which is not true and has not been
 * true since the obligation and settlement halves were split apart in v0.6.
 * The form is in Settings now, under "Add a permit", asked at the moment it
 * matters instead of at the door.
 *
 * Whose phone this is stays here because detection genuinely stops without it:
 * `ParkDetectionUseCase` returns `NotConfigured` when `myCar` is unset, so a
 * park would never be decided or recorded.
 */
@Composable
fun SetupFlow(
    stateStore: ParkStateStore,
    onDone: () -> Unit,
) {
    WhosePhoneStep(
        current = stateStore.myCar,
        onPick = { stateStore.myCar = it },
        onContinue = onDone,
    )
}

@Composable
private fun WhosePhoneStep(
    current: MyCar?,
    onPick: (MyCar) -> Unit,
    onContinue: () -> Unit,
) {
    var picked by remember { mutableStateOf(current) }
    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Whose phone is this?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "It tells the app which car this phone drives away in, which is how " +
                "a park gets noticed at all.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MyCar.entries.forEach { car ->
            val label = if (car == MyCar.WASIL) "Wasil" else "Walid"
            TextButton(
                onClick = { picked = car; onPick(car) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(selected = picked == car, onClick = null)
                Text("  $label's phone")
            }
        }
        Button(
            onClick = onContinue,
            enabled = picked != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue") }
        Text(
            "Everything else — a permit, a paired car, sharing with the other " +
                "phone, a home zone — is optional and lives in Settings. " +
                "Handoff will tell you what a spot costs without any of them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
