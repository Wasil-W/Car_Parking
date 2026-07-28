package dev.wasil.permit.parking

import kotlinx.coroutines.delay

interface DetectionSignals {
    suspend fun start()
    suspend fun stop()
    fun activitySamples(): List<ActivitySample>
    suspend fun currentLocation(): GeoPoint?
}

interface ParkNotifier {
    fun statusPermitOn(label: String, vrn: String)
    fun statusFreeZone()
    fun askManualDecision()
    fun switchFailed(reason: String?)
    fun mismatchWarning(serverVrn: String?)
}

sealed interface ParkOutcome {
    data object NotConfigured : ParkOutcome
    data object FalseAlarm : ParkOutcome
    data object FreeZoneParked : ParkOutcome
    data class Claimed(val vrn: String) : ParkOutcome
    data object ManualNeeded : ParkOutcome
    data object SwitchFailed : ParkOutcome
    data class MismatchDetected(val serverVrn: String?) : ParkOutcome
}

class ParkDetectionUseCase(
    private val signals: DetectionSignals,
    private val stateStore: ParkStateStore,
    private val freeZones: FreeZoneStore,
    private val claimPermit: ClaimPermit,
    private val notifier: ParkNotifier,
    private val pollIntervalMs: Long = 5_000,
) {
    suspend fun run(): ParkOutcome {
        if (stateStore.myCar == null) return ParkOutcome.NotConfigured

        signals.start()
        val disconnectPoint = signals.currentLocation()
        var latestPoint = disconnectPoint
        var elapsed = 0L
        var decision: Decision? = null
        try {
            while (decision == null && elapsed < ParkDecisionEngine.TIMEOUT_MS) {
                decision = ParkDecisionEngine.decide(
                    signals.activitySamples(), disconnectPoint, latestPoint, elapsed,
                )
                if (decision == null) {
                    delay(pollIntervalMs)
                    elapsed += pollIntervalMs
                    latestPoint = signals.currentLocation() ?: latestPoint
                }
            }
        } finally {
            signals.stop()
        }

        return when (decision ?: Decision.Unclear) {
            Decision.FalseAlarm -> ParkOutcome.FalseAlarm
            Decision.Unclear -> {
                notifier.askManualDecision()
                ParkOutcome.ManualNeeded
            }
            Decision.ParkedInCar, Decision.ParkedWalkedAway -> {
                stateStore.parked = true
                stateStore.lastParkLocation = latestPoint
                val point = latestPoint
                when {
                    point != null && isInFreeZone(point, freeZones.all()) -> {
                        notifier.statusFreeZone()
                        ParkOutcome.FreeZoneParked
                    }
                    !stateStore.autoClaim -> {
                        notifier.askManualDecision()
                        ParkOutcome.ManualNeeded
                    }
                    else -> claimPermit.claim()
                }
            }
        }
    }
}
