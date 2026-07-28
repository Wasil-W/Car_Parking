package dev.wasil.permit.parking

import android.content.SharedPreferences
import dev.wasil.permit.data.api.PermitJson
import kotlinx.serialization.encodeToString

class PrefsFreeZoneStore(private val prefs: SharedPreferences) : FreeZoneStore {
    override fun all(): List<FreeZone> {
        val json = prefs.getString("free_zones", null) ?: return emptyList()
        return runCatching { PermitJson.decodeFromString<List<FreeZone>>(json) }.getOrDefault(emptyList())
    }

    override fun add(zone: FreeZone) = save(all() + zone)

    override fun removeAt(index: Int) = save(all().filterIndexed { i, _ -> i != index })

    private fun save(zones: List<FreeZone>) {
        prefs.edit().putString("free_zones", PermitJson.encodeToString(zones)).apply()
    }
}
