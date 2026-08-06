package dev.wasil.permit.parking.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.wasil.permit.MainActivity
import dev.wasil.permit.R
import dev.wasil.permit.parking.ParkNotifier
import dev.wasil.permit.parking.ParkStateStore
import dev.wasil.permit.parking.PendingDecision
import dev.wasil.permit.parking.PrefsParkStateStore
import dev.wasil.permit.parking.myCarForLabel
import dev.wasil.permit.parking.other
import dev.wasil.permit.parking.shared.PhoneState
import dev.wasil.permit.ui.blockedNotificationText
import dev.wasil.permit.ui.blockedTitle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ParkNotifications(private val context: Context) : ParkNotifier {

    // Rebuilt lazily rather than injected: ParkNotifications is constructed
    // ad hoc from workers and receivers throughout the app (see PermitApp,
    // ParkWorkers, SharedSync), none of which currently thread a
    // ParkStateStore through to here — matching how those call sites already
    // rebuild PrefsParkStateStore.from(context) themselves.
    private val store: ParkStateStore by lazy { PrefsParkStateStore.from(context) }

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

    /**
     * Tapping the notification body opens the app. When [decisionId] is given
     * the intent carries only that identifier — never the facts, which go
     * stale in extras and vanish with a killed process anyway. MainActivity
     * re-reads the persisted decision from [ParkStateStore] instead, and falls
     * back to the normal screen if it's since been cleared or expired.
     */
    private fun openAppIntent(decisionId: Long? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            decisionId?.let { putExtra(MainActivity.EXTRA_DECISION_ID, it) }
        }
        return PendingIntent.getActivity(
            context, decisionId?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Persists the decision the notification is about, and returns its content intent. */
    private fun raise(decision: PendingDecision): PendingIntent {
        store.pendingDecision = decision
        return openAppIntent(decision.raisedAtMs)
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
            .setSmallIcon(notificationIconFor(myCarForLabel(label)))
            .setContentTitle("Permit on $label's car ($vrn)")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent()))
        dismissEvents(context)
    }

    override fun statusParkedNoClaim(reason: String) {
        notify(STATUS_ID, NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Parked — permit untouched")
            .setContentText("$reason (${now()})")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent()))
    }

    override fun askGiveBack(otherLabel: String) {
        val decision = PendingDecision.GiveBack(otherLabel, raisedAtMs = System.currentTimeMillis())
        // Only raised when the permit is on my own plate right now (see
        // GiveBackWorker) - the holder is mine, not otherLabel's.
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(notificationIconFor(store.myCar))
            .setContentTitle("Give the permit back to $otherLabel?")
            .setContentText("You parked free; $otherLabel's car is parked outside and the permit is still on yours.")
            .setAutoCancel(true)
            .setContentIntent(raise(decision))
            .addAction(action(ParkActionReceiver.ACTION_GIVE_BACK, "Give back"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "Keep it")))
    }

    override fun blockedByOther(otherLabel: String, other: PhoneState) {
        val known = other.parkedOutsideKnown
        val decision = PendingDecision.Blocked(
            otherLabel, other.parkedAtMs, other.heartbeatAtMs,
            raisedAtMs = System.currentTimeMillis(), known = known,
        )
        // The permit is what's blocking the claim, so it's on the other car.
        // Both strings come from DecisionPresentation so this notification and
        // the screen it opens cannot describe the same situation differently —
        // and so the unknown case gets its own wording rather than borrowing
        // the confident one.
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(notificationIconFor(store.myCar?.other()))
            .setContentTitle(blockedTitle(otherLabel, known))
            .setContentText(
                blockedNotificationText(otherLabel, other.parkedAtMs, other.heartbeatAtMs, known),
            )
            .setAutoCancel(true)
            .setContentIntent(raise(decision))
            .addAction(action(ParkActionReceiver.ACTION_CLAIM_FORCE, "Claim anyway"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "Ignore")))
    }

    override fun takeover(byLabel: String) {
        val decision = PendingDecision.Takeover(byLabel, raisedAtMs = System.currentTimeMillis())
        // byLabel is always the other car - it took the permit from mine.
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(notificationIconFor(store.myCar?.other()))
            .setContentTitle("$byLabel took the permit")
            .setContentText("Your car is parked WITHOUT a permit. Move it or reclaim.")
            .setAutoCancel(true)
            .setContentIntent(raise(decision))
            .addAction(action(ParkActionReceiver.ACTION_CLAIM, "Reclaim"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "OK")))
    }

    override fun eventNote(text: String) {
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Handoff")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent()))
    }

    override fun askManualDecision() {
        val decision = PendingDecision.Manual(raisedAtMs = System.currentTimeMillis())
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Parked? Decide about the permit")
            .setContentText("Detected a possible park at ${now()}")
            .setAutoCancel(true)
            .setContentIntent(raise(decision))
            .addAction(action(ParkActionReceiver.ACTION_CLAIM, "Claim permit"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "Ignore"))
            .addAction(action(ParkActionReceiver.ACTION_FREE_HERE, "Free here")))
    }

    /**
     * The last attempt has failed and retrying has stopped. Says so plainly:
     * going quiet after giving up would leave the permit on the wrong car with
     * nothing to indicate it.
     */
    fun switchGaveUp() {
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Gave up switching the permit")
            .setContentText(
                "Tried ${ParkWorkers.MAX_CLAIM_ATTEMPTS} times and stopped. " +
                    "The permit did NOT move — switch it yourself.",
            )
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .addAction(action(ParkActionReceiver.ACTION_CLAIM, "Try again"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "Ignore")))
    }

    override fun switchFailed(reason: String?) {
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Permit switch failed")
            .setContentText(reason ?: "Network error - will retry automatically")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .addAction(action(ParkActionReceiver.ACTION_CLAIM, "Retry now"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "Ignore")))
    }

    override fun mismatchWarning(serverVrn: String?) {
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Permit switch not confirmed")
            .setContentText("The site still shows ${serverVrn ?: "no plate"} on the permit. Worth checking.")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .addAction(action(ParkActionReceiver.ACTION_CLAIM, "Retry"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "Ignore")))
    }
}
