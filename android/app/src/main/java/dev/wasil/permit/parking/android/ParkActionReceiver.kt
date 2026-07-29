package dev.wasil.permit.parking.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ParkActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CLAIM = "dev.wasil.permit.CLAIM"
        const val ACTION_CLAIM_FORCE = "dev.wasil.permit.CLAIM_FORCE"
        const val ACTION_GIVE_BACK = "dev.wasil.permit.GIVE_BACK"
        const val ACTION_IGNORE = "dev.wasil.permit.IGNORE"
        const val ACTION_FREE_HERE = "dev.wasil.permit.FREE_HERE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
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
    }
}
