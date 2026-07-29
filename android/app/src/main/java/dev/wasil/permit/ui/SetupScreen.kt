package dev.wasil.permit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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

@Composable
fun SetupScreen(onSave: (String, String, String, String) -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var wasilPlate by rememberSaveable { mutableStateOf("") }
    var walidPlate by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Your permit account", style = MaterialTheme.typography.headlineSmall)
        Text("Stored encrypted on this phone. Asked once.")
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
        ) { Text("Continue") }
    }
}
