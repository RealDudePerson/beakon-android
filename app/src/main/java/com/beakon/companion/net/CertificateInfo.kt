package com.beakon.companion.net

/** Details of a certificate presented by an instance, shown to the user for inspection. */
data class CertificateInfo(
    val host: String,
    val port: Int,
    val sha256: String,
    val subject: String,
    val issuer: String,
    val notBefore: Long,
    val notAfter: Long,
    /**
     * True when the platform/default trust manager accepts this certificate's
     * chain. When true the certificate needs no fingerprint: the UI must not
     * offer to pin it.
     */
    val trustedByPlatform: Boolean = false,
)
