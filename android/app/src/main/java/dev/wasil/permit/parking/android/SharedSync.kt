package dev.wasil.permit.parking.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.wasil.permit.PermitApp
import dev.wasil.permit.parking.FreeZone
import dev.wasil.permit.parking.GiveBackResult
import dev.wasil.permit.parking.MyCar
import dev.wasil.permit.parking.ParkScheduler
import dev.wasil.permit.parking.ParkStateStore
import dev.wasil.permit.parking.PrefsFreeZoneStore
import dev.wasil.permit.parking.PrefsParkStateStore
import dev.wasil.permit.parking.label
import dev.wasil.permit.parking.other
import dev.wasil.permit.parking.shared.ClaimGuard
import dev.wasil.permit.parking.shared.PhoneState
import dev.wasil.permit.parking.takeoverBy
import dev.wasil.permit.ui.formatCoordinates
import java.util.concurrent.TimeUnit

object SharedSync {
    const val SYNC_WORK = "sync_state"
    const val HEARTBEAT_WORK = "heartbeat"
    const val HEARTBEAT_NOW_WORK = "heartbeat_now"
    const val GIVE_BACK_WORK = "give_back"
    const val FREE_HERE_WORK = "mark_free_zone"

    private fun connected() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun requestSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncStateWorker>()
            .setConstraints(connected())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(SYNC_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun requestGiveBack(context: Context, executeNow: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<GiveBackWorker>()
            .setConstraints(connected())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf("executeNow" to executeNow))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(GIVE_BACK_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun requestFreeHere(context: Context) {
        val request = OneTimeWorkRequestBuilder<MarkFreeZoneWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(FREE_HERE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Reads the shared permit node and says so if the other phone has taken it.
     *
     * Lifted out of [HeartbeatWorker] in v0.6.5 because it had been trapped
     * there. The heartbeat runs only while this phone is parked outside, so the
     * takeover check ran only then too — and the case it most needed to cover
     * was the drive, when the heartbeat is deliberately cancelled. Two callers
     * now: the heartbeat, which no longer gates it on being parked outside, and
     * the live-location sampler, which is awake every 20 s while the car's
     * Bluetooth is up (throttled — see `shouldWatchForTakeover`).
     *
     * Throws whatever the network throws; the callers decide whether that is
     * worth a retry, and for the sampler it never is.
     */
    suspend fun watchForTakeover(context: Context) {
        val app = context.applicationContext as? PermitApp ?: return
        val store = PrefsParkStateStore.from(context)
        val shared = app.sharedStateStore()
        if (!shared.configured) return
        val permit = shared.readPermit() ?: return
        val by = takeoverBy(permit, store.myCar, store.lastAlertedClaimMs) ?: return
        ParkNotifications(context).takeover(by.label())
        store.lastAlertedClaimMs = permit.claimedAtMs
    }

    /** Heartbeat runs only while parked outside; KEEP avoids resetting the period. */
    fun ensureHeartbeat(context: Context, parkedOutside: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (parkedOutside) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
                .setConstraints(connected())
                .build()
            wm.enqueueUniquePeriodicWork(HEARTBEAT_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            wm.cancelUniqueWork(HEARTBEAT_WORK)
        }
    }

    /**
     * Runs HeartbeatWorker right now instead of waiting for its next 15-minute
     * tick — WorkManager's periodic floor, which cannot be shortened. Called
     * whenever the app comes to the foreground, so a forced takeover on the
     * other phone surfaces the moment this one is looked at rather than lagging
     * behind the schedule. A no-op (fast success) if this phone isn't parked
     * outside. This is an approximation, not a push: a phone left fully
     * backgrounded still waits for the periodic tick, same as before — genuinely
     * instant delivery needs a push channel (e.g. FCM), which is out of scope.
     */
    fun requestImmediateHeartbeat(context: Context) {
        val request = OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(connected())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(HEARTBEAT_NOW_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /** Back in the car: any pending detection/claim/give-back is now stale. */
    fun cancelClaimChain(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(ParkWorkers.DETECTION_WORK)
        wm.cancelUniqueWork(ParkWorkers.CLAIM_WORK)
        wm.cancelUniqueWork(GIVE_BACK_WORK)
    }
}

class WorkManagerScheduler(private val context: Context) : ParkScheduler {
    override fun requestSync() = SharedSync.requestSync(context)
    override fun requestGiveBack() = SharedSync.requestGiveBack(context)
}

/** Pushes my current phone node; keeps the heartbeat schedule in step. */
class SyncStateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PermitApp
        val store = PrefsParkStateStore.from(applicationContext)
        val shared = app.sharedStateStore()
        if (!shared.configured) return Result.success()
        return try {
            // Four fields, and every one of them is read by the other phone.
            // No position, no zone code, no rate — see PhoneState for why each
            // went, and why not publishing beats filtering when there is no
            // server to filter at.
            //
            // parkedOutsideKnown joined them in v0.6.5. It had been recorded
            // locally since v0.6.2 and published by nothing, which left the fix
            // half done: the phone knew its own parkedOutside was a leftover
            // and the other phone was still handed it as a fact.
            shared.writeMine(PhoneState(
                parkedOutside = store.parkedOutside,
                parkedAtMs = store.parkedAtMs,
                parkedOutsideKnown = store.parkedOutsideKnown,
            ))
            SharedSync.ensureHeartbeat(applicationContext, store.parkedOutside)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/**
 * Refresh my heartbeat, and watch for permit takeovers.
 *
 * The two used to be one thing, gated together on `parkedOutside`, and that was
 * the bug. Refreshing a heartbeat while not parked outside is genuinely
 * pointless — it stamps freshness onto a "not parked outside" nobody acts on —
 * but *watching* is not, and the early return took both out at once. It also
 * meant [SharedSync.requestImmediateHeartbeat], which runs whenever the app is
 * brought to the foreground, checked nothing at all during a drive.
 *
 * So the heartbeat write keeps its gate and the watch loses it.
 */
class HeartbeatWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PermitApp
        val store = PrefsParkStateStore.from(applicationContext)
        val shared = app.sharedStateStore()
        if (!shared.configured) return Result.success()
        // Unschedule the periodic work as soon as it stops applying, exactly as
        // before — but after the watch below has had its turn, not instead of it.
        if (!store.parkedOutside) SharedSync.ensureHeartbeat(applicationContext, false)
        return try {
            if (store.parkedOutside) shared.heartbeat()
            SharedSync.watchForTakeover(applicationContext)
            store.lastTakeoverCheckMs = System.currentTimeMillis()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/**
 * Give the permit back after parking free. Auto-claim ON executes directly;
 * OFF only asks (notification) when a give-back would actually apply.
 * The "Give back" notification action re-runs this with executeNow = true.
 */
class GiveBackWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PermitApp
        val store = PrefsParkStateStore.from(applicationContext)
        val shared = app.sharedStateStore()
        if (!shared.configured) return Result.success()
        val executeNow = inputData.getBoolean("executeNow", false)

        if (store.autoClaim || executeNow) {
            return when (app.guardedClaim().giveBack()) {
                is GiveBackResult.Given -> Result.success()
                GiveBackResult.NothingToDo -> Result.success()
                GiveBackResult.Failed -> Result.retry()
            }
        }
        // Ask-first mode: check the conditions, then only notify.
        return try {
            val other = shared.readOther() ?: return Result.success()
            val fresh = other.parkedOutside &&
                System.currentTimeMillis() - other.heartbeatAtMs <= ClaimGuard.STALE_AFTER_MS
            if (!fresh) return Result.success()
            val config = app.credentialStore.load() ?: return Result.success()
            val mine = store.myCar ?: return Result.success()
            val myPlate = if (mine == MyCar.WASIL) config.wasilPlate else config.walidPlate
            if (app.repository.activePlate() == myPlate) {
                ParkNotifications(applicationContext).askGiveBack(mine.other().label())
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/** "Free here": read a FRESH location at tap time (Phase 2 stored a stale one). */
class MarkFreeZoneWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val point = PlayServicesSignals(applicationContext).currentLocation()
        val notifications = ParkNotifications(applicationContext)
        if (point == null) {
            notifications.eventNote("Couldn't get a location — free zone not marked. Try again outdoors.")
            return Result.success()
        }
        val store = PrefsParkStateStore.from(applicationContext)
        // No date label: the day it was marked told you nothing about where
        // it was. Named from a reverse geocode instead — a real address
        // beats a timestamp — falling back to coordinates if that fails.
        // Already in a background worker, so awaiting it here (it's
        // internally timeout-bounded) costs nothing the UI thread would feel.
        val label = reverseGeocodeAddress(applicationContext, point)
            ?: formatCoordinates(point.lat, point.lng)
        PrefsFreeZoneStore(
            applicationContext.getSharedPreferences("park_state", Context.MODE_PRIVATE),
        ).add(FreeZone(point.lat, point.lng, 60.0, label))
        store.parkedOutside = false
        store.lastZoneCode = null
        SharedSync.requestSync(applicationContext)
        notifications.statusParkedNoClaim("in a free zone (just marked)")
        return Result.success()
    }
}
