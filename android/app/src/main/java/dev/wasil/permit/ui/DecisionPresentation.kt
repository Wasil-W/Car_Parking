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
fun contentFor(
    decision: PendingDecision,
    /**
     * Whether this park has a position at all.
     *
     * Read from the store by the caller rather than carried on
     * [PendingDecision.Manual], which is persisted as flat primitives and would
     * need a migration for one boolean — and this is a fact about *now* anyway:
     * the ask can be opened up to 12 hours later, by which time the pin may have
     * been set by hand on the Map.
     */
    positionKnown: Boolean = true,
    /**
     * Whether the missing position is explained by a permission the user can
     * grant. Keeps the prompt from advising a fix that would not fix anything —
     * a failed fix instruction is worse than none.
     */
    fixablePermission: Boolean = false,
): DecisionContent = when (decision) {
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
    // Two different situations wearing one prompt, and until now one sentence.
    //
    // "A possible park was detected. Choose what to do with the permit" says
    // nothing about *why* it cannot decide, so the same unanswerable question
    // arrives after every park and the only way out is to answer it by hand
    // every time — Wasil, 2026-09-03: "the message pop up far more often than
    // before... i have to do it manually now every time."
    //
    // When the position is known this is a real question (auto-claim is off, or
    // the permit holder could not be read) and the old wording is fine. When it
    // is not known, the app is asking about a spot it cannot see, and saying so
    // is the difference between a decision and a riddle.
    is PendingDecision.Manual -> DecisionContent(
        title = if (positionKnown) "Parked — decide about the permit" else "Parked — but where?",
        body = if (positionKnown) {
            "A possible park was detected at ${hm(decision.raisedAtMs)}. " +
                "Choose what to do with the permit."
        } else {
            "The car parked at ${hm(decision.raisedAtMs)}, but the phone could not work out " +
                "where. Without a position the app cannot tell whether this spot is paid, so " +
                "it has to ask." +
                if (fixablePermission) {
                    " This usually means location is set to “Allow only while using the app” — " +
                        "Handoff reads your position in the background, so it needs “Allow all " +
                        "the time”. Settings has a row that fixes it."
                } else {
                    " You can set the car's position on the Map."
                }
        },
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
