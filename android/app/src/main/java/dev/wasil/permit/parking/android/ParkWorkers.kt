package dev.wasil.permit.parking.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.core.app.NotificationCompat
import dev.wasil.permit.PermitApp
import dev.wasil.permit.parking.GuardedResult
import dev.wasil.permit.parking.LIVE_POLL_INTERVAL_MS
import dev.wasil.permit.parking.LiveLocation
import dev.wasil.permit.parking.ParkDetectionUseCase
import dev.wasil.permit.parking.ParkOutcome
import dev.wasil.permit.parking.PrefsParkStateStore
import dev.wasil.permit.parking.Settlement
import dev.wasil.permit.parking.shouldWatchForTakeover
import dev.wasil.permit.parking.zones.nextCheckInMin
import dev.wasil.permit.parking.zones.reminderFor
import dev.wasil.permit.parking.zones.tariffNext
import dev.wasil.permit.parking.zones.tariffNow
import dev.wasil.permit.ui.tariffNowText
import java.util.Calendar
import java.util.concurrent.TimeUnit

object ParkWorkers {
    const val DETECTION_WORK = "park_detection"
    const val CLAIM_WORK = "claim_permit"
    const val LIVE_LOCATION_WORK = "live_location"
    const val REMINDER_WORK = "park_reminder"

    /**
     * Stop retrying a claim after this many attempts. Without a cap, a
     * persistent failure — wrong credentials, an API change — retries forever
     * on exponential backoff and notifies on every attempt. Giving up silently
     * would be worse than the loop, so the last attempt says plainly that the
     * permit did not move.
     */
    const val MAX_CLAIM_ATTEMPTS = 5

    /** Detection needs no network; a failed claim is handed to enqueueClaim. */
    fun enqueueDetection(context: Context) {
        val request = OneTimeWorkRequestBuilder<ParkDetectionWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DETECTION_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Begin sampling the phone's position, now that the car says the phone is
     * in it.
     *
     * There is no location *stream* and no foreground service behind this — it
     * is one short worker that takes a fix and books the next one. A permit app
     * has no business holding a high-accuracy request open for the length of
     * every drive, and the only sample that is ever used is the last one, so
     * the gaps in between cost nothing.
     */
    fun startLiveLocation(context: Context) = enqueueLiveLocation(context, delayMs = 0)

    /**
     * Stop sampling. Called the instant the car disconnects, before detection
     * is even enqueued — a poll still in flight when the car stops is the one
     * thing that could write a position after the fact.
     */
    fun stopLiveLocation(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(LIVE_LOCATION_WORK)
    }

    internal fun enqueueLiveLocation(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<LiveLocationWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        // Unique, so a stray second connect event cannot start a second chain
        // sampling in parallel. REPLACE rather than KEEP because the worker
        // re-books itself under this same name: KEEP would drop every
        // subsequent poll on the floor and the trail would freeze at its first
        // sample — the stalest possible answer, in the direction that matters.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(LIVE_LOCATION_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Look at the parked spot in [delayMin] minutes and say something if a
     * tariff boundary has come within reach.
     *
     * Zero means now, which is how a fresh park starts the chain: the first run
     * either finds a boundary already inside its lead window and says so, or
     * finds nothing and simply books the run that will.
     *
     * No constraints, and deliberately none. A reminder needs no network — the
     * schedules are bundled — and adding a network or battery constraint would
     * let WorkManager hold the one job in this app whose entire value is that it
     * arrives before a particular minute.
     */
    fun scheduleReminder(context: Context, delayMin: Int) {
        val request = OneTimeWorkRequestBuilder<ParkReminderWorker>()
            .setInitialDelay(delayMin.toLong().coerceAtLeast(0), TimeUnit.MINUTES)
            .build()
        // REPLACE for the same reason live location uses it: the worker re-books
        // itself under this name, and KEEP would drop every link after the first.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(REMINDER_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /** Drop the chain. Called when the car is driven away — see CarBluetoothReceiver. */
    fun cancelReminder(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(REMINDER_WORK)
    }

    /**
     * The claim retries HERE, behind a CONNECTED constraint: after the Phase 2
     * DNS failure, blind backoff retries fired while still offline and the
     * chain outlived the outage uselessly. Now WorkManager holds attempts
     * until connectivity is actually back.
     */
    fun enqueueClaim(context: Context, force: Boolean = false, userInitiated: Boolean = true) {
        val request = OneTimeWorkRequestBuilder<ClaimPermitWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf("force" to force, "userInitiated" to userInitiated))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(CLAIM_WORK, ExistingWorkPolicy.REPLACE, request)
    }
}

private fun foregroundInfo(context: Context): ForegroundInfo {
    val notification = NotificationCompat.Builder(context, ParkNotifications.CHANNEL_STATUS)
        .setSmallIcon(dev.wasil.permit.R.drawable.ic_notification)
        .setContentTitle("Checking whether you parked…")
        .build()
    return ForegroundInfo(ParkNotifications.STATUS_ID, notification)
}

class ParkDetectionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext)

