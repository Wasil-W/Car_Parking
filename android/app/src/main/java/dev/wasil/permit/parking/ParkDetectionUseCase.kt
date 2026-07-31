package dev.wasil.permit.parking

import dev.wasil.permit.parking.zones.ZoneInfo
import dev.wasil.permit.parking.zones.ZoneResolver
import kotlinx.coroutines.delay

interface DetectionSignals {
    suspend fun start()
    suspend fun stop()
    fun activitySamples(): List<ActivitySample>
    suspend fun currentLocation(): GeoPoint?
}

interface ParkNotifier {
    fun statusPermitOn(label: String, vrn: String, zoneText: String? = null)
    /** Ongoing status when parked without claiming (home / free zone / free street). */
    fun statusParkedNoClaim(reason: String)
    fun askManualDecision()
    fun askGiveBack(otherLabel: String)
    fun blockedByOther(otherLabel: String, parkedAtMs: Long, heartbeatAtMs: Long)
    fun takeover(byLabel: String)
    fun switchFailed(reason: String?)
    fun mismatchWarning(serverVrn: String?)
    /** One-off dismissible note on the events channel. */
    fun eventNote(text: String)
}

/** Background jobs the use case asks for; implemented with WorkManager. */
interface ParkScheduler {
    fun requestSync()
    fun requestGiveBack()
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
    private val zoneResolver: ZoneResolver,
    private val guardedClaim: GuardedClaim,
    private val notifier: ParkNotifier,
    private val scheduler: ParkScheduler,
    private val pollIntervalMs: Long = 5_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
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
            Decision.ParkedInCar, Decision.ParkedWalkedAway -> confirmedPark(latestPoint)
        }
    }

    private suspend fun confirmedPark(point: GeoPoint?): ParkOutcome {
        stateStore.parked = true
        stateStore.parkedAtMs = nowMs()
        // Only overwrite a known position with a real one. A failed fix means
        // "we don't know where you are now", not "the car is nowhere" — writing
        // null here erased the pin from the map on every park without a fix.
        point?.let { stateStore.lastParkLocation = it }

        if (point == null) {
            // No GPS fix: could be at home for all we know. Never claim blind,
            // never block the other phone on guesswork.
            markNotOutside()
            return askUnlessAlreadyMine()
        }

        val zone = zoneResolver.resolve(point)
        if (zone !is ZoneInfo.Paid) {
            markNotOutside()
            notifier.statusParkedNoClaim(reasonFor(zone))
            // The other car may be waiting for the permit we still hold.
            scheduler.requestGiveBack()
            return ParkOutcome.FreeZoneParked
        }

        stateStore.parkedOutside = true
        stateStore.lastZoneCode = zone.area?.code
        scheduler.requestSync()

        if (!stateStore.autoClaim) {
            return askUnlessAlreadyMine(zoneText(zone))
        }

        return when (val result = guardedClaim.claim(zoneText = zoneText(zone))) {
            is GuardedResult.Blocked -> {
                notifier.blockedByOther(
                    result.otherLabel, result.other.parkedAtMs, result.other.heartbeatAtMs,
                )
                ParkOutcome.ManualNeeded
            }
            is GuardedResult.Done -> {
                if (result.outcome == ParkOutcome.ManualNeeded) {
                    askUnlessAlreadyMine(zoneText(zone))
                } else {
                    if (result.outcome is ParkOutcome.Claimed) scheduler.requestSync()
                    result.outcome
                }
            }
        }
    }

    /**
     * Never ask about a permit that is already on your own car — there is
     * nothing to decide, and being asked anyway is what made the app feel like
     * it was ignoring auto-claim. Falls back to asking when the holder cannot
     * be read, since an unanswerable question beats a wrong assumption.
     */
    private suspend fun askUnlessAlreadyMine(zoneText: String? = null): ParkOutcome {
        val mine = guardedClaim.alreadyMine()
        if (mine != null) {
            val label = stateStore.myCar?.label() ?: return alsoAsk()
            notifier.statusPermitOn(label, mine, zoneText)
            return ParkOutcome.Claimed(mine)
        }
        return alsoAsk()
    }

    private fun alsoAsk(): ParkOutcome {
        notifier.askManualDecision()
        return ParkOutcome.ManualNeeded
    }

    private fun markNotOutside() {
        stateStore.parkedOutside = false
        stateStore.lastZoneCode = null
        scheduler.requestSync()
    }

    private fun reasonFor(zone: ZoneInfo): String = when (zone) {
        ZoneInfo.Home -> "at home"
        is ZoneInfo.ManualFree -> "in a free zone" +
            (zone.label.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        ZoneInfo.FreeStreet -> "free street parking (outside paid zones)"
        is ZoneInfo.Paid -> ""   // unreachable
    }

    private fun zoneText(zone: ZoneInfo.Paid): String =
        zone.area?.let { "${it.tariffText} zone ${it.code}" } ?: "paid area (zone data unavailable)"
}
