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
            }
            // Whichever way the user acted, the decision that prompted it is resolved.
            PrefsParkStateStore.from(context).pendingDecision = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        perform(context, intent.action)
    }
}
