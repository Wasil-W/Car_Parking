package dev.wasil.permit.parking

class FakeFreeZoneStore(val zones: MutableList<FreeZone> = mutableListOf()) : FreeZoneStore {
    override fun all(): List<FreeZone> = zones.toList()
    override fun add(zone: FreeZone) { zones += zone }
    override fun removeAt(index: Int) { zones.removeAt(index) }
    override fun updateLabel(index: Int, label: String) { zones[index] = zones[index].copy(label = label) }
}
