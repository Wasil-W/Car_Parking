package dev.wasil.permit.parking

class FakeParkStateStore : ParkStateStore {
    override var carMac: String? = "AA:BB:CC:DD:EE:FF"
    override var carName: String? = "Car stereo"
    override var thisPhoneDrives: VehicleId? = slotIdFor(0)
    override var autoClaim: Boolean = true
    override var parked: Boolean = false
    override var lastParkLocation: GeoPoint? = null
    override var detectedParkLocation: GeoPoint? = null
    override var parkedOutside: Boolean = false
    override var parkedOutsideKnown: Boolean = true
    override var parkedAtMs: Long = 0
    override var lastZoneCode: String? = null
    override var carLinkConnected: Boolean = false
    override var liveLocation: LiveLocation? = null
    override var lastKnownHolderVrn: String? = null
    override var lastKnownHolderAtMs: Long = 0
    override var homeZone: FreeZone? = null
    override var syncUrl: String? = null
    override var lastAlertedClaimMs: Long = 0
    override var lastTakeoverCheckMs: Long = 0
    override var pendingDecision: PendingDecision? = null
}
