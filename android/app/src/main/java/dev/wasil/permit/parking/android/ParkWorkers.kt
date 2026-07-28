package dev.wasil.permit.parking.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.core.app.NotificationCompat
import dev.wasil.permit.PermitApp
import dev.wasil.permit.parking.ClaimPermit
import dev.wasil.permit.parking.ParkDetectionUseCase
import dev.wasil.permit.parking.ParkOutcome
import dev.wasil.permit.parking.PrefsFreeZoneStore
import dev.wasil.permit.parking.PrefsParkStateStore
import java.util.concurrent.TimeUnit

object ParkWorkers {
    const val DETECTION_WORK = "park_detection"
    const val CLAIM_WORK = "claim_permit"

    fun enqueueDetection(context: Context) {
        val request = OneTimeWorkRequestBuilder<ParkDetectionWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DETECTION_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun enqueueClaim(context: Context) {
        val request = OneTimeWorkRequestBuilder<ClaimPermitWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(CLAIM_WORK, ExistingWorkPolicy.REPLACE, request)
    }
}

private fun claimPermit(context: Context): ClaimPermit {
    val app = context.applicationContext as PermitApp
    return ClaimPermit(
        app.repository,
        app.credentialStore,
        PrefsParkStateStore.from(context),
        ParkNotifications(context),
    )
}

private fun foregroundInfo(context: Context): ForegroundInfo {
    val notification = NotificationCompat.Builder(context, ParkNotifications.CHANNEL_STATUS)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("Checking whether you parked…")
        .build()
    return ForegroundInfo(ParkNotifications.STATUS_ID, notification)
}

class ParkDetectionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext)

    override suspend fun doWork(): Result {
        val context = applicationContext
        val stateStore = PrefsParkStateStore.from(context)

        // A retry after a failed switch should not re-run detection: we already
        // know we're parked, only the network call needs another attempt.
        val outcome = if (runAttemptCount > 0 && stateStore.parked) {
            claimPermit(context).claim()
        } else {
            ParkDetectionUseCase(
                signals = PlayServicesSignals(context),
                stateStore = stateStore,
                freeZones = PrefsFreeZoneStore(
                    context.getSharedPreferences("park_state", Context.MODE_PRIVATE),
                ),
                claimPermit = claimPermit(context),
                notifier = ParkNotifications(context),
            ).run()
        }
        return if (outcome == ParkOutcome.SwitchFailed) Result.retry() else Result.success()
    }
}

class ClaimPermitWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(applicationContext)

    override suspend fun doWork(): Result {
        val outcome = claimPermit(applicationContext).claim()
        return if (outcome == ParkOutcome.SwitchFailed) Result.retry() else Result.success()
    }
}
