package dev.wasil.permit.parking

class FakeParkStateStore : ParkStateStore {
    override var carMac: String? = "AA:BB:CC:DD:EE:FF"
    override var carName: String? = "Car stereo"
    override var myCar: MyCar? = MyCar.WASIL
    override var autoClaim: Boolean = true
    override var parked: Boolean = false
    override var lastParkLocation: GeoPoint? = null
}
