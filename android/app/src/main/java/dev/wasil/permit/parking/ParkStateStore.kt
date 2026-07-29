package dev.wasil.permit.parking

enum class MyCar { WASIL, WALID }

interface ParkStateStore {
    var carMac: String?
    var carName: String?
    var myCar: MyCar?
    var autoClaim: Boolean
    var parked: Boolean
    var lastParkLocation: GeoPoint?

    /** True only when confirmed parked in a PAID zone — the state that blocks the other phone. */
    var parkedOutside: Boolean
    var parkedAtMs: Long
    var lastZoneCode: String?
    var homeZone: FreeZone?
    var syncUrl: String?
    /** claimedAtMs of the last permit takeover we already alerted about. */
    var lastAlertedClaimMs: Long
}
