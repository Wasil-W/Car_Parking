package dev.wasil.permit.parking

import android.content.Context
import android.content.SharedPreferences
import dev.wasil.permit.data.api.PermitJson
import kotlinx.serialization.encodeToString

class PrefsParkStateStore(private val prefs: SharedPreferences) : ParkStateStore {

    companion object {
        fun from(context: Context): PrefsParkStateStore =
            PrefsParkStateStore(context.getSharedPreferences("park_state", Context.MODE_PRIVATE))
    }

    override var carMac: String?
        get() = prefs.getString("car_mac", null)
        set(value) { prefs.edit().putString("car_mac", value).apply() }

    override var carName: String?
        get() = prefs.getString("car_name", null)
        set(value) { prefs.edit().putString("car_name", value).apply() }

    override var myCar: MyCar?
        get() = prefs.getString("my_car", null)?.let { runCatching { MyCar.valueOf(it) }.getOrNull() }
        set(value) { prefs.edit().putString("my_car", value?.name).apply() }

    override var autoClaim: Boolean
        get() = prefs.getBoolean("auto_claim", true)
        set(value) { prefs.edit().putBoolean("auto_claim", value).apply() }

    override var parked: Boolean
        get() = prefs.getBoolean("parked", false)
        set(value) { prefs.edit().putBoolean("parked", value).apply() }

    override var lastParkLocation: GeoPoint?
        get() {
            val lat = prefs.getString("last_lat", null)?.toDoubleOrNull() ?: return null
            val lng = prefs.getString("last_lng", null)?.toDoubleOrNull() ?: return null
            return GeoPoint(lat, lng, prefs.getFloat("last_acc", 0f))
        }
        set(value) {
            prefs.edit()
                .putString("last_lat", value?.lat?.toString())
                .putString("last_lng", value?.lng?.toString())
                .putFloat("last_acc", value?.accuracyM ?: 0f)
                .apply()
        }

    override var parkedOutside: Boolean
        get() = prefs.getBoolean("parked_outside", false)
        set(value) { prefs.edit().putBoolean("parked_outside", value).apply() }

    override var parkedAtMs: Long
        get() = prefs.getLong("parked_at_ms", 0L)
        set(value) { prefs.edit().putLong("parked_at_ms", value).apply() }

    override var lastZoneCode: String?
        get() = prefs.getString("last_zone_code", null)
        set(value) { prefs.edit().putString("last_zone_code", value).apply() }

    override var homeZone: FreeZone?
        get() = prefs.getString("home_zone", null)?.let {
            runCatching { PermitJson.decodeFromString<FreeZone>(it) }.getOrNull()
        }
        set(value) {
            prefs.edit().putString("home_zone", value?.let { PermitJson.encodeToString(it) }).apply()
        }

    override var syncUrl: String?
        get() = prefs.getString("sync_url", null)
        set(value) { prefs.edit().putString("sync_url", value).apply() }

    override var lastAlertedClaimMs: Long
        get() = prefs.getLong("last_alerted_claim_ms", 0L)
        set(value) { prefs.edit().putLong("last_alerted_claim_ms", value).apply() }

    // Stored as plain Strings/Longs, never a serialized blob: the encode/decode
    // between these primitives and PendingDecision is a pure function
    // (PendingDecision.kt) so the round-trip is unit-testable without Android.
    override var pendingDecision: PendingDecision?
        get() = PendingDecisionRecord(
            kind = prefs.getString("pending_decision_kind", null),
            label = prefs.getString("pending_decision_label", null),
            parkedAtMs = prefs.getLong("pending_decision_parked_at_ms", 0L),
            heartbeatAtMs = prefs.getLong("pending_decision_heartbeat_at_ms", 0L),
            raisedAtMs = prefs.getLong("pending_decision_raised_at_ms", 0L),
        ).toDecision()
        set(value) {
            val record = value?.toRecord()
            prefs.edit()
                .putString("pending_decision_kind", record?.kind)
                .putString("pending_decision_label", record?.label)
                .putLong("pending_decision_parked_at_ms", record?.parkedAtMs ?: 0L)
                .putLong("pending_decision_heartbeat_at_ms", record?.heartbeatAtMs ?: 0L)
                .putLong("pending_decision_raised_at_ms", record?.raisedAtMs ?: 0L)
                .apply()
        }
}
