package dev.wasil.permit.parking

import dev.wasil.permit.parking.shared.PhoneState
import dev.wasil.permit.parking.zones.TariffArea
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
    /**
     * [identitySlot] is the roster position the notification icon's dot should
     * sit at — 0 left, 1 right, null centred. It travels separately from
     * [carName] because the two used to be the same thing: the notifier was
     * handed a label and turned it back into a car by comparing it against the
     * literal strings "Wasil" and "Walid", which mapped anything unrecognised
     * onto Walid and lit the wrong brother's icon. A name is now text and
     * nothing else.
     */
    fun statusPermitOn(carName: String, identitySlot: Int?, vrn: String, zoneText: String? = null)
    /** Ongoing status when parked without claiming (home / free zone / free street). */
    fun statusParkedNoClaim(reason: String)
    fun askManualDecision()
    fun askGiveBack(otherLabel: String)

    /**
     * Takes the whole [PhoneState] rather than two timestamps out of it,
     * because what the user is told has to branch on
     * [dev.wasil.permit.parking.shared.PhoneState.parkedOutsideKnown] — the
     * guard now blocks on an *unknown* park as well as a known one, and the two
     * are not the same sentence.
     */
    fun blockedByOther(otherLabel: String, other: PhoneState)
    fun takeover(byName: String, identitySlot: Int?)
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

/**
 * Names the spot for the history record, or returns null when it cannot.
 *
 * A lambda rather than a dependency because the only implementation needs a
 * `Context` (Android's `Geocoder`), and this class is unit-tested on the JVM.
 * Defaults to "no name", which is also what a failed lookup produces — a park
 * with no place name still happened.
 */
typealias PlaceNamer = suspend (GeoPoint) -> String?

