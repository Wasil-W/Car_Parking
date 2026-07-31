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
import dev.wasil.permit.parking.ParkDetectionUseCase
import dev.wasil.permit.parking.ParkOutcome
import dev.wasil.permit.parking.PrefsParkStateStore
import java.util.concurrent.TimeUnit

object ParkWorkers {
    const val DETECTION_WORK = "park_detection"
    const val CLAIM_WORK = "claim_permit"

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
        ).run()
        // Retry the CLAIM, not the detection: we know we're parked.
        if (outcome == ParkOutcome.SwitchFailed) {
            ParkWorkers.enqueueClaim(applicationContext, userInitiated = false)
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
                notifications.blockedByOther(
                    result.otherLabel, result.other.parkedAtMs, result.other.heartbeatAtMs,
                )
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
