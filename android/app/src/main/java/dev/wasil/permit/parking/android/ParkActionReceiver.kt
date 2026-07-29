package dev.wasil.permit.parking.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.PrefsFreeZoneStore
import dev.wasil.permit.parking.PrefsParkStateStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            ACTION_IGNORE -> ParkNotifications.dismissEvents(context)
            ACTION_FREE_HERE -> {
                val store = PrefsParkStateStore.from(context)
                val location = store.lastParkLocation
                if (location != null) {
                    val date = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
                    PrefsFreeZoneStore(
                        context.getSharedPreferences("park_state", Context.MODE_PRIVATE),
                    ).add(FreeZone(location.lat, location.lng, 60.0, "Marked $date"))
                }
                ParkNotifications.dismissEvents(context)
            }
        }
    }
}
