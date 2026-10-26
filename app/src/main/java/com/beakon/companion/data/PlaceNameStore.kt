package com.beakon.companion.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent cache for reverse-geocoded place names, keyed by
 * [com.beakon.companion.ui.geocodeCacheKey] (coordinates rounded to the
 * displayed 5 decimals). Place names are not sensitive, so plain
 * SharedPreferences is enough — unlike the API secret, which lives in
 * [SecureSecretStore].
 *
 * The cache is deliberately capped: history grows one point per report
 * interval, and an unbounded preferences file would eventually slow
 * every app start that reads it. Once the cap is reached, new names
 * are simply not saved; names already cached keep working.
 */
interface PlaceNameCache {
    fun loadAll(): Map<String, String>
    fun save(key: String, name: String)
}

/** In-memory [PlaceNameCache], for tests and for ViewModels without storage. */
class InMemoryPlaceNameCache : PlaceNameCache {
    private val names = mutableMapOf<String, String>()
    override fun loadAll(): Map<String, String> = names.toMap()
    override fun save(key: String, name: String) {
        names[key] = name
    }
}

class PlaceNameStore(
    private val prefs: SharedPreferences,
) : PlaceNameCache {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
    )

    override fun loadAll(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) ->
            if (value is String && value.isNotBlank()) key to value else null
        }.toMap()

    override fun save(key: String, name: String) {
        if (!prefs.contains(key) && loadAll().size >= MAX_ENTRIES) return
        prefs.edit().putString(key, name).apply()
    }

    companion object {
        private const val FILE_NAME = "beakon_place_names"
        private const val MAX_ENTRIES = 2000
    }
}
