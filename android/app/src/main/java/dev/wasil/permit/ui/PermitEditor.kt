package dev.wasil.permit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * The permit account, asked for where it belongs: inside Settings, behind a
 * row, once someone has decided they want it.
 *
 * This was the app's front door until v0.6.4 — the first screen, four fields,
 * with the app invisible behind it. Nothing about the form was wrong; its
 * position was. A permit is one way of settling what a spot demands, and a
 * settlement method cannot also be the thing you must supply before the app
 * will tell you what is demanded.
 *
 * The password is never pre-filled, even when one is stored. Editing a plate is
 * a common reason to open this and re-typing a password is a small price for
 * not putting a stored credential back on screen.
 */
@Composable
fun PermitEditor(
    initialUsername: String,
    initialWasilPlate: String,
    initialWalidPlate: String,
    onSave: (String, String, String, String) -> Unit,
) {
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    var wasilPlate by rememberSaveable { mutableStateOf(initialWasilPlate) }
    var walidPlate by rememberSaveable { mutableStateOf(initialWalidPlate) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Stored encrypted on this phone, and sent only to the permit site.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(username, { username = it }, label = { Text("Permit username") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Permit password") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(wasilPlate, { wasilPlate = it }, label = { Text("Wasil's plate") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(walidPlate, { walidPlate = it }, label = { Text("Walid's plate") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { onSave(username, password, wasilPlate, walidPlate) },
            enabled = username.isNotBlank() && password.isNotBlank() &&
                wasilPlate.isNotBlank() && walidPlate.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save permit") }
    }
}
