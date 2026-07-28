package dev.wasil.permit.parking

enum class MyCar { WASIL, WALID }

interface ParkStateStore {
    var carMac: String?
    var carName: String?
    var myCar: MyCar?
    var autoClaim: Boolean
    var parked: Boolean
    var lastParkLocation: GeoPoint?
}
