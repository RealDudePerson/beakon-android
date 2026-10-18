package com.beakon.companion.net

import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CertificateFingerprintTest {

    @Test
    fun `matching fingerprint is accepted regardless of formatting`() {
        val fingerprint = CertificateFingerprint.sha256(byteArrayOf(1, 2, 3, 4, 5))

        assertTrue(CertificateFingerprint.matches(fingerprint, fingerprint))
        assertTrue(CertificateFingerprint.matches(fingerprint.lowercase(), fingerprint))
        assertTrue(CertificateFingerprint.matches(fingerprint.replace(":", ""), fingerprint))
        assertTrue(CertificateFingerprint.matches("  $fingerprint  ", fingerprint))
    }

    @Test
    fun `changed fingerprint is rejected`() {
        val original = CertificateFingerprint.sha256(byteArrayOf(1, 2, 3))
        val changed = CertificateFingerprint.sha256(byteArrayOf(1, 2, 4))

        assertNotEquals(original, changed)
        assertFalse(CertificateFingerprint.matches(changed, original))
        assertFalse(CertificateFingerprint.matches(original, changed))
    }

    @Test
    fun `single character difference is rejected`() {
        val original = CertificateFingerprint.sha256(byteArrayOf(9, 8, 7, 6))
        val tampered = original.dropLast(1) + if (original.last() == 'A') "B" else "A"

        assertFalse(CertificateFingerprint.matches(tampered, original))
    }

    @Test
    fun `format is uppercase colon-separated hex`() {
        val formatted = CertificateFingerprint.format(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x0F))

        assertEquals("AB:CD:0F", formatted)
    }

    @Test
    fun `the checked-in mock certificate has a stable fingerprint`() {
        val root = System.getProperty("beakon.repoRoot")?.let(::File)
        assumeTrue("repo root property not set", root != null)
        val certFile = File(root, "mock/cert.pem")
        assumeTrue("mock certificate not present", certFile.exists())

        val certificate = certFile.inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val fingerprint = CertificateFingerprint.sha256(certificate)

        assertTrue(CertificateFingerprint.matches(fingerprint, fingerprint))
        val tampered = fingerprint.dropLast(1) + if (fingerprint.last() == 'A') "B" else "A"
        assertFalse(CertificateFingerprint.matches(tampered, fingerprint))
    }
}
