package com.beakon.companion.data

import android.content.Context
import com.beakon.companion.net.CertificateFingerprint

/**
 * Stores accepted certificate fingerprints, scoped to the instance host, in
 * app-private storage. Plain prefs are fine: a fingerprint is not a secret and
 * storing it in the clear makes it auditable.
 */
class FingerprintStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("beakon_fingerprints", Context.MODE_PRIVATE)

    fun pinFor(host: String): String? = prefs.getString(key(host), null)

    fun store(host: String, sha256: String) {
        prefs.edit().putString(key(host), CertificateFingerprint.normalize(sha256)).apply()
    }

    fun clear(host: String) {
        prefs.edit().remove(key(host)).apply()
    }

    private fun key(host: String) = "sha256:$host"
}
