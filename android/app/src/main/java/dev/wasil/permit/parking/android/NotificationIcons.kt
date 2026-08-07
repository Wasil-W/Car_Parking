package dev.wasil.permit.parking.android

import dev.wasil.permit.R

/**
 * Which small icon a notification should use. Mirrors [dev.wasil.permit.ui.HandoffMark]'s
 * dot placement — against the left arc for roster slot 0, the right arc for
 * slot 1, centred when [identitySlot] is null.
 *
 * Notification small icons are tinted and flattened by Android to a single-
 * colour white silhouette, discarding any colour info in the drawable — so
 * arc colour and dimming, which [dev.wasil.permit.ui.HandoffMark] also uses
 * to show the holder, can't survive here. Dot position is the only signal
 * left, which is why a slot is all this needs.
 *
 * Null covers three cases and they collapse honestly into one drawing: nobody
 * holds the permit, the holder is not known, or there are more than two cars
 * and so no identity hue or side to point at. In all three the mark is a
 * wordmark rather than a state display, and a centred dot is what that looks
 * like.
 */
fun notificationIconFor(identitySlot: Int?): Int = when (identitySlot) {
    0 -> R.drawable.ic_notification_wasil
    1 -> R.drawable.ic_notification_walid
    else -> R.drawable.ic_notification
}
