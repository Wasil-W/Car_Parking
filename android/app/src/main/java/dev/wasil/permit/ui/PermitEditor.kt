package dev.wasil.permit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
 * **It no longer asks for the plates.** The account lists them, and has done
 * since v0.1 — `activePlate()` read the whole list, took the one holding a
 * session and threw the rest away, while this screen asked the user to type
 * those same plates in. Wasil, on being shown the form: *"instead of making
 * myself put the licences show them to me."*
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
    /**
     * Signs in with these credentials and returns every plate the account
     * covers, or null if it could not ask.
     *
     * Saving the credentials first is unavoidable — `PermitAuthenticator` reads
     * them from the store to obtain a token — so this both saves and fetches.
     * That is why the button says "Sign in and find my cars" rather than
     * "Fetch": signing in is what happens, and the copy should not hide it.
     */
    onFindPlates: suspend (String, String) -> List<String>?,
) {
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    var minePlate by rememberSaveable { mutableStateOf(initialWasilPlate) }
    var otherPlate by rememberSaveable { mutableStateOf(initialWalidPlate) }
    var choice by remember { mutableStateOf<PlateChoice?>(null) }
    var finding by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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

        when (val current = choice) {
            null -> {
                Button(
                    onClick = {
                        scope.launch {
                            finding = true
                            failed = false
                            val found = onFindPlates(username, password)
                            finding = false
                            if (found == null) failed = true else choice = plateChoiceFor(found)
                        }
                    },
                    enabled = !finding && username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (finding) "Signing in…" else "Sign in and find my cars") }

                if (failed) {
                    Text(
                        "Couldn't reach the permit site. Check the username and password, " +
                            "or enter the plates yourself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    // Never a dead end. A failed sign-in must not leave the
                    // permit unenterable — the network is not the user's fault.
                    ManualPlates(minePlate, { minePlate = it }, otherPlate) { otherPlate = it }
                }
            }

            PlateChoice.Manual -> {
                Text(
                    "That account lists no plates. Enter them yourself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ManualPlates(minePlate, { minePlate = it }, otherPlate) { otherPlate = it }
            }

            is PlateChoice.Only -> {
                LaunchedEffect(current) { minePlate = current.plate }
                Text(
                    "One car on this permit: ${current.plate}. Add the other car's plate " +
                        "if you share it with someone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    otherPlate, { otherPlate = it },
                    label = { Text("The other car's plate (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }

            is PlateChoice.Pick -> {
                Text("Which of these is your car?", style = MaterialTheme.typography.bodyMedium)
                current.plates.forEach { plate ->
                    PlateRow(
                        plate = plate,
                        mine = plate == minePlate,
                        theirs = plate == otherPlate,
                        onMine = {
                            minePlate = plate
                            // Exactly two settles the second without a second
                            // tap; more than two has to be said out loud.
                            otherPlate = if (current.plates.size == 2) {
                                current.plates.first { it != plate }
                            } else if (otherPlate == plate) "" else otherPlate
                        },
                        onTheirs = {
                            otherPlate = plate
                            if (minePlate == plate) minePlate = ""
                        },
                    )
                }
            }
        }

        // Null until the answer is unambiguous, so Save stays disabled rather
        // than storing a guess. A wrong plate moves the permit to a car that is
        // not yours, which is the expensive direction to be wrong in.
        val pair = when (val current = choice) {
            is PlateChoice.Pick -> platePairFor(current.plates, minePlate, otherPlate)
            else -> minePlate.takeIf { it.isNotBlank() }?.let { it to otherPlate }
        }
        Button(
            onClick = { onSave(username, password, pair?.first.orEmpty(), pair?.second.orEmpty()) },
            enabled = username.isNotBlank() && password.isNotBlank() && pair != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save permit") }
    }
}

/** The old two-field form, kept for when the account cannot answer. */
@Composable
private fun ManualPlates(
    mine: String,
    onMine: (String) -> Unit,
    theirs: String,
    onTheirs: (String) -> Unit,
) {
    OutlinedTextField(mine, onMine, label = { Text("Your plate") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(theirs, onTheirs, label = { Text("The other car's plate") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
}

/**
 * One plate from the account, with the two things it can be.
 *
 * Both choices sit on every row rather than a single "this is mine" toggle,
 * because with three or more plates the second car has to be stated: the app
 * stores two, and must not guess which of the rest was meant.
 */
@Composable
private fun PlateRow(
    plate: String,
    mine: Boolean,
    theirs: Boolean,
    onMine: () -> Unit,
    onTheirs: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(plate, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        FilterChip(selected = mine, onClick = onMine, label = { Text("Mine") })
        FilterChip(selected = theirs, onClick = onTheirs, label = { Text("Other car") })
    }
}