    override suspend fun doWork(): Result {
        val app = applicationContext as PermitApp
        val outcome = ParkDetectionUseCase(
            signals = PlayServicesSignals(applicationContext),
            stateStore = PrefsParkStateStore.from(applicationContext),
            zoneResolver = app.zoneResolver(),
            guardedClaim = app.guardedClaim(),
            notifier = ParkNotifications(applicationContext),
            scheduler = WorkManagerScheduler(applicationContext),
            parkLog = app.parkLogStore,
            // The same lookup, and the same two-level name, the map header
            // shows — so a history record and the map can never disagree about
            // what a place is called.
            namePlace = { point ->
                placeLabelAt(applicationContext, point)
                    ?.let { listOfNotNull(it.district, it.detail).joinToString(" · ") }
            },
            // The live line, not the timetable: "€3,01/h · until 19:00" is what
            // the spot was charging when you left the car, which is the only
            // rate a record has any business quoting.
            rateNow = { area ->
                val now = Calendar.getInstance()
                val dayIndex = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
                val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                tariffNowText(tariffNow(area.windows, dayIndex, minuteOfDay), dayIndex, minuteOfDay)
            },
        ).run()
        // Retry the CLAIM, not the detection: we know we're parked.
        if (outcome == ParkOutcome.SwitchFailed) {
            ParkWorkers.enqueueClaim(applicationContext, userInitiated = false)
        }
        // Start the reminder chain for whatever this park turned out to be. Run
        // unconditionally rather than only for the outcomes that look like they
        // need it: the worker asks the four questions itself, from state written
        // moments ago, and a park that got the permit is exactly as capable of
        // losing it an hour later as one that never had it.
        if (PrefsParkStateStore.from(applicationContext).parked) {
            ParkWorkers.scheduleReminder(applicationContext, delayMin = 0)
        }
        return Result.success()
    }
}

/**
 * Ask what the parked spot is doing, say something if a boundary is close, and
 * book the next look.
 *
 * The chain, rather than one alarm per park: see
 * [dev.wasil.permit.parking.zones.nextCheckInMin]. Every condition is re-read
 * here at the moment of firing instead of being baked into the schedule, which
 * is what makes the cancellation story so small. Drive off and the park closes,
 * so this finds `parked == false` and stops. Claim the permit and the open
 * record becomes [Settlement.PERMIT], so this finds nothing to say. Correct the
 * pin onto a free street and `lastZoneCode` is null, so this stops. None of
 * those needed a cancel call to be correct — only [ParkWorkers.cancelReminder]
 * on drive-off, and that is a courtesy to the battery rather than a guard
 * against a wrong notification.
 */
class ParkReminderWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PermitApp
        val store = PrefsParkStateStore.from(applicationContext)
        // Not parked any more: the chain has served its purpose and ends here,
        // unbooked. Same stale-timer guard LiveLocationWorker opens with, and
        // for the same reason — WorkManager promises nothing about when a
        // delayed job actually lands.
        if (!store.parked) return Result.success()

        // The area is geometry, not the clock: a polygon this car sits inside.
        // A null code is a spot with no tariff area resolved — a free street, or
        // a park whose position never came back (D9) — and neither has a
        // boundary this app is entitled to name.
        val area = store.lastZoneCode
            ?.let { code -> app.tariffAreas?.firstOrNull { it.code == code } }
            ?: return Result.success()

        val calendar = Calendar.getInstance()
        val dayIndex = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val now = tariffNow(area.windows, dayIndex, minuteOfDay)
        val next = tariffNext(area.windows, dayIndex, minuteOfDay)

        // The settlement half, read from the open history record rather than
        // from the permit site. That record is already re-badged by every path
        // that can change the answer — settleOpen on a claim, uncoverOpen when
        // the other car takes it — so it is both local and current, where a
        // remembered holder VRN would be local and stale.
        val open = app.parkLogStore.all().lastOrNull()
        val settled = open != null && open.endedAtMs == null && open.settlement == Settlement.PERMIT

        reminderFor(
            parked = true,
            inPaidArea = store.parkedOutside && store.parkedOutsideKnown,
            permitSettles = settled,
            now = now,
            next = next,
        )?.let {
            ParkNotifications(applicationContext)
                .reminder(it, dayIndex, minuteOfDay, open?.place)
        }

        // Null here is an area that never changes — nothing left to wait for, so
        // the chain ends rather than waking all week to rediscover that.
        nextCheckInMin(now, next)?.let {
            ParkWorkers.scheduleReminder(applicationContext, delayMin = it)
        }
        return Result.success()
    }
}

