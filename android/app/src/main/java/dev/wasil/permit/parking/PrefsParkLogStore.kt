package dev.wasil.permit.parking

import android.content.SharedPreferences

/**
 * The park log in the same SharedPreferences file every other store uses.
 *
 * Deliberately the shape of [PrefsFreeZoneStore]: read the whole list, apply a
 * pure list operation, write the whole list back. The log is a few hundred
 * short records, so there is nothing here a database would buy, and every
 * decision worth testing lives in `ParkLog.kt` where the JVM can reach it.
 */
class PrefsParkLogStore(private val prefs: SharedPreferences) : ParkLogStore {

    override fun all(): List<ParkRecord> = decodeParkLog(prefs.getString(KEY, null))

    override fun record(record: ParkRecord) = save(all().withPark(record))

    override fun closeOpen(endedAtMs: Long) = save(all().withOpenParkClosed(endedAtMs))

    override fun settleOpen(settlement: Settlement, holder: VehicleId?) =
        save(all().withOpenParkSettled(settlement, holder))

    override fun uncoverOpen() = save(all().withOpenParkUncovered())

    private fun save(records: List<ParkRecord>) {
        prefs.edit().putString(KEY, encodeParkLog(records)).apply()
    }

    private companion object {
        const val KEY = "park_log"
    }
}
