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
import dev.wasil.permit.parking.PrefsFreeZoneStore
import dev.wasil.permit.parking.PrefsParkStateStore
import dev.wasil.permit.parking.label
import dev.wasil.permit.parking.other
import dev.wasil.permit.parking.shared.ClaimGuard
import dev.wasil.permit.parking.shared.PhoneState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
            val location = store.lastParkLocation.takeIf { store.parkedOutside }
            shared.writeMine(PhoneState(
                parkedOutside = store.parkedOutside,
                lat = location?.lat,
                lng = location?.lng,
                accuracyM = location?.accuracyM?.toDouble(),
                zoneCode = store.lastZoneCode.takeIf { store.parkedOutside },
                parkedAtMs = store.parkedAtMs,
            ))
            SharedSync.ensureHeartbeat(applicationContext, store.parkedOutside)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/** While parked outside: refresh my heartbeat and watch for permit takeovers. */
class HeartbeatWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as PermitApp
        val store = PrefsParkStateStore.from(applicationContext)
        if (!store.parkedOutside) {
            SharedSync.ensureHeartbeat(applicationContext, false)
            return Result.success()
        }
        val shared = app.sharedStateStore()
        if (!shared.configured) return Result.success()
        return try {
            shared.heartbeat()
            val permit = shared.readPermit()
            val me = store.myCar
            if (permit != null && me != null && permit.holder != me.name.lowercase() &&
                permit.claimedAtMs > store.lastAlertedClaimMs
            ) {
                ParkNotifications(applicationContext).takeover(me.other().label())
                store.lastAlertedClaimMs = permit.claimedAtMs
            }
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
        val date = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
        PrefsFreeZoneStore(
            applicationContext.getSharedPreferences("park_state", Context.MODE_PRIVATE),
        ).add(FreeZone(point.lat, point.lng, 60.0, "Marked $date"))
        store.parkedOutside = false
        store.lastZoneCode = null
        SharedSync.requestSync(applicationContext)
        notifications.statusParkedNoClaim("in a free zone (just marked)")
        return Result.success()
    }
}
