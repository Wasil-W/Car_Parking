package dev.wasil.permit.parking

import android.content.SharedPreferences
import dev.wasil.permit.data.api.PermitJson
import kotlinx.serialization.encodeToString

class PrefsFreeZoneStore(private val prefs: SharedPreferences) : FreeZoneStore {
    override fun all(): List<FreeZone> {
        val json = prefs.getString("free_zones", null) ?: return emptyList()
        return runCatching { PermitJson.decodeFromString<List<FreeZone>>(json) }
            .getOrDefault(emptyList())
            // Circles saved before v0.6.8 are dropped rather than shown. They
            // cannot be tested by the new containment rule, so keeping them
            // would put rows in "Your zones" that the claim decision quietly
            // ignores — a zone that says it is free and is not. Filtering on
            // read rather than rewriting on upgrade keeps this one line and one
            // place, and the next write settles the stored list anyway.
            .filter { it.isArea }
    }

    override fun add(zone: FreeZone) = save(all() + zone)

    override fun removeAt(index: Int) = save(all().filterIndexed { i, _ -> i != index })

    override fun updateLabel(index: Int, label: String) =
        save(all().mapIndexed { i, zone -> if (i == index) zone.copy(label = label) else zone })

    override fun replaceAt(index: Int, zone: FreeZone) =
        save(all().mapIndexed { i, existing -> if (i == index) zone else existing })

    private fun save(zones: List<FreeZone>) {
        prefs.edit().putString("free_zones", PermitJson.encodeToString(zones)).apply()
    }
}
