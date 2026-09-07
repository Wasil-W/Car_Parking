package dev.wasil.permit.parking.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.wasil.permit.parking.PrefsParkStateStore

class ParkActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CLAIM = "dev.wasil.permit.CLAIM"
        const val ACTION_CLAIM_FORCE = "dev.wasil.permit.CLAIM_FORCE"
        const val ACTION_GIVE_BACK = "dev.wasil.permit.GIVE_BACK"
        const val ACTION_IGNORE = "dev.wasil.permit.IGNORE"
        const val ACTION_FREE_HERE = "dev.wasil.permit.FREE_HERE"

        /**
         * The reminder's own two buttons, and they exist because sharing the
         * permit ones was a bug.
         *
         * v0.8.0 first wired the reminder to [ACTION_CLAIM] and [ACTION_IGNORE].
         * Both are handled by [perform], which cancels the *event* notification
         * and clears [ParkStateStore.pendingDecision] — so "stop telling me
         * about the clock" also silently threw away an unanswered permit
         * question and the only route back to its decision screen. The two
         * notifications are about different things and must not share a verb.
         *
         * [ACTION_REMINDER_CLAIM] still claims, because that is the one action
         * that can settle the spot — it just does not also resolve a decision
         * nobody answered.
         */
        const val ACTION_REMINDER_CLAIM = "dev.wasil.permit.REMINDER_CLAIM"
        const val ACTION_REMINDER_IGNORE = "dev.wasil.permit.REMINDER_IGNORE"

        /**
         * The single implementation of every notification action, so the
         * notification's action buttons (via [onReceive], a broadcast) and the
         * tappable-notification decision screen (a direct call from
         * MainActivity) end up running identical code rather than two paths
         * that could drift apart.
         */
        fun perform(context: Context, action: String?) {
            when (action) {
                ACTION_CLAIM -> ParkWorkers.enqueueClaim(context)
                ACTION_CLAIM_FORCE -> ParkWorkers.enqueueClaim(context, force = true)
                ACTION_GIVE_BACK -> {
                    SharedSync.requestGiveBack(context, executeNow = true)
                    ParkNotifications.dismissEvents(context)
                }
                ACTION_IGNORE -> ParkNotifications.dismissEvents(context)
                ACTION_FREE_HERE -> {
                    SharedSync.requestFreeHere(context)
                    ParkNotifications.dismissEvents(context)
                }
                // The reminder's own actions touch only the reminder. They
                // return early: everything below this `when` is about resolving
                // a pending permit decision, and a reminder never raises one.
                ACTION_REMINDER_CLAIM -> {
                    ParkWorkers.enqueueClaim(context)
                    ParkNotifications.dismissReminder(context)
                    return
                }
                ACTION_REMINDER_IGNORE -> {
                    ParkNotifications.dismissReminder(context)
                    return
                }
            }
            // Whichever way the user acted, the decision that prompted it is resolved.
            PrefsParkStateStore.from(context).pendingDecision = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        perform(context, intent.action)
    }
}
