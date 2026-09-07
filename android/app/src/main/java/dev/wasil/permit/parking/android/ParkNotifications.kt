package dev.wasil.permit.parking.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.wasil.permit.MainActivity
import dev.wasil.permit.PermitApp
import dev.wasil.permit.R
import dev.wasil.permit.data.store.roster
import dev.wasil.permit.parking.ParkNotifier
import dev.wasil.permit.parking.ParkStateStore
import dev.wasil.permit.parking.PendingDecision
import dev.wasil.permit.parking.PrefsParkStateStore
import dev.wasil.permit.parking.Roster
import dev.wasil.permit.parking.shared.PhoneState
import dev.wasil.permit.parking.zones.Reminder
import dev.wasil.permit.ui.blockedNotificationText
import dev.wasil.permit.ui.blockedTitle
import dev.wasil.permit.ui.reminderBody
import dev.wasil.permit.ui.reminderTitle
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

    /**
     * The cars, for the one thing these notifications need them for: which side
     * the icon's dot sits on. Read from the application rather than by opening
     * the encrypted store again, and falling back to the seed so a notification
     * raised before any permit exists still draws.
     */
    private val roster: Roster by lazy {
        (context.applicationContext as? PermitApp)?.credentialStore?.roster() ?: Roster.SEED
    }

    /** Slot 0 or 1 for this phone's own car; null when nothing can say. */
    private fun mySlot(): Int? = roster.identitySlotOf(store.thisPhoneDrives)

    /** Slot for the other car, when there is exactly one other car. */
    private fun otherSlot(): Int? =
        roster.other(store.thisPhoneDrives)?.let { roster.identitySlotOf(it.id) }

    companion object {
        const val CHANNEL_STATUS = "permit_status"
        const val CHANNEL_EVENTS = "park_events"

        /**
         * The two reminders get a channel each, and that is the answer to
         * "should the quieter one exist at all".
         *
         * They are not the same kind of message. One can save a fine and the
         * other saves a few minutes of meter, so putting both on one channel
         * would mean silencing the useful one to be rid of the chatty one.
         * Android's own per-channel controls settle it without this app growing
         * a settings screen for it: [CHANNEL_OWING] is HIGH and heads up,
         * [CHANNEL_FREE] is DEFAULT and does not.
         */
        const val CHANNEL_OWING = "park_owing"
        const val CHANNEL_FREE = "park_free"

        const val STATUS_ID = 1
        const val EVENT_ID = 2

        /**
         * One id for both reminders, so a new one replaces the old.
         *
         * A shade holding "you start paying at 09:00" from this morning next to
         * "free from 19:00" from tonight would be showing two answers to one
         * question, and the older is always the wrong one.
         */
        const val REMINDER_ID = 3

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "Permit status", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_EVENTS, "Parking events", NotificationManager.IMPORTANCE_HIGH)
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_OWING, "Before you start paying", NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "A paid street is about to start charging and nothing is covering it." }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_FREE, "When parking turns free", NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "The spot you are parked in is about to stop charging." }
            )
        }

        fun dismissEvents(context: Context) {
            NotificationManagerCompat.from(context).cancel(EVENT_ID)
        }

        /**
         * Drops the reminder, whichever of the two it was.
         *
         * Called from every notification action: acting on the permit at all
         * resolves the thing the reminder was raised about, and leaving it in
         * the shade afterwards would keep asking a question already answered.
         */
        fun dismissReminder(context: Context) {
            NotificationManagerCompat.from(context).cancel(REMINDER_ID)
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

    override fun statusPermitOn(carName: String, identitySlot: Int?, vrn: String, zoneText: String?) {
        val text = buildString {
            append("Claimed at ${now()}")
            zoneText?.let { append(" · $it") }
        }
        notify(STATUS_ID, NotificationCompat.Builder(context, CHANNEL_STATUS)
            // The slot travels with the name now. It used to be recovered from
            // the name by string comparison, which lit Walid's icon for any
            // label the notifier did not recognise.
            .setSmallIcon(notificationIconFor(identitySlot))
            .setContentTitle("Permit on $carName's car ($vrn)")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent()))
        dismissEvents(context)
        // The permit is now on this car, so any "you start paying at 09:00" in
        // the shade has just stopped being true. This is the ONE place a permit
        // event may clear the reminder, and it is precise: it fires exactly when
        // a claim has actually landed, not on every button in the app. The
        // chain itself needs no help — its next wake reads the settled record
        // and says nothing — this only clears the stale card.
        dismissReminder(context)
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
            .setSmallIcon(notificationIconFor(mySlot()))
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
            .setSmallIcon(notificationIconFor(otherSlot()))
            .setContentTitle(blockedTitle(otherLabel, known))
            .setContentText(
                blockedNotificationText(otherLabel, other.parkedAtMs, other.heartbeatAtMs, known),
            )
            .setAutoCancel(true)
            .setContentIntent(raise(decision))
            .addAction(action(ParkActionReceiver.ACTION_CLAIM_FORCE, "Claim anyway"))
            .addAction(action(ParkActionReceiver.ACTION_IGNORE, "Ignore")))
    }

    override fun takeover(byName: String, identitySlot: Int?) {
        val decision = PendingDecision.Takeover(byName, raisedAtMs = System.currentTimeMillis())
        // byName is whichever car the room says now holds it, and its slot
        // comes from the same lookup rather than from "not mine".
        notify(EVENT_ID, NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(notificationIconFor(identitySlot))
            .setContentTitle("$byName took the permit")
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
     * A boundary is coming and this spot is not covered.
     *
     * The only notification in the app raised about *time* rather than about
     * the permit, which is why it takes neither an identity icon nor a slot: it
     * is a fact about a street, and drawing a brother's colour on it would say
     * the street belonged to someone.
     *
     * [Reminder.StartsOwing] offers Claim because that is the one action that can
     * still change the outcome — and if the other car is genuinely parked out
     * there, [GuardedClaim] answers with [blockedByOther] rather than taking it.
     * [Reminder.BecomesFree] offers nothing, because there is nothing the app can
     * do about it and a button that only dismisses is a button pretending.
     */
    fun reminder(reminder: Reminder, dayOfWeek: Int, minuteOfDay: Int, place: String?) {
        val channel = when (reminder) {
            is Reminder.StartsOwing -> CHANNEL_OWING
            is Reminder.BecomesFree -> CHANNEL_FREE
        }
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminderTitle(reminder, dayOfWeek, minuteOfDay))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
        // BigTextStyle because the shade clipped the real thing: "from €1,72/h ·
        // Oostelijk Havengebied · Cr…" on the emulator, losing the street. The
        // truncation order is at least the right way round — the time is in the
        // title and the rate comes first — but the street is what tells you
        // *which* car this is about, and without a style the expanded view
        // showed the same clipped line. Caught by looking, not by a test.
        reminderBody(reminder, place)?.let {
            builder.setContentText(it)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(it))
        }
        if (reminder is Reminder.StartsOwing) {
            // The reminder's OWN actions, not the permit card's. Sharing them
            // meant "Ignore" here also cancelled an unanswered park question and
            // erased its pending decision — see ParkActionReceiver.
            builder.addAction(action(ParkActionReceiver.ACTION_REMINDER_CLAIM, "Claim permit"))
            builder.addAction(action(ParkActionReceiver.ACTION_REMINDER_IGNORE, "Ignore"))
        }
        notify(REMINDER_ID, builder)
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
