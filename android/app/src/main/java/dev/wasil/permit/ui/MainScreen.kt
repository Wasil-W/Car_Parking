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
import dev.wasil.permit.ui.theme.HandoffShapes
import dev.wasil.permit.ui.theme.LocalHandoffColors

/**
 * What is true right now, here: what this spot demands, and what is covering
 * it. Three screens rather than one, selected on what is actually configured —
 * see [PermitView].
 *
 * With two phones and a permit it is unchanged: one hero card and the single
 * button, because a permit with two possible homes can only ever move to one
 * place. With one phone the button goes, because there is nowhere to send it.
 * With no permit the settlement half goes entirely and the obligation half is
 * the screen — which is the app the v0.6 split was for, and it is not a
 * degraded version of anything.
 */
@Composable
fun MainScreen(
    state: UiState,
    view: PermitView,
    myCar: MyCar?,
    car: GeoPoint?,
    // Passed alongside [car] rather than inferred from it: "no pin" and "not
    // parked" are different states, and this card used to render them as one.
    parked: Boolean,
    me: GeoPoint?,
    demand: SpotDemand,
    /** Geocoded name of the parked spot, or null while it resolves / if it failed. */
    place: String?,
    /** "09:12", or null when nothing recorded a park time. */
    parkedSince: String?,
    onSwitch: (PlateOption) -> Unit,
    onRefresh: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenSettings: () -> Unit,
    onConfirmBlocked: () -> Unit,
    onDismissBlocked: () -> Unit,
) {
    val colors = LocalHandoffColors.current
    val holder = holderFor(state.activeVrn, state.options)

    // Rhythm rather than a uniform gap: the card and its action belong
    // together (12dp), everything else is a separate thought (24dp). Uniform
    // spacing reads as a list of unrelated things.
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HandoffMark(
                    when (view) {
                        PermitView.Shared -> markStateFor(holder)
                        // Never `holder ?: myCar`. Whose phone this is says
                        // nothing about where the permit is, and lighting the
                        // arc on that basis is the mark stating a fact it does
                        // not have.
                        PermitView.Sole -> soleMarkStateFor(holder)
                        // No permit, so nothing holds anything. The mark is a
                        // wordmark here, not a state display.
                        PermitView.NoPermit -> markStateFor(null)
                    },
                    size = 20.dp,
                )
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

        // What this spot demands, above what settles it. Two questions, in the
        // order you actually ask them: is anything owed here, and who is
        // covering it. Keeping them apart is what lets a paying action arrive
        // later without the permit card having to know about it.
        //
        // Dropped entirely with no permit, because there is no second question
        // there and the card below *is* the answer to the first. Both drawn
        // together read as a stutter on a real screen — "Not parked" in the
        // strip and "Not parked" again at 26sp underneath it — and with a rate
        // it is worse, the same "€3,01/h · until 19:00" said twice in two
        // shapes. The mockup drew both; on a device only one of them earns its
        // place.
        if (view != PermitView.NoPermit) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "This spot",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    spotDemandText(demand),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (demand) {
                        // The two states where the permit is taken while
                        // nothing is owed read at full strength too: they are
                        // the ones a person would otherwise scan past as
                        // "free", having just had the permit moved for them.
                        is SpotDemand.Payable, is SpotDemand.FreeForNow,
                        SpotDemand.RateUnknown,
                        -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // Why the permit is on this car when the line above says nothing is
            // owed. Present only in the two states that need explaining, so no
            // screen grows a permanent line that usually says nothing.
            permitHeldNote(demand)?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                )
            }
        }

        when (view) {
            PermitView.NoPermit -> {
                // The obligation half alone. No mark, no plate, no card asking
                // to be completed — this is a finished screen for someone who
                // has no permit and is not going to get one.
                val headline = spotHeadlineFor(demand, place)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = HandoffShapes.Card,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            headline.big,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            headline.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                QuietRow(
                    "Have a permit? Add it in Settings.",
                    onClick = onOpenSettings,
                )
            }

            PermitView.Sole, PermitView.Shared -> {
                val sole = view == PermitView.Sole
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = HandoffShapes.Card,
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
                        } else if (sole) {
                            // One car: naming a brother would be answering a
                            // question nobody asked. What matters is that the
                            // spot is covered.
                            HandoffMark(soleMarkStateFor(holder), size = 60.dp)
                            Text(
                                soleCardTitle(covered = holder != null),
                                style = MaterialTheme.typography.headlineLarge,
                                color = onCard,
                            )
                            state.activeVrn?.let {
                                Text(
                                    soleCardSubtitle(it),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = onCard,
                                )
                            }
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

                if (sole) {
                    // No hand-over button, because there is nowhere to hand it
                    // to. A disabled one would be worse than none: it implies a
                    // second phone exists and is merely unavailable.
                    //
                    // The line is only shown once a holder is actually known.
                    // Seen on screen with the permit site unreachable: the card
                    // said "No plate active" and this row underneath it said
                    // "The permit is on your car" — the screen contradicting
                    // itself, which is worse than the screen being short of an
                    // answer. When we do not know, the card says so alone.
                    if (holder != null && !state.loading) {
                        QuietRow(soleStatusLine(parked, place, parkedSince))
                    }
                } else if (myCar != null && !state.loading) {
                    val action = primaryActionFor(myCar, holder)
                    val target = state.options.firstOrNull { it.car == action.target }
                    Button(
                        onClick = { target?.let(onSwitch) },
                        enabled = target != null && state.switching == null,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = HandoffShapes.Control,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.actionFor(action.target),
                            contentColor = colors.onAction,
                        ),
                    ) {
                        Text(
                            if (state.switching != null) "Switching…" else action.label,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                if (!sole) {
                    state.otherStatus?.let { status -> QuietRow(status) }
                }
            }
        }

        // The car's location without leaving this screen — tap opens the map
        // tab. Takes the remaining height so the screen fills rather than
        // trailing off into empty space.
        // Fills the screen when there is a map to show; shrinks to a single
        // quiet line when there isn't. Reserving half a screen to announce that
        // nothing has happened makes absence the loudest thing on it.
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (car == null) Modifier else Modifier.weight(1f))
                .padding(top = 12.dp, bottom = 16.dp)
                .clickable(onClick = onOpenMap),
            shape = HandoffShapes.Card,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            if (car == null) {
                Text(
                    // The app knowing you are parked and this card announcing
                    // that nothing has happened cannot both be on screen. See
                    // carPositionLine in MapScreen.kt — same rule, same reason.
                    if (parked) "Parked — location unknown" else "No parked location yet",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                Box(Modifier.fillMaxSize().clip(HandoffShapes.Card)) {
                    MapCanvas(car = car, me = me, interactive = false, zoom = 16.0)
                }
            }
        }
    }

    // The blocked dialog belongs to the two-phone case by construction: it only
    // ever appears when the *other* phone is parked outside on this permit — or,
    // since v0.6.5, when it could not tell whether it is. Both wordings come
    // from DecisionPresentation, so this dialog, the notification and the
    // full-screen decision cannot describe one situation three ways.
    state.blocked?.let { blocked ->
        AlertDialog(
            onDismissRequest = onDismissBlocked,
            title = { Text(blockedTitle(blocked.otherLabel, blocked.known)) },
            text = {
                Text(
                    blockedBody(
                        blocked.otherLabel, blocked.parkedAtMs, blocked.heartbeatAtMs, blocked.known,
                    ),
                )
            },
            confirmButton = { TextButton(onClick = onConfirmBlocked) { Text("Claim anyway") } },
            dismissButton = { TextButton(onClick = onDismissBlocked) { Text("Cancel") } },
        )
    }
}

/**
 * The one-line note under the card — the other car's status, "nothing to do",
 * or the invitation to add a permit. One shape for all three, because they sit
 * in the same slot and differ only in what they say.
 */
@Composable
private fun QuietRow(text: String, onClick: (() -> Unit)? = null) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = HandoffShapes.Control,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(13.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
