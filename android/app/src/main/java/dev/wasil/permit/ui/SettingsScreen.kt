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
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import dev.wasil.permit.data.store.CredentialStore
import dev.wasil.permit.parking.FreeZoneStore
import dev.wasil.permit.parking.ParkStateStore
import dev.wasil.permit.parking.Roster
import dev.wasil.permit.parking.android.SharedSync
import dev.wasil.permit.parking.shared.SharedStateStore
import dev.wasil.permit.ui.theme.LocalHandoffColors
import kotlinx.coroutines.launch

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * One line per setting: label on the left, current value and/or a trailing
 * control on the right, chevron only when the row itself is tappable. This is
 * the only shape a settings item takes on this screen — no titled blocks, no
 * full-width buttons.
 */
@Composable
private fun SettingRow(
    label: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        value?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
        if (onClick != null) {
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 4.dp),
    )
}

/** bodySmall hint under a row, for the rare case where the row's meaning genuinely isn't obvious. */
@Composable
private fun RowHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun SettingsScreen(
    stateStore: ParkStateStore,
    freeZoneStore: FreeZoneStore,
    credentialStore: CredentialStore,
    sharedStore: () -> SharedStateStore,
    onSavePermit: (String, String, String, String) -> Unit,
    /** Signs in and returns the plates the account covers, or null if it could not ask. */
    onFindPlates: suspend (String, String) -> List<String>?,
    onOpenMap: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalHandoffColors.current
    var refresh by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh++ }

    var permit by remember { mutableStateOf(credentialStore.load()) }
    var editingPermit by remember { mutableStateOf(false) }
    var confirmRemovePermit by remember { mutableStateOf(false) }
    var carMac by remember { mutableStateOf(stateStore.carMac) }
    var carName by remember { mutableStateOf(stateStore.carName) }
    // The seed pair when no permit is stored, so "whose phone is this" is still
    // answerable on an install that has declined one.
    val roster = permit?.roster ?: Roster.SEED
    var myVehicle by remember(roster) { mutableStateOf(roster.byId(stateStore.thisPhoneDrives)) }
    var autoClaim by remember { mutableStateOf(stateStore.autoClaim) }
    var zones by remember { mutableStateOf(freeZoneStore.all()) }
    var homeZone by remember { mutableStateOf(stateStore.homeZone) }
    var syncUrl by remember { mutableStateOf(stateStore.syncUrl ?: "") }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var pickingCar by remember { mutableStateOf(false) }

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
    // Deliberately NOT in `needed` above. Bundling ACCESS_BACKGROUND_LOCATION
    // into an ordinary request makes the system deny it without showing
    // anything on API 30+, and it may only be asked for once fine location is
    // already granted — so it is checked here, reported as its own row, and
    // fixed by sending the user to the one screen where "Allow all the time"
    // exists at all.
    val backgroundLocation = remember(revision) {
        when {
            Build.VERSION.SDK_INT < 29 -> BackgroundLocation.NOT_APPLICABLE
            granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
                BackgroundLocation.GRANTED
            else -> BackgroundLocation.MISSING
        }
    }
    val powerManager = context.getSystemService(PowerManager::class.java)
    val ignoringBatteryOpt = remember(revision) {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val rows = healthRows(
        missingPermissions = missingPermissions,
        batteryOptimised = !ignoringBatteryOpt,
        carPaired = carMac != null,
        permitAdded = permit != null,
        syncConfigured = syncUrl.isNotBlank(),
        homeZoneSet = homeZone != null,
        backgroundLocation = backgroundLocation,
    )
    val setupOk = rows.all { it.ok }
    val bluetoothGranted = granted(context, Manifest.permission.BLUETOOTH_CONNECT)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
        )

        // --- Setup summary: the set-once questions (whose phone, sync URL), collapsed. ---
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
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
                        // Never "Setup incomplete" again. That headline was
                        // shown to anyone who had declined sharing or had no
                        // permit — both of which are working configurations of
                        // this app — and it named a fault that did not exist.
                        // Counting the genuine ones instead means the card can
                        // only be as loud as the number of things wrong.
                        setupHeadline(rows),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done" else "Edit") }
                }
                Text(
                    // Guarded: an unset myCar previously interpolated the
                    // literal string "null" via the safe-call chain
                    // ("null's phone"). Currently unreachable because of
                    // branch ordering in MainActivity, but not guaranteed.
                    setupConfigurationLine(
                        phoneLabel = myVehicle?.name,
                        permitAdded = permit != null,
                        syncConfigured = syncUrl.isNotBlank(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (editing) {
                    HorizontalDivider()

                    Text("Whose phone is this?", style = MaterialTheme.typography.bodyLarge)
                    Row {
                        roster.vehicles.forEach { option ->
                            Row(Modifier.clickable {
                                stateStore.thisPhoneDrives = option.id
                                myVehicle = option
                            }.padding(end = 24.dp)) {
                                RadioButton(selected = myVehicle?.id == option.id, onClick = {
                                    stateStore.thisPhoneDrives = option.id
                                    myVehicle = option
                                })
                                Text(option.name, Modifier.padding(top = 12.dp))
                            }
                        }
                    }

                    Text("Sharing with the other phone", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        // Optional, and said so plainly. Leaving this blank is a
                        // single-phone install, which works: the guard proceeds
                        // when there is no other state to read, and nothing on
                        // screen asks about a car that is not there.
                        "Optional. Leave it empty to use Handoff on this phone alone. " +
                            "To share a permit, both phones need the same Firebase " +
                            "database URL — see SETUP_FIREBASE.md.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = syncUrl,
                        onValueChange = { syncUrl = it },
                        label = { Text("https://…firebasedatabase.app") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row {
                        TextButton(onClick = {
                            stateStore.syncUrl = syncUrl.trim().ifBlank { null }
                            syncStatus = "Saved."
                            SharedSync.requestSync(context)
                        }) { Text("Save") }
                        TextButton(onClick = {
                            syncStatus = "Testing…"
                            scope.launch {
                                syncStatus = runCatching { sharedStore().heartbeat() }
                                    .fold({ "Connection OK." }, { "Could not connect: ${it.message}" })
                            }
                        }) { Text("Test connection") }
                    }
                    syncStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        // --- Permit: one way of settling what a spot demands, and not the
        // app's front door any more. This row is where the four-field form
        // that used to block first run now lives. ---
        SectionHeader("Permit")

        SettingRow(
            label = if (permit == null) "Add a permit" else "Permit account",
            value = permit?.username,
            onClick = { editingPermit = !editingPermit },
        )
        if (permit == null && !editingPermit) {
            RowHint(
                "Handoff works without one — it still says what a spot costs and " +
                    "when it goes free.",
            )
        }
        if (editingPermit) {
            PermitEditor(
                initialUsername = permit?.username.orEmpty(),
                // "Mine" and "theirs" relative to the car this phone drives,
                // rather than to a fixed slot. Storing them by slot is what let
                // "your plate" land in Wasil's slot on Walid's phone.
                initialMyPlate = myVehicle?.plate.orEmpty(),
                initialTheirPlate = roster.other(myVehicle?.id)?.plate.orEmpty(),
                onSave = { u, p, a, b ->
                    onSavePermit(u, p, a, b)
                    permit = credentialStore.load()
                    myVehicle = permit?.roster?.byId(stateStore.thisPhoneDrives)
                    editingPermit = false
                },
                onFindPlates = onFindPlates,
            )
        } else if (permit != null) {
            roster.vehicles.forEach { car ->
                SettingRow(label = "${car.name}'s plate", value = car.plate)
            }
            SettingRow(label = "Remove permit", onClick = { confirmRemovePermit = true })
        }

        // Deliberately narrow, and the dialog says so. "Log out" would be the
        // wrong word twice over: there is no account to leave — the credentials
        // live only on this phone — and it implies the rest goes with them.
        // Only the username and password are cleared; the sync link, the zones,
        // the history and the parked state are separate things a person set up
        // separately and would have to set up again for no reason.
        if (confirmRemovePermit) {
            AlertDialog(
                onDismissRequest = { confirmRemovePermit = false },
                title = { Text("Remove permit?") },
                text = {
                    Text(
                        "The username and password are deleted from this phone. " +
                            "Your home zone, free zones, history and the link to the " +
                            "other phone all stay. " +
                            "If the permit is on your car right now it stays there — " +
                            "removing it here does not hand it back.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        credentialStore.clear()
                        permit = null
                        editingPermit = false
                        confirmRemovePermit = false
                        refresh++
                    }) { Text("Remove") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRemovePermit = false }) { Text("Keep it") }
                },
            )
        }

        // --- Detection: options you might occasionally change. ---
        SectionHeader("Detection")

        SettingRow(
            label = "Auto-claim permit on park",
            trailing = {
                // Material's default switch fills its track with `primary`,
                // which is near-white in our neutralised scheme — the brightest
                // object on the screen, for a setting touched about once. On
                // and off stay obvious through track lightness and thumb
                // contrast rather than through glare.
                Switch(
                    checked = autoClaim,
                    onCheckedChange = {
                        stateStore.autoClaim = it
                        autoClaim = it
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.surface,
                        checkedTrackColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        checkedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
            },
        )

        SettingRow(
            label = "Car Bluetooth device",
            value = carName ?: "Not set",
            onClick = if (bluetoothGranted) {
                { pickingCar = !pickingCar }
            } else null,
            trailing = if (!bluetoothGranted) {
                {
                    TextButton(onClick = {
                        permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }) { Text("Grant") }
                }
            } else null,
        )
        if (pickingCar && bluetoothGranted) {
            val bonded = remember(refresh) {
                runCatching {
                    context.getSystemService(BluetoothManager::class.java)
                        ?.adapter?.bondedDevices?.toList().orEmpty()
                }.getOrDefault(emptyList())
            }
            if (bonded.isEmpty()) {
                RowHint("No paired Bluetooth devices found.")
            }
            bonded.forEach { device ->
                val name = runCatching { device.name }.getOrNull() ?: "Unknown"
                SettingRow(
                    label = name,
                    onClick = {
                        stateStore.carMac = device.address
                        stateStore.carName = name
                        carMac = device.address
                        carName = name
                        pickingCar = false
                    },
                    trailing = if (device.address == carMac) {
                        { Icon(Icons.Filled.Check, contentDescription = null, tint = colors.fine) }
                    } else null,
                )
            }
        }

        // --- Zones: geographic things, so they live on the map now. These
        // rows are summaries only — tapping either one opens the map tab,
        // where the zone is an actual circle you can place, move or remove. ---
        SectionHeader("Zones")

        SettingRow(
            label = "Home zone",
            // The address it was named with (or renamed to on the map) — not
            // the radius, and never raw coordinates: an address is the thing
            // Wasil actually recognises at a glance.
            value = homeZone?.label?.takeIf { it.isNotBlank() } ?: "Not set",
            onClick = onOpenMap,
        )
        SettingRow(
            label = "Free zones",
            value = if (zones.isEmpty()) "None" else "${zones.size}",
            onClick = onOpenMap,
        )

        // --- System: permission grants, battery optimisation, and what first-run
        // could skip — quiet when fine. `rows` is computed above, alongside the
        // Setup summary card, so both read the same facts. ---
        SectionHeader("System")

        // The only screen where "Allow all the time" exists. Bumping `refresh`
        // on the way out is what makes the row go green when you come back
        // without having to restart the app.
        val openAppSettings: () -> Unit = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
            refresh++
        }

        rows.forEach { row ->
            SettingRow(
                label = row.label,
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            if (row.ok) Icons.Filled.Check else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = if (row.ok) colors.fine else colors.alert,
                        )
                        row.fix?.let { fix ->
                            TextButton(onClick = {
                                when (fix) {
                                    FixAction.GrantPermission ->
                                        needed.firstOrNull { !granted(context, it) }
                                            ?.let { permissionLauncher.launch(it) }
                                    FixAction.BatterySettings -> {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                Uri.parse("package:${context.packageName}"),
                                            ),
                                        )
                                        refresh++
                                    }
                                    FixAction.AppSettings -> openAppSettings()
                                }
                            }) { Text(row.fixLabel ?: "Fix") }
                        }
                    }
                },
            )
            row.hint?.let { RowHint(it) }
        }
        SettingRow(label = "Open app settings", onClick = openAppSettings)
    }
}
