package dev.wasil.permit.data.store

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class EncryptedCredentialStore(context: Context) : CredentialStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "permit_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun load(): PermitConfig? {
        val username = prefs.getString("username", null) ?: return null
        val password = prefs.getString("password", null) ?: return null
        val wasil = prefs.getString("wasil_plate", null) ?: return null
        val walid = prefs.getString("walid_plate", null) ?: return null
        return PermitConfig(username, password, wasil, walid)
    }

    override fun save(config: PermitConfig) {
        prefs.edit()
            .putString("username", config.username)
            .putString("password", config.password)
            .putString("wasil_plate", normalizePlate(config.wasilPlate))
            .putString("walid_plate", normalizePlate(config.walidPlate))
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}
