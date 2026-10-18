package com.beakon.companion.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The per-user API secret lives only here, encrypted with a Keystore-backed
 * AES-256 key. It is never written to plain preferences or logs.
 */
class SecureSecretStore(context: Context) {

    private val prefs: SharedPreferences = create(context)

    fun secret(): String? = prefs.getString(KEY_SECRET, null)

    fun store(secret: String) {
        prefs.edit().putString(KEY_SECRET, secret).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_SECRET).apply()
    }

    /** Persisted `session` cookie value, so location history survives process death. */
    fun session(): String? = prefs.getString(KEY_SESSION, null)

    fun storeSession(value: String) {
        prefs.edit().putString(KEY_SESSION, value).apply()
    }

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private fun create(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private companion object {
        const val FILE_NAME = "beakon_secrets"
        const val KEY_SECRET = "api_secret"
        const val KEY_SESSION = "session_cookie"
    }
}
