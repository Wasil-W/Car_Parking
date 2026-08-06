package dev.wasil.permit.ui

import dev.wasil.permit.parking.PendingDecision
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a decision screen button does. Kept as a small neutral enum rather than
 * importing ParkActionReceiver's action Strings directly, so this file stays
 * plain Kotlin — the caller maps a kind to the actual action name when it
 * wires the button to ParkActionReceiver.perform(...), the same call the
 * notification's own action buttons make.
 */
enum class DecisionActionKind { CLAIM, CLAIM_FORCE, GIVE_BACK, IGNORE, FREE_HERE }

data class DecisionChoice(val label: String, val kind: DecisionActionKind)

data class DecisionContent(val title: String, val body: String, val choices: List<DecisionChoice>)

private fun hm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

/**
 * The three surfaces that report a held-back claim — the notification, the
 * tappable-decision screen and the in-app dialog — said the same thing in three
 * hand-written registers. They now say it from here, so a wording that has to
 * change can only change once.
 *
 * All three branch on [known], and that is the point of the pair. With a known
 * park the app has read a finding: their car *is* on a paid street and the
 * permit *is* on it, so it may say so. With an unknown one it has read a gap —
 * the other phone parked and never resolved a position — and the only true
 * sentence is that nobody can tell. Saying the first when only the second holds
 * would be exactly the guess-as-fact this app refuses to publish, just pointed
 * at the user instead of at the wire.
 */
fun blockedTitle(otherLabel: String, known: Boolean): String =
    if (known) "$otherLabel's car is parked — permit not claimed"
    else "$otherLabel's car may be parked — permit not claimed"

/** The one-line version, for a notification that gets collapsed to it. */
fun blockedNotificationText(
    otherLabel: String,
    parkedAtMs: Long,
    heartbeatAtMs: Long,
    known: Boolean,
): String = if (known) {
    "$otherLabel parked at ${hm(parkedAtMs)} (last seen ${hm(heartbeatAtMs)}). " +
        "Claiming would leave their car unpermitted."
} else {
    "$otherLabel's phone couldn't tell where it parked (last seen ${hm(heartbeatAtMs)}). " +
        "The permit is on their car."
}

/** The full version, for a screen with room for the whole reason. */
fun blockedBody(
    otherLabel: String,
    parkedAtMs: Long,
    heartbeatAtMs: Long,
    known: Boolean,
): String = if (known) {
    "$otherLabel parked at ${hm(parkedAtMs)} and was last heard from at ${hm(heartbeatAtMs)}. " +
        "Claiming now would leave their car unpermitted — that's a fine if it's still there."
} else {
    "$otherLabel's phone parked at ${hm(parkedAtMs)} without working out where, so whether " +
        "their car is on a paid street is unknown (last heard from at ${hm(heartbeatAtMs)}). " +
        "The permit is on their car, and claiming it may leave them unpermitted."
}

/**
 * The full-screen version of whichever decision a notification raised: a
 * clear title, the facts in prose (the cramped notification can't fit them),
 * and the same choices the notification's action buttons offer. BLOCKED gets
 * one choice more than its notification can hold — the full screen has the
 * room "mark this spot free" never had.
 */
fun contentFor(decision: PendingDecision): DecisionContent = when (decision) {
    is PendingDecision.Blocked -> DecisionContent(
        title = blockedTitle(decision.otherLabel, decision.known),
        body = blockedBody(
            decision.otherLabel, decision.parkedAtMs, decision.heartbeatAtMs, decision.known,
        ),
        choices = listOf(
            DecisionChoice("Claim anyway", DecisionActionKind.CLAIM_FORCE),
            DecisionChoice("Mark this spot free", DecisionActionKind.FREE_HERE),
            DecisionChoice("Leave it", DecisionActionKind.IGNORE),
        ),
    )
    is PendingDecision.Manual -> DecisionContent(
        title = "Parked — decide about the permit",
        body = "A possible park was detected at ${hm(decision.raisedAtMs)}. Choose what to do with the permit.",
        choices = listOf(
            DecisionChoice("Claim permit", DecisionActionKind.CLAIM),
            DecisionChoice("Mark this spot free", DecisionActionKind.FREE_HERE),
            DecisionChoice("Ignore", DecisionActionKind.IGNORE),
        ),
    )
    is PendingDecision.GiveBack -> DecisionContent(
        title = "Give the permit back to ${decision.otherLabel}?",
        body = "You parked free. ${decision.otherLabel}'s car is parked outside and the permit is still on yours.",
        choices = listOf(
            DecisionChoice("Give back", DecisionActionKind.GIVE_BACK),
            DecisionChoice("Keep it", DecisionActionKind.IGNORE),
        ),
    )
    is PendingDecision.Takeover -> DecisionContent(
        title = "${decision.byLabel} took the permit",
        body = "Your car is parked without a permit. Move it or reclaim.",
        choices = listOf(
            DecisionChoice("Reclaim", DecisionActionKind.CLAIM),
            DecisionChoice("OK", DecisionActionKind.IGNORE),
        ),
    )
}
