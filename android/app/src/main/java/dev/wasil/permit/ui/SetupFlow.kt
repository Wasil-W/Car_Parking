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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wasil.permit.parking.MyCar
import dev.wasil.permit.parking.ParkStateStore

@Composable
fun SetupFlow(
    stateStore: ParkStateStore,
    onSaveCredentials: (String, String, String, String) -> Unit,
    onDone: () -> Unit,
) {
    var step by remember { mutableIntStateOf(if (stateStore.myCar == null) 0 else 1) }

    when (step) {
        0 -> SetupScreen(onSave = { u, p, a, b ->
            onSaveCredentials(u, p, a, b)
            step = 1
        })
        else -> WhosePhoneStep(
            current = stateStore.myCar,
            onPick = { stateStore.myCar = it },
            onContinue = onDone,
        )
    }
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
            "This is what makes the app mirror itself — your phone offers to hand " +
                "the permit over, the other one offers to take it back.",
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
        ) { Text("Finish setup") }
        Text(
            "Permissions, the shared database and your home zone are all in " +
                "Settings, and Settings will tell you if any of them still need doing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
