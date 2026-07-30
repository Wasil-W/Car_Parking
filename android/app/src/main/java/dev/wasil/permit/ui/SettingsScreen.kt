package dev.wasil.permit.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.FreeZoneStore
import dev.wasil.permit.parking.MyCar
import dev.wasil.permit.parking.ParkStateStore
import dev.wasil.permit.parking.label
import dev.wasil.permit.parking.android.PlayServicesSignals
import dev.wasil.permit.parking.android.SharedSync
import dev.wasil.permit.parking.shared.SharedStateStore
import dev.wasil.permit.ui.theme.LocalHandoffColors
import kotlinx.coroutines.launch

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun SettingsScreen(
    stateStore: ParkStateStore,
    freeZoneStore: FreeZoneStore,
    sharedStore: () -> SharedStateStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalHandoffColors.current
    var refresh by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh++ }

    var carMac by remember { mutableStateOf(stateStore.carMac) }
    var carName by remember { mutableStateOf(stateStore.carName) }
    var myCar by remember { mutableStateOf(stateStore.myCar) }
    var autoClaim by remember { mutableStateOf(stateStore.autoClaim) }
    var zones by remember { mutableStateOf(freeZoneStore.all()) }
    var homeZone by remember { mutableStateOf(stateStore.homeZone) }
    var homeStatus by remember { mutableStateOf<String?>(null) }
    var syncUrl by remember { mutableStateOf(stateStore.syncUrl ?: "") }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }

    // Reading `refresh` here makes permission/battery grants recompose this.
    // Computed up front (not just inside the System section further down) so
    // the Setup summary card above it can derive a real "is everything done"
    // tick from the same facts, instead of a hard-coded "Setup complete".
    val revision = refresh
    val needed = buildList {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val missingPermissions = remember(revision) { needed.count { !granted(context, it) } }
    val powerManager = context.getSystemService(PowerManager::class.java)
    val ignoringBatteryOpt = remember(revision) {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val rows = healthRows(
        missingPermissions = missingPermissions,
        batteryOptimised = !ignoringBatteryOpt,
        carPaired = carMac != null,
        syncConfigured = syncUrl.isNotBlank(),
        homeZoneSet = homeZone != null,
    )
    val setupOk = rows.all { it.ok }

    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        // --- Setup summary: the set-once questions (whose phone, sync URL), collapsed. ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (setupOk) Icons.Filled.Check else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = if (setupOk) colors.fine else colors.alert,
                    )
                    Text(
                        if (setupOk) "Setup complete" else "Setup incomplete",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    // Guarded: an unset myCar previously interpolated the
                    // literal string "null" via the safe-call chain
                    // ("null's phone"). Currently unreachable because of
                    // branch ordering in MainActivity, but not guaranteed.
                    myCar?.let { "${it.label()}'s phone" } ?: "Whose phone isn't set yet",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    (if (syncUrl.isNotBlank()) "Sync configured" else "Sync not set up") +
                        " · " + (if (carMac != null) "car paired" else "no car paired"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (editing) {
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

                    Text("Shared state (Firebase)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Database URL from SETUP_FIREBASE.md. Both phones must use the same URL.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = syncUrl,
                        onValueChange = { syncUrl = it },
                        label = { Text("https://…firebasedatabase.app") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row {
                        Button(onClick = {
                            stateStore.syncUrl = syncUrl.trim().ifBlank { null }
                            syncStatus = "Saved."
                            SharedSync.requestSync(context)
                        }) { Text("Save") }
                        TextButton(onClick = {
                            syncStatus = "Testing…"
                            scope.launch {
                                syncStatus = runCatching { sharedStore().heartbeat() }
                                    .fold({ "Connection OK." }, { "FAILED: ${it.message}" })
                            }
                        }) { Text("Test connection") }
                    }
                    syncStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }

                TextButton(
                    onClick = { editing = !editing },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(if (editing) "Done" else "Edit") }
            }
        }

        // --- Detection: options you might occasionally change. ---
        Text("Detection", style = MaterialTheme.typography.labelSmall)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Auto-claim permit on park", Modifier.padding(top = 12.dp))
            Switch(checked = autoClaim, onCheckedChange = {
                stateStore.autoClaim = it
                autoClaim = it
            })
        }

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

        // --- Zones ---
        Text("Zones", style = MaterialTheme.typography.labelSmall)

        Text("Home zone", style = MaterialTheme.typography.titleMedium)
        Text(
            homeZone?.let { "Home set: %.5f, %.5f (radius %.0f m)".format(it.lat, it.lng, it.radiusM) }
                ?: "Not set. Parking inside your home zone never claims the permit.",
            style = MaterialTheme.typography.bodyMedium,
        )
        homeZone?.let { zone ->
            Text("Radius: %.0f m".format(zone.radiusM))
            Slider(
                value = zone.radiusM.toFloat(),
                onValueChange = {
                    val updated = zone.copy(radiusM = it.toDouble())
                    stateStore.homeZone = updated
                    homeZone = updated
                },
                valueRange = 30f..200f,
            )
        }
        Button(onClick = {
            homeStatus = "Getting location…"
            scope.launch {
                val point = PlayServicesSignals(context).currentLocation()
                if (point == null) {
                    homeStatus = "Couldn't get a location — check location permission and try outdoors."
                } else {
                    val zone = FreeZone(point.lat, point.lng, homeZone?.radiusM ?: 60.0, "Home")
                    stateStore.homeZone = zone
                    homeZone = zone
                    homeStatus = "Home saved."
                }
            }
        }) { Text(if (homeZone == null) "Set home to current location" else "Move home here") }
        if (homeZone != null) {
            TextButton(onClick = {
                stateStore.homeZone = null
                homeZone = null
                homeStatus = null
            }) { Text("Clear home zone") }
        }
        homeStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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

        // --- System: permission grants, battery optimisation, and what first-run
        // could skip — quiet when fine. `rows` is computed above, alongside the
        // Setup summary card, so both read the same facts. ---
        Text("System", style = MaterialTheme.typography.labelSmall)

        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (row.ok) Icons.Filled.Check else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = if (row.ok) colors.fine else colors.alert,
                    )
                    Text(row.label, Modifier.padding(top = 12.dp))
                }
                row.fixLabel?.let { fix ->
                    TextButton(onClick = {
                        when (fix) {
                            "Grant" -> needed.firstOrNull { !granted(context, it) }
                                ?.let { permissionLauncher.launch(it) }
                            else -> {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                                refresh++
                            }
                        }
                    }) { Text(fix) }
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
        HorizontalDivider()

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}
