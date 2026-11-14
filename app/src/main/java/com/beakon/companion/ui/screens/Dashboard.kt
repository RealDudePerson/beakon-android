package com.beakon.companion.ui.screens

import com.beakon.companion.net.BeakonUrl
import com.beakon.companion.net.CertificateFingerprint
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The Map tab is the Beakon web dashboard itself, shown in an in-app browser
 * view — not a native map. The dashboard lives at the instance base URL
 * (`https://location2.navettas.com/`), which is also where a successful login
 * redirects, so the root URL is the dashboard.
 *
 * Pure decisions, kept free of WebView types so JVM tests can cover them:
 * which URL to load, and whether a WebView SSL error may be bypassed. The
 * bypass mirrors the app's pinning rule exactly: proceed only when the
 * certificate the server presented matches a SHA-256 fingerprint the user
 * explicitly accepted for this host. Anything else cancels the load.
 */
object Dashboard {

    /**
     * The dashboard URL for an instance, or null when the URL is not usable.
     * Always the bare base URL with a trailing slash: any path the user typed
     * after the host is dropped, so a saved URL like
     * `https://example.com:8443/some/path` still lands on the dashboard.
     */
    fun url(instanceUrl: String?): String? {
        val parsed = instanceUrl?.let { BeakonUrl.normalize(it)?.toHttpUrlOrNull() } ?: return null
        val defaultPort = if (parsed.scheme == "https") 443 else 80
        val authority =
            if (parsed.port == defaultPort) parsed.host
            else "${parsed.host}:${parsed.port}"
        return "${parsed.scheme}://$authority/"
    }

    /**
     * True only when the presented certificate's fingerprint matches the stored
     * pin for this host. A missing pin, a missing presented certificate, or a
     * mismatch all refuse — a pin for the host must never bless a *different*
     * certificate.
     */
    fun shouldProceedOnSslError(presentedSha256: String?, pinnedSha256: String?): Boolean =
        presentedSha256 != null && pinnedSha256 != null &&
            CertificateFingerprint.matches(presentedSha256, pinnedSha256)
}
