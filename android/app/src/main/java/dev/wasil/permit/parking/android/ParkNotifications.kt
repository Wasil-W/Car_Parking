package dev.wasil.permit.parking.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.wasil.permit.parking.ParkNotifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ParkNotifications(private val context: Context) : ParkNotifier {

    companion object {
        const val CHANNEL_STATUS = "permit_status"
        const val CHANNEL_EVENTS = "park_events"
        const val STATUS_ID = 1
        const val EVENT_ID = 2

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "Permit status", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_EVENTS, "Parking events", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        fun dismissEvents(context: Context) {
            NotificationManagerCompat.from(context).cancel(EVENT_ID)
        }
    }

    private fun now(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun action(actionName: String, label: String): NotificationCompat.Action {
        val intent = Intent(context, ParkActionReceiver::class.java).setAction(actionName)
        val pi = PendingIntent.getBroadcast(
            context, actionName.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action(0, label, pi)
    }

    private fun notify(id: Int, builder: NotificationCompat.Builder) {
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
        // SecurityException when POST_NOTIFICATIONS not granted - the Settings
        // screen surfaces that; never crash a background worker over it.
    }

    private fun time(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    override fun statusPermitOn(label: String, vrn: String, zoneText: String?) {
        val text = buildString {
            append("Claimed at ${now()}")
            zoneText?.let { append(" · $it") }
        }
        notify(STATUS_ID, NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Permit on $label's car ($vrn)")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true))
        dismissEvents(context)
    }

    override fun statusParkedNoClaim(reason: String) {
        notify(STATUS_ID, NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Parked — permit untouched")
            .setContentText("$reason (${now()})")
            .setOngoing(true)
            .setOnlyAlertOnce(true))
    }

    override fun askGiveBack(otherLabel: String) {
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Give the permit back to $otherLabel?")
            .setContentText("You parked free; $otherLabel's car is parked outside and the permit is still on yours.")
            .setAutoCancel(true)
            .addAction(action(ParkActionReceiver.ACTION_GIVE_BACK, "Give back"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "Keep it")))
    }

    override fun blockedByOther(otherLabel: String, parkedAtMs: Long, heartbeatAtMs: Long) {
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$otherLabel's car is parked — permit NOT claimed")
            .setContentText("$otherLabel parked at ${time(parkedAtMs)} (last seen ${time(heartbeatAtMs)}). Claiming would leave their car unpermitted.")
            .setAutoCancel(true)
            .addAction(action(ParkActionReceiver.ACTION_CLAIM_FORCE, "Claim anyway"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "Ignore")))
    }

    override fun takeover(byLabel: String) {
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$byLabel took the permit")
            .setContentText("Your car is parked WITHOUT a permit. Move it or reclaim.")
            .setAutoCancel(true)
            .addAction(action(ParkActionReceiver.ACTION_CLAIM, "Reclaim"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "OK")))
    }

    override fun eventNote(text: String) {
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Permit Switcher")
            .setContentText(text)
            .setAutoCancel(true))
    }

    override fun askManualDecision() {
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Parked? Decide about the permit")
            .setContentText("Detected a possible park at ${now()}")
            .setAutoCancel(true)
            .addAction(action(ParkActionReceiver.ACTION_CLAIM, "Claim permit"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "Ignore"))
            .addAction(action(ParkActionReceiver.ACTION_FREE_HERE, "Free here")))
    }

    override fun switchFailed(reason: String?) {
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Permit switch FAILED")
            .setContentText(reason ?: "Network error - will retry automatically")
            .setAutoCancel(true)
            .addAction(action(ParkActionReceiver.ACTION_CLAIM, "Retry now"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "Ignore")))
    }

    override fun mismatchWarning(serverVrn: String?) {
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("WARNING: permit switch not confirmed")
            .setContentText("Server reports ${serverVrn ?: "no plate"} active - check the website!")
            .setAutoCancel(true)
            .addAction(action(ParkActionReceiver.ACTION_CLAIM, "Retry"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "Ignore")))
    }
}