class ParkDetectionUseCase(
    private val signals: DetectionSignals,
    private val stateStore: ParkStateStore,
    private val zoneResolver: ZoneResolver,
    private val guardedClaim: GuardedClaim,
    private val notifier: ParkNotifier,
    private val scheduler: ParkScheduler,
    private val pollIntervalMs: Long = 5_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * Where a confirmed park is written. Null in tests that predate the log and
     * on any path that has no store to hand — the detection loop's job is not
     * conditional on being able to record it.
     */
    private val parkLog: ParkLogStore? = null,
    private val namePlace: PlaceNamer = { null },
    /**
     * What the area charges *at this moment* — "€3,01/h · until 19:00" — for
     * the record's cost column. Injected rather than computed here because the
     * one function that phrases it lives in the UI layer alongside the map
     * header that already shows the same line, and the two must not drift.
     * Defaults to the area's headline rate, which is what an area carries when
     * no live schedule can be read.
     */
    private val rateNow: (TariffArea) -> String? = { it.tariffText.ifBlank { null } },
) {
    suspend fun run(): ParkOutcome {
        if (stateStore.thisPhoneDrives == null) return ParkOutcome.NotConfigured

        signals.start()
        val disconnectPoint = signals.currentLocation()
        var latestPoint = disconnectPoint
        var elapsed = 0L
        var decision: Decision? = null
        try {
            while (decision == null && elapsed < ParkDecisionEngine.TIMEOUT_MS) {
                decision = ParkDecisionEngine.decide(
                    signals.activitySamples(), disconnectPoint, latestPoint, elapsed,
                    // Re-read every pass rather than captured once. The whole
                    // point is that it can change *underneath* a run in
                    // progress: CarBluetoothReceiver writes it the moment the
                    // stereo comes back, and until v0.6.8 this loop was the one
                    // part of the app that never looked.
                    carLinkBackUp = stateStore.carLinkConnected,
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

        // Checked once more after the loop, and it is not belt-and-braces. The
        // link can come back between the last poll and this line, and a decision
        // reached a moment before that is a decision about a car that is now
        // being driven. Nothing below this point re-reads it.
        if (stateStore.carLinkConnected) return ParkOutcome.FalseAlarm

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
     * [sealAtDisconnect] is what makes **the fallback** safe rather than merely
     * useful: it returns null while the link is still up, so a sampler that
     * fires late yields nothing here instead of feeding a driving position into
     * the claim path.
     *
     * **It only ever guarded the fallback, and the comment here used to claim
     * more.** It said a blip that reconnects mid-detection was covered too, and
     * that was false in the one case it mattered: the seal is reached only when
     * [latestPoint] is null, and at a traffic light in the open the GPS answers
     * perfectly well. A live fix took the first branch and was never checked
     * against the link at all — which is how a park got recorded at a red light.
     * The link is now read by [run], before anything reaches here, and a run
     * that finds it back up ends as a false alarm rather than arriving with a
     * position it should not be holding.
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
        val startedAtMs = nowMs()
        val point = fix?.point
        stateStore.parked = true
        stateStore.parkedAtMs = startedAtMs
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

        // Resolved once and held, because the history record must be badged
        // with the same answer the permit decision was made on. Re-resolving
        // later could disagree with it — a zone edited in between is enough.
        val zone = point?.let { zoneResolver.resolve(it) }
        val outcome = settleConfirmedPark(point, zone)
        recordPark(startedAtMs, point, zone, outcome)
        return outcome
    }

    /** Everything the park does to the world, minus the record it leaves behind. */
    private suspend fun settleConfirmedPark(point: GeoPoint?, zone: ZoneInfo?): ParkOutcome {
        if (point == null || zone == null) {
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
                notifier.blockedByOther(result.otherLabel, result.other)
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
        val plate = guardedClaim.alreadyMine()
        if (plate != null) {
            val car = guardedClaim.myVehicle() ?: return alsoAsk()
            notifier.statusPermitOn(car.name, identitySlot(car.id), plate, zoneText)
            return ParkOutcome.Claimed(plate)
        }
        return alsoAsk()
    }

    /**
     * Which identity colour this car carries, from the roster rather than from
     * its name. Null when the roster cannot say, which the notification icon
     * renders as a centred dot rather than as a guess at a brother.
     */
    private fun identitySlot(id: VehicleId): Int? = guardedClaim.roster().identitySlotOf(id)

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
    /**
     * Leaves the record of a park that has just been decided.
     *
     * Written here, at the one point the app is certain a park happened, and
     * never anywhere else — the log must not acquire a second author who could
     * disagree with this one about what settled the spot. Nothing is
     * reconstructed for parks that happened before this shipped; a log that
     * invents its own past is worse than a short one.
     *
     * The geocode is best-effort and deliberately last: a name is a nicety, and
     * a lookup that hangs or throws must not cost the record itself.
     */
    private suspend fun recordPark(
        startedAtMs: Long,
        point: GeoPoint?,
        zone: ZoneInfo?,
        outcome: ParkOutcome,
    ) {
        val log = parkLog ?: return
        val settlement = settlementFor(zone, outcome)
        val place = point?.let { runCatching { namePlace(it) }.getOrNull() }
        log.record(
            ParkRecord(
                startedAtMs = startedAtMs,
                settlement = settlement,
                holder = stateStore.thisPhoneDrives.takeIf { settlement == Settlement.PERMIT },
                place = place,
                rateText = (zone as? ZoneInfo.Paid)?.area?.let(rateNow),
                // Null, not false, when no zone resolved — the record has to be
                // able to say "we could not tell" as distinctly as it says "no".
                paid = zone?.let { it is ZoneInfo.Paid },
            ),
        )
    }

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

/**
 * Which badge a finished park earns.
 *
 * The claim wins outright and is checked first: if the permit ended up on this
 * car, it covered the spot whether or not the zone lookup succeeded. Below that
 * the zone decides, and the last two rows are the pair this app keeps insisting
 * on — a paid spot nothing covered is [Settlement.UNSETTLED], which is a
 * finding, while no zone at all is [Settlement.UNKNOWN], which is a gap. They
 * must never collapse into each other: "we could not tell" is not "you owed
 * nothing".
 */
fun settlementFor(zone: ZoneInfo?, outcome: ParkOutcome): Settlement = when {
    outcome is ParkOutcome.Claimed -> Settlement.PERMIT
    zone is ZoneInfo.Home -> Settlement.HOME
    zone is ZoneInfo.ManualFree -> Settlement.FREE_ZONE
    zone is ZoneInfo.FreeStreet -> Settlement.FREE_STREET
    zone is ZoneInfo.Paid -> Settlement.UNSETTLED
    else -> Settlement.UNKNOWN
}
