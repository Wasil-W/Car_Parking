package dev.wasil.permit.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.wasil.permit.parking.FreeZoneStore
import dev.wasil.permit.parking.MyCar
import dev.wasil.permit.parking.ParkStateStore

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun SettingsScreen(
    stateStore: ParkStateStore,
    freeZoneStore: FreeZoneStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh++ }

    var carMac by remember { mutableStateOf(stateStore.carMac) }
    var carName by remember { mutableStateOf(stateStore.carName) }
    var myCar by remember { mutableStateOf(stateStore.myCar) }
    var autoClaim by remember { mutableStateOf(stateStore.autoClaim) }
    var zones by remember { mutableStateOf(freeZoneStore.all()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Text("Car Bluetooth device", style = MaterialTheme.typography.titleMedium)
        Text(
            carName?.let { "Selected: $it ($carMac)" } ?: "No car selected yet",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (granted(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            val bonded = remember(refresh) {
                runCatching {
                    context.getSystemService(BluetoothManager::class.java)
                        ?.adapter?.bondedDevices?.toList().orEmpty()
                }.getOrDefault(emptyList())
            }
            if (bonded.isEmpty()) Text("No paired Bluetooth devices found.")
            bonded.forEach { device ->
                val name = runCatching { device.name }.getOrNull() ?: "Unknown"
                Text(
                    "• $name",
                    modifier = Modifier.fillMaxWidth().clickable {
                        stateStore.carMac = device.address
                        stateStore.carName = name
                        carMac = device.address
                        carName = name
                    }.padding(vertical = 6.dp),
                )
            }
        } else {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                Text("Grant Bluetooth permission to pick your car")
            }
        }
        HorizontalDivider()

        Text("Whose phone is this?", style = MaterialTheme.typography.titleMedium)
        Row {
            MyCar.entries.forEach { option ->
                Row(Modifier.clickable {
                    stateStore.myCar = option
                    myCar = option
                }.padding(end = 24.dp)) {
                    RadioButton(selected = myCar == option, onClick = {
                        stateStore.myCar = option
                        myCar = option
                    })
                    Text(option.name.lowercase().replaceFirstChar { it.uppercase() },
                        Modifier.padding(top = 12.dp))
                }
            }
        }
        HorizontalDivider()

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Auto-claim permit on park", Modifier.padding(top = 12.dp))
            Switch(checked = autoClaim, onCheckedChange = {
                stateStore.autoClaim = it
                autoClaim = it
            })
        }
        HorizontalDivider()

        Text("Free zones", style = MaterialTheme.typography.titleMedium)
        if (zones.isEmpty()) Text("None marked. Use \"Free here\" on a parking notification.")
        zones.forEachIndexed { index, zone ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(zone.label.ifBlank { "%.5f, %.5f".format(zone.lat, zone.lng) },
                    Modifier.padding(top = 12.dp))
                TextButton(onClick = {
                    freeZoneStore.removeAt(index)
                    zones = freeZoneStore.all()
                }) { Text("Delete") }
            }
        }
        HorizontalDivider()

        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        // Reading `refresh` here makes permission grants recompose this section.
        val revision = refresh
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        needed.forEach { permission ->
            val ok = remember(revision, permission) { granted(context, permission) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text((if (ok) "OK " else "Missing ") + permission.substringAfterLast('.'),
                    Modifier.padding(top = 12.dp))
                if (!ok) TextButton(onClick = { permissionLauncher.launch(permission) }) {
                    Text("Grant")
                }
            }
        }
        Text(
            "Background location must be set to \"Allow all the time\" in system settings " +
                "for detection to work with the screen off.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        }) { Text("Open app settings") }

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}
