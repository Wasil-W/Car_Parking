package dev.wasil.permit.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.wasil.permit.data.api.PermitKind
import dev.wasil.permit.data.api.PermitJson
import dev.wasil.permit.parking.Roster
import dev.wasil.permit.parking.Vehicle
import dev.wasil.permit.parking.legacyRoster
import kotlinx.serialization.encodeToString

class EncryptedCredentialStore(context: Context) : CredentialStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "permit_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun load(): PermitConfig? = loadFrom(prefs)

    override fun save(config: PermitConfig) {
        prefs.edit()
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_ROSTER, PermitJson.encodeToString(config.roster.vehicles))
            .putString(KEY_PERMIT_KIND, config.permitKind.name)
            // The pair this replaced. Removed rather than left lying around, so
            // there can never be two answers to "which plate is Walid's" — the
            // migration below reads them only while the roster key is absent.
            .remove(KEY_LEGACY_WASIL_PLATE)
            .remove(KEY_LEGACY_WALID_PLATE)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_ROSTER = "roster"
        private const val KEY_PERMIT_KIND = "permit_kind"
        private const val KEY_LEGACY_WASIL_PLATE = "wasil_plate"
        private const val KEY_LEGACY_WALID_PLATE = "walid_plate"

        /**
         * Reading a stored config, including one written before the roster
         * existed.
         *
         * Pure over a [SharedPreferences], so the migration is exercised by a
         * JVM test against a fake rather than only by whichever install happens
         * to be upgraded first. There is no version counter: the presence of
         * the roster key is the version, which is enough for one step and
         * cannot be got wrong.
         *
         * The legacy branch keeps the two plates in their stored order — Wasil
         * first — rather than sorting them. Slot order is identity order and
         * it is also the Firebase node layout both phones are already paired
         * under; a migration that reshuffled it would swap the two brothers'
         * colours and point each phone's writes at the other's node.
         */
        fun loadFrom(prefs: SharedPreferences): PermitConfig? {
            val username = prefs.getString(KEY_USERNAME, null) ?: return null
            val password = prefs.getString(KEY_PASSWORD, null) ?: return null
            val roster = prefs.getString(KEY_ROSTER, null)?.let(::decodeRoster)
                ?: legacyRosterIn(prefs)
                ?: return null
            val kind = prefs.getString(KEY_PERMIT_KIND, null)
                ?.let { name -> runCatching { PermitKind.valueOf(name) }.getOrNull() }
                ?: PermitKind.UNKNOWN
            return PermitConfig(username, password, roster, kind)
        }

        private fun legacyRosterIn(prefs: SharedPreferences): Roster? {
            val wasil = prefs.getString(KEY_LEGACY_WASIL_PLATE, null) ?: return null
            val walid = prefs.getString(KEY_LEGACY_WALID_PLATE, null) ?: return null
            return legacyRoster(wasil, walid)
        }

        /**
         * An unreadable roster reads as "no permit stored" rather than as an
         * empty one, and that is the safe direction: no permit shows the
         * obligation half and moves nothing, whereas a roster of guesses could
         * move the permit onto a plate nobody chose.
         */
        private fun decodeRoster(raw: String): Roster? = runCatching {
            Roster.of(PermitJson.decodeFromString<List<Vehicle>>(raw))
        }.getOrNull()
    }
}
