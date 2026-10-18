package com.beakon.companion.net

import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * SHA-256 fingerprint helpers used for user-driven certificate pinning.
 *
 * The comparison is deliberately tolerant of separator/format differences so a
 * fingerprint copied from `openssl x509 -fingerprint -sha256` can be compared
 * with one computed in-app.
 */
object CertificateFingerprint {

    fun sha256(certificate: X509Certificate): String = sha256(certificate.encoded)

    fun sha256(der: ByteArray): String = format(MessageDigest.getInstance("SHA-256").digest(der))

    /** Formats raw digest bytes as uppercase colon-separated hex, e.g. `AB:CD:...`. */
    fun format(bytes: ByteArray): String =
        bytes.joinToString(separator = ":") { "%02X".format(it) }

    fun normalize(fingerprint: String): String =
        fingerprint.replace(":", "").replace(" ", "").trim().uppercase()

    /** True when [presented] is the same fingerprint as [pinned], ignoring formatting. */
    fun matches(presented: String, pinned: String): Boolean =
        normalize(presented) == normalize(pinned)
}
