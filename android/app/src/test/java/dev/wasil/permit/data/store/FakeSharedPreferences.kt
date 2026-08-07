package dev.wasil.permit.data.store

import android.content.SharedPreferences

/**
 * A SharedPreferences that lives in a map.
 *
 * Here so the two upgrade paths in this release can be tested against **stores
 * that start in the old shape**, on the JVM, rather than being discovered on
 * the first phone that installs over v0.6.6. Both migrations are the kind that
 * fails silently and expensively: one decides which Firebase node a phone
 * writes to, the other decides whether a stored permit is found at all.
 *
 * Implements the interface rather than mocking it, so the code under test is
 * the real [dev.wasil.permit.parking.PrefsParkStateStore] and the real
 * [EncryptedCredentialStore.loadFrom] — not a re-implementation of them that
 * could agree with the test and disagree with the phone.
 */
class FakeSharedPreferences(initial: Map<String, Any?> = emptyMap()) : SharedPreferences {

    val values: MutableMap<String, Any?> = initial.toMutableMap()

    override fun getAll(): MutableMap<String, *> = values

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (values[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    /** Writes straight through, which is all the real store's `apply()` promises anyway. */
    private inner class Editor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = set(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = set(key, values)
        override fun putInt(key: String?, value: Int) = set(key, value)
        override fun putLong(key: String?, value: Long) = set(key, value)
        override fun putFloat(key: String?, value: Float) = set(key, value)
        override fun putBoolean(key: String?, value: Boolean) = set(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            values.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            values.clear()
            return this
        }

        override fun commit(): Boolean = true
        override fun apply() = Unit

        private fun set(key: String?, value: Any?): SharedPreferences.Editor {
            if (key == null) return this
            // A null value is a removal, exactly as the platform treats it —
            // otherwise `putString(k, null)` would leave a null sitting where a
            // later getString would find it and hand back null anyway.
            if (value == null) values.remove(key) else values[key] = value
            return this
        }
    }
}
