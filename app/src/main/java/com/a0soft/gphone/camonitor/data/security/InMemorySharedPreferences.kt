package com.a0soft.gphone.camonitor.data.security

import android.content.SharedPreferences

/**
 * Non-persistent [SharedPreferences] used when the encrypted store cannot be opened at all.
 * Keys and pairings live only for the current process and are never written to disk.
 */
class InMemorySharedPreferences : SharedPreferences {
    private val data = HashMap<String, Any?>()
    private val lock = Any()

    override fun getAll(): MutableMap<String, *> = synchronized(lock) { HashMap(data) }
    override fun getString(key: String, defValue: String?): String? =
        synchronized(lock) { (data[key] as? String) ?: defValue }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(lock) { (data[key] as? Set<String>)?.toMutableSet() ?: defValues }
    override fun getInt(key: String, defValue: Int): Int = synchronized(lock) { data[key] as? Int ?: defValue }
    override fun getLong(key: String, defValue: Long): Long = synchronized(lock) { data[key] as? Long ?: defValue }
    override fun getFloat(key: String, defValue: Float): Float = synchronized(lock) { data[key] as? Float ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        synchronized(lock) { data[key] as? Boolean ?: defValue }
    override fun contains(key: String): Boolean = synchronized(lock) { data.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) =
            apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removals.add(key) }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            synchronized(lock) {
                if (clear) data.clear()
                removals.forEach { data.remove(it) }
                pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }

    // Change listeners are never used by SecurityRepository.
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {}

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {}
}