/**
 * One sample of where the phone is while the car's Bluetooth is connected, and
 * the booking for the next one.
 *
 * Everything this worker touches is [dev.wasil.permit.parking.LiveLocation],
 * and that is the point. It cannot resolve a zone, switch a permit or publish
 * anything, because it never holds a type any of those accept — not by
 * convention, but because a [dev.wasil.permit.parking.GeoPoint] goes into
 * [dev.wasil.permit.parking.LiveLocation.captured] here and no expression in
 * this file can get one back out. A late poll is therefore uninteresting rather
 * than dangerous, which is the property that was asked for.
 *
 * Since v0.6.5 it carries one passenger that is not about location at all: the
 * takeover watch. That needs no new alarm, no foreground service and no revived
 * heartbeat — this worker is already awake every 20 s for the length of every
 * drive, which is exactly the stretch the watch could not previously cover, and
 * exactly the stretch where the phone is demonstrably in a car with a person in
 * it. Throttled to one read every few minutes; see `shouldWatchForTakeover`.
 */
class LiveLocationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = PrefsParkStateStore.from(applicationContext)
        // The stale-timer guard. WorkManager makes no promise about when a
        // delayed job actually lands, so one booked mid-drive can easily arrive
        // after the car has been parked, walked away from, and the permit
        // decided. Nothing after this line should run in that world.
        if (!store.carLinkConnected) return Result.success()

        // The one moment the app ever sees a park *end*: the car's link came
        // back up, so you got in and drove away. This is why history can show a
        // time range at all — a record whose end nothing observed says "from
        // 09:12" rather than borrowing the next park's start and calling it a
        // departure. Idempotent, so the nineteen later runs of this worker cost
        // a read and nothing else.
        (applicationContext as? PermitApp)?.parkLogStore?.closeOpen(System.currentTimeMillis())

        val point = PlayServicesSignals(applicationContext).currentLocation()
        // Re-read rather than trust the check above: taking a fix can take
        // seconds, and the car can disconnect inside them. A sample captured
        // while connected but written after the link dropped would be a
        // position racing the detection that is already deciding what to do
        // with the trail, so it is dropped instead. The fix detection takes for
        // itself covers this moment anyway.
        if (point != null && store.carLinkConnected) {
            store.liveLocation = LiveLocation.captured(point, System.currentTimeMillis())
        }

        // Mid-drive: has the other phone taken the permit? Deliberately last,
        // deliberately swallowed, and deliberately not a retry. This worker's
        // job is to sample and re-book itself; a network failure on a courtesy
        // read must not cost the trail its next sample, and the next tick is
        // three minutes away in any case. The stamp is written before the read
        // so a hanging call cannot let the throttle fire again behind it.
        val nowMs = System.currentTimeMillis()
        if (store.carLinkConnected && shouldWatchForTakeover(store.lastTakeoverCheckMs, nowMs)) {
            store.lastTakeoverCheckMs = nowMs
            runCatching { SharedSync.watchForTakeover(applicationContext) }
        }

        if (store.carLinkConnected) {
            ParkWorkers.enqueueLiveLocation(applicationContext, LIVE_POLL_INTERVAL_MS)
        }
        return Result.success()
    }
}

class ClaimPermitWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext)

    override suspend fun doWork(): Result {
        val app = applicationContext as PermitApp
        val store = PrefsParkStateStore.from(applicationContext)
        // Situation changed since this was enqueued (drove off / reset): stale claim must not fire.
        if (!store.parked) return Result.success()

        val force = inputData.getBoolean("force", false)
        val userInitiated = inputData.getBoolean("userInitiated", true)
        val zoneText = store.lastZoneCode?.let { code ->
            app.tariffAreas?.firstOrNull { it.code == code }
                ?.let { "${it.tariffText} zone ${it.code}" }
        }
        val notifications = ParkNotifications(applicationContext)

        return when (val result = app.guardedClaim()
            .claim(force = force, userInitiated = userInitiated, zoneText = zoneText)) {
            is GuardedResult.Blocked -> {
                notifications.blockedByOther(result.otherLabel, result.other)
                Result.success()
            }
            is GuardedResult.Done -> when (result.outcome) {
                ParkOutcome.SwitchFailed ->
                    if (runAttemptCount + 1 >= ParkWorkers.MAX_CLAIM_ATTEMPTS) {
                        notifications.switchGaveUp()
                        Result.failure()
                    } else {
                        Result.retry()
                    }
                ParkOutcome.ManualNeeded -> {
                    notifications.askManualDecision()
                    Result.success()
                }
                else -> {
                    result.guardSkippedNote?.let { notifications.eventNote(it) }
                    SharedSync.requestSync(applicationContext)
                    Result.success()
                }
            }
        }
    }
}
