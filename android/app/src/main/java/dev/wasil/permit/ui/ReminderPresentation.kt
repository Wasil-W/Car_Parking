package dev.wasil.permit.ui

import dev.wasil.permit.parking.zones.Reminder

/**
 * The words a parking reminder arrives in.
 *
 * Separate from the notification for the same reason [blockedTitle] and
 * [blockedNotificationText] are: copy is a decision, and a decision that lives
 * inside a `NotificationCompat.Builder` cannot be tested without a device. The
 * builder assembles; this file decides what it says.
 */

/**
 * "You start paying here at 09:00" — a wall-clock time, never a countdown.
 *
 * A countdown is a number you have to add to a clock you cannot see while
 * walking, and it starts rotting the moment it is written: a notification that
 * says "in 30 minutes" and then sits unread for ten is simply wrong, where
 * "at 09:00" is still exactly as true. [clockAhead] adds the day prefix by
 * itself when the boundary falls on the other side of midnight, which is what
 * the overnight areas need.
 */
fun reminderTitle(reminder: Reminder, dayOfWeek: Int, minuteOfDay: Int): String {
    val at = clockAhead(dayOfWeek, minuteOfDay, reminder.inMin)
    return when (reminder) {
        is Reminder.StartsOwing -> "You start paying here at $at"
        is Reminder.BecomesFree -> "Free here from $at"
    }
}

/**
 * The rate and the place, in that order, or null when there is nothing to add.
 *
 * **No holder name**, deliberately, and this is where the shipped notification
 * departs from the mockup. Naming the other car would make the sentence read
 * better — "Walid's car has the permit" — but the app cannot honestly assert it
 * from a worker: the only local record of who holds the permit is marked
 * presentation-only precisely because it goes stale, and a stale name here would
 * be a guess published as a fact about the one subject this project has a rule
 * about. The Claim button asks the live question instead, and answers it
 * truthfully whichever way it goes.
 *
 * Null for a [Reminder.BecomesFree] with no place name, because the title has
 * already said the whole thing and a second line repeating it is worse than no
 * second line.
 */
fun reminderBody(reminder: Reminder, place: String?): String? = when (reminder) {
    is Reminder.StartsOwing -> listOfNotNull(
        reminder.rateText.takeIf { it.isNotBlank() },
        place,
    ).joinToString(" · ")
        // Neither a rate nor a place: an area whose next span the schedule
        // could not price, in a spot the geocoder could not name. Still worth
        // sending — the title carries the time, which is the actionable half —
        // so this says the one other thing that is certainly true.
        .ifBlank { "Nothing is covering this spot yet." }
    is Reminder.BecomesFree -> place
}
