package dev.wasil.permit.parking.android

import dev.wasil.permit.R
import dev.wasil.permit.parking.MyCar

/**
 * Which small icon a notification should use. Mirrors [dev.wasil.permit.ui.HandoffMark]'s
 * dot placement — against the left arc for Wasil, the right arc for Walid,
 * centred when [holder] is unknown or the notification isn't about who
 * currently holds the permit at all.
 *
 * Notification small icons are tinted and flattened by Android to a single-
 * colour white silhouette, discarding any colour info in the drawable — so
 * arc colour and dimming, which [dev.wasil.permit.ui.HandoffMark] also uses
 * to show the holder, can't survive here. Dot position is the only signal
 * left, which is why [holder] is all this needs.
 */
fun notificationIconFor(holder: MyCar?): Int = when (holder) {
    MyCar.WASIL -> R.drawable.ic_notification_wasil
    MyCar.WALID -> R.drawable.ic_notification_walid
    null -> R.drawable.ic_notification
}
