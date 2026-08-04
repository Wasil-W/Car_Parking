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

        val fix = parkedFix(latestPoint)

        return when (decision ?: Decision.Unclear) {
            Decision.FalseAlarm -> ParkOutcome.FalseAlarm
            Decision.Unclear -> unclearPark(fix)
            Decision.ParkedInCar, Decision.ParkedWalkedAway -> confirmedPark(fix)
        }
    }

    /**
     * The one position this run is allowed to act on, or null if there isn't
     * one.
     *
     * A fix taken now wins outright — it is the parking spot itself. The
     * fallback is the last position sampled during the drive, and it is the
     * reason this release exists: in a garage, or after the phone has sat
     * still, [DetectionSignals.currentLocation] returns nothing at all, and
     * with nothing the app could not tell home from a paid street so it asked.
     * Home is exactly where a fix fails most, which is why the question kept
     * arriving where it was least wanted.
     *
     * [sealAtDisconnect] is what makes the fallback safe rather than merely
     * useful: it returns null while the link is still up, so a sampler that
     * fires late — or a Bluetooth blip that reconnects mid-detection — yields
     * nothing here instead of feeding a driving position into the claim path.
     */
    private fun parkedFix(latestPoint: GeoPoint?): ParkedFix? =
        latestPoint?.let { ParkedFix.atDisconnect(it) }
            ?: sealAtDisconnect(
                connected = stateStore.carLinkConnected,
                last = stateStore.liveLocation,
                nowMs = nowMs(),
            )

    /**
     * Ninety seconds went by without the signals saying anything either way.
     *
     * Asking used to be the whole answer, and it was wrong in a specific way:
     * weak activity signals mean the phone is indoors, and indoors correlates
     * with being at home — so the prompt fired hardest in the one place where
     * there is nothing to decide. Now that a position usually survives the
     * drive, the app can look before it asks: if the spot is not a paid one,
     * there is no permit question to put to anyone, and it says so quietly
     * instead.
     *
     * Only ever the quiet direction. A paid zone, or no position at all, still
     * asks — "unclear" is not evidence that a claim is wanted, and this must
     * never become a path that claims on its own.
     */
    private suspend fun unclearPark(fix: ParkedFix?): ParkOutcome {
        val zone = fix?.let { zoneResolver.resolve(it.point) }
        if (zone != null && zone !is ZoneInfo.Paid) return confirmedPark(fix)
        notifier.askManualDecision()
        return ParkOutcome.ManualNeeded
    }

    private suspend fun confirmedPark(fix: ParkedFix?): ParkOutcome {
        val point = fix?.point
        stateStore.parked = true
        stateStore.parkedAtMs = nowMs()
        // Only overwrite a known position with a real one. A failed fix means
        // "we don't know where you are now", not "the car is nowhere" — writing
        // null here erased the pin from the map on every park without a fix.
        point?.let {
            stateStore.lastParkLocation = it
            // The anchor a hand correction is measured against. Written only
            // here, never by a correction, so the 300 m cap cannot be reset by
            // confirming one move and starting another.
            stateStore.detectedParkLocation = it
        }

        if (point == null) {
            // No fix now and nothing usable from the drive: we do not know
            // which zone this is. Never claim blind — but equally, never
            // *publish* a guess. This used to write parkedOutside = false,
            // which reads on the other phone as "my car is not on a paid
            // street, the permit is yours if you want it" — on the strength of
            // a failed GPS read. Their car may be on a paid street, and that is
            // the direction that costs a fine. So the last published value is
            // left standing and the state is marked unknown instead; the sync
            // still runs so the other phone learns the difference between a
            // finding and a gap.
            stateStore.parkedOutsideKnown = false
            scheduler.requestSync()
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
        stateStore.parkedOutsideKnown = true
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

    /**
     * Only ever called with a resolved zone in hand, which is what makes the
     * `parkedOutsideKnown = true` here honest: this is a finding about a known
     * position, not the absence of one. The no-fix branch above deliberately
     * does not come through here.
     */
    private fun markNotOutside() {
        stateStore.parkedOutside = false
        stateStore.parkedOutsideKnown = true
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
