package com.beakon.companion.data

import android.content.SharedPreferences

/**
 * Minimal in-memory [SharedPreferences] so [AppPreferences] can be exercised on
 * the JVM. [apply] commits synchronously and notifies listeners, which is what
 * the preference-change listener in [AppPreferences] relies on.
 */
class FakeSharedPreferences : SharedPreferences {

    private val values = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, Any?> = values.toMutableMap()

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listeners.remove(listener)
    }

    private inner class FakeEditor : SharedPreferences.Editor {

        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            put(key, value)

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = put(key, values?.toMutableSet())

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            put(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            removals.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            pending[key] = value
            removals.remove(key)
            return this
        }

        private fun applyChanges() {
            if (clearRequested) values.clear()
            removals.forEach { values.remove(it) }
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            val changed = pending.keys + removals
            changed.forEach { key ->
                listeners.toList().forEach { it.onSharedPreferenceChanged(this@FakeSharedPreferences, key) }
            }
        }
    }
}
