package com.beakon.companion.net

/** Details of a certificate presented by an instance, shown to the user for acceptance. */
data class CertificateInfo(
    val host: String,
    val port: Int,
    val sha256: String,
    val subject: String,
    val issuer: String,
    val notBefore: Long,
    val notAfter: Long,
)
