package dev.wasil.permit.parking

interface ParkStateStore {
    var carMac: String?
    var carName: String?

    /**
     * Which car in the roster this phone drives away in.
     *
     * `myCar: MyCar?` until v0.6.6, and it is the most important single field in
     * the app. Two properties it must keep, both from `USER-MODEL.md`:
     *
     * - **Device-local.** It is never written to the shared room and never
     *   inferable from it. The room stores state per car key already; it does
     *   not need to know which handset is holding which.
     * - **Changeable.** Phones get replaced and cars get swapped.
     *
     * Null means setup has not been done. Detection stops there — a park could
     * not be attributed to anything — which is why this is the one question
     * first run still asks.
     */
    var thisPhoneDrives: VehicleId?

    var autoClaim: Boolean
    var parked: Boolean
    var lastParkLocation: GeoPoint?

    /**
     * Where *detection* put the car, before any hand correction.
     *
     * Kept apart from [lastParkLocation] so the correction distance cap has a
     * fixed anchor. Measuring from the current pin let a correction be
     * confirmed and then corrected again, 300 m at a time, walking the car
     * anywhere on the map — a cap that resets is not a cap.
     */
    var detectedParkLocation: GeoPoint?

    /** True only when confirmed parked in a PAID zone — the state that blocks the other phone. */
    var parkedOutside: Boolean

    /**
     * Whether [parkedOutside] is a finding or a leftover.
     *
     * False means the last detection ended without ever resolving a zone — no
     * fix, nothing in the trail — so [parkedOutside] is simply whatever it was
     * before and stands for nothing. It used to be written `false` in that case,
     * which told the other phone "my car is not on a paid street, help
     * yourself", on the strength of a failed GPS read. Their car may well have
     * been on a paid street; that is the expensive direction to be wrong in.
     *
     * Read by the shared-state write so the other phone can be told the state is
     * unknown rather than be given a guess dressed as a fact.
     */
    var parkedOutsideKnown: Boolean

    var parkedAtMs: Long
    var lastZoneCode: String?

    /**
     * True between the car's Bluetooth connecting and disconnecting — i.e.
     * while driving.
     *
     * Not a parked state and not the opposite of one: it says the phone is with
     * the car, nothing more. Its job is to gate [liveLocation], which may only
     * be sealed into something actionable once this has gone false.
     */
    var carLinkConnected: Boolean

    /**
     * The most recent position sampled while [carLinkConnected] — the drive in
     * progress, never a parking spot.
     *
     * Typed rather than stored as a bare [GeoPoint] on purpose: a [GeoPoint]
     * here would be indistinguishable from the parked one two fields up, and
     * the entire promise is that these two can never be confused. See
     * [LiveLocation].
     */
    var liveLocation: LiveLocation?

    var homeZone: FreeZone?
    var syncUrl: String?
    /** claimedAtMs of the last permit takeover we already alerted about. */
    var lastAlertedClaimMs: Long

    /**
     * When the takeover watch last read the shared permit node.
     *
     * Only the drive-time watch uses it. The live-location sampler wakes every
     * 20 s while the car's Bluetooth is up, and reading the permit on every one
     * of those would be ninety network calls for a half-hour drive; this is
     * what throttles it to [TAKEOVER_CHECK_INTERVAL_MS]. Stored rather than
     * held in memory because each poll is a fresh worker with no memory of the
     * last one.
     */
    var lastTakeoverCheckMs: Long
    /**
     * The one decision currently waiting on the user, if any. Raised alongside
     * a notification, read by the tappable-notification screen, cleared when
     * acted on. See [currentPendingDecision] for the expiry rule.
     */
    var pendingDecision: PendingDecision?
}
