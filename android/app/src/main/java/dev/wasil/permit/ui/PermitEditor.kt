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
    /** This phone's own car's plate — not a fixed slot's. */
    initialMyPlate: String,
    initialTheirPlate: String,
    /** `(username, password, myPlate, theirPlate)`. */
    onSave: (String, String, String, String) -> Unit,
    /**
     * Signs in with these credentials and says what came back — the cars, or
     * which of the three ways it did not work.
     *
     * Saving the credentials first is unavoidable — `PermitAuthenticator` reads
     * them from the store to obtain a token — so this both saves and fetches.
     * That is why the button says "Sign in and find my cars" rather than
     * "Fetch": signing in is what happens, and the copy should not hide it.
     *
     * It returns a [SignIn] rather than a nullable list because until v0.6.8 the
     * screen could not tell a wrong password from a site that was down, and said
     * the second for both. It could not have told the difference anyway: the
     * request went out on the previous session's token, so a wrong password
     * produced somebody's real cars. Both halves are fixed in `MainViewModel`.
     */
    onFindPlates: suspend (String, String) -> SignIn,
) {
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    var minePlate by rememberSaveable { mutableStateOf(initialMyPlate) }
    var otherPlate by rememberSaveable { mutableStateOf(initialTheirPlate) }
    var choice by remember { mutableStateOf<PlateChoice?>(null) }
    var finding by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
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
                            problem = null
                            val result = onFindPlates(username, password)
                            finding = false
                            problem = signInProblemText(result)
                            // Only a real list opens the picker. "No cars" keeps
                            // the button on screen with its explanation, because
                            // the sign-in worked and re-trying it is pointless —
                            // the plates below are the way forward.
                            if (result is SignIn.Cars) choice = plateChoiceFor(result.plates)
                        }
                    },
                    enabled = !finding && username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (finding) "Signing in…" else "Sign in and find my cars") }

                problem?.let { text ->
                    Text(
                        text,
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
                Text(
                    "Which car does this phone drive?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "The second one is the car this phone can hand the permit to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    // Same two roles as the chips above, in field form — see [PlateRow] for why
    // neither of them names a person.
    OutlinedTextField(mine, onMine, label = { Text("Plate of the car this phone drives") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(theirs, onTheirs, label = { Text("Plate of the car to hand the permit to") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
}

/**
 * One plate from the account, with the two things it can be.
 *
 * Both choices sit on every row rather than a single "this is mine" toggle,
 * because with three or more plates the second car has to be stated: the app
 * stores two, and must not guess which of the rest was meant.
 *
 * **The labels.** They were "Mine" and "Other car" until v0.6.8, and Wasil was
 * right that both are poor. "Mine" asks a question about a *person* — and the
 * app is not asking one. Whose car it is has never been what this field decides;
 * it decides which car **this phone travels in**, which is why the answer is
 * stored device-locally in
 * [dev.wasil.permit.parking.ParkStateStore.thisPhoneDrives] and never shared.
 * And "Other car" is defined by exclusion, which means it changes meaning
 * depending on which handset you are holding — the exact framing Wasil wants to
 * leave: *"i want to eventually switch away from my brother and i."*
 *
 * [PLATE_ROLE_THIS_PHONE] and [PLATE_ROLE_HAND_TO] name what each car does
 * instead. "This phone" is a fact about the device in your hand, true on both
 * handsets, with no person in it; "Hand to" is the one thing the second car is
 * for — it is the destination of the button on the main screen, in the same
 * words that button already uses. Neither implies ownership, and neither has to
 * change when a third car or a non-brother arrives.
 *
 * Rejected on the way, since the reasoning is the deliverable: "Wasil"/"Walid"
 * (the framing being retired), "Car 1"/"Car 2" (numbers nobody assigned, and the
 * app orders by plate so they would disagree with the roster), and "Driver"/
 * "Passenger" (invents a fact about who is sitting where).
 */
internal const val PLATE_ROLE_THIS_PHONE = "This phone"
internal const val PLATE_ROLE_HAND_TO = "Hand to"

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
        FilterChip(selected = mine, onClick = onMine, label = { Text(PLATE_ROLE_THIS_PHONE) })
        FilterChip(selected = theirs, onClick = onTheirs, label = { Text(PLATE_ROLE_HAND_TO) })
    }
}
