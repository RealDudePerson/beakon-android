package com.beakon.companion.net

import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Proves the trust ordering required by the platform-trust work:
 *
 *  - the platform/default trust manager is consulted FIRST, and when it accepts
 *    the chain the fingerprint is never consulted (so a CA-signed certificate
 *    needs no pin and survives renewal);
 *  - a certificate the platform rejects is refused unless it matches a
 *    fingerprint the user explicitly accepted;
 *  - a non-matching fingerprint is refused with an actionable message.
 *
 * There is no trust-all path to prove against, and none is added.
 */
class PlatformTrustTest {

    @Test
    fun `a platform-trusted certificate is accepted without consulting the pin`() {
        val delegate = RecordingTrustManager(accept = true)
        var pinConsulted = false
        val manager = PinningTrustManager(
            pinnedSha256 = "AA:BB:CC:DD",
            delegate = delegate,
            fingerprintMatches = { _, _ ->
                pinConsulted = true
                true
            },
        )

        manager.checkServerTrusted(arrayOf(mockCertificate()), "RSA")

        assertEquals("the platform trust manager must be consulted exactly once", 1, delegate.calls)
        assertFalse(
            "the fingerprint must NOT be consulted when the platform trusts the chain",
            pinConsulted,
        )
    }

    @Test
    fun `an untrusted certificate with no accepted fingerprint is refused`() {
        val manager = PinningTrustManager(
            pinnedSha256 = null,
            delegate = RecordingTrustManager(accept = false),
        )

        val failure = assertThrows(UntrustedCertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(mockCertificate()), "RSA")
        }
        assertTrue(
            "the refusal must name the untrusted chain, was: ${failure.message}",
            failure.message!!.contains("not trusted by this device"),
        )
    }

    @Test
    fun `an untrusted certificate is accepted only by an exactly matching fingerprint`() {
        val certificate = mockCertificate()
        val manager = PinningTrustManager(
            pinnedSha256 = CertificateFingerprint.sha256(certificate),
            delegate = RecordingTrustManager(accept = false),
        )

        // The platform rejects, but the fingerprint matches: this is the one and
        // only way an untrusted certificate is accepted.
        manager.checkServerTrusted(arrayOf(certificate), "RSA")
    }

    @Test
    fun `a wrong fingerprint is refused with an actionable message`() {
        val manager = PinningTrustManager(
            pinnedSha256 = "00:11:22:33",
            delegate = RecordingTrustManager(accept = false),
        )

        val failure = assertThrows(UntrustedCertificateException::class.java) {
            manager.checkServerTrusted(arrayOf(mockCertificate()), "RSA")
        }
        assertTrue(
            "the message must say the device does not trust it, was: ${failure.message}",
            failure.message!!.contains("not trusted by this device"),
        )
        assertTrue(
            "the message must say the fingerprint did not match, was: ${failure.message}",
            failure.message!!.contains("does not match the accepted fingerprint"),
        )
    }

    @Test
    fun `an empty chain is refused rather than trusted`() {
        val manager = PinningTrustManager(pinnedSha256 = null, delegate = RecordingTrustManager(accept = true))

        assertThrows(CertificateException::class.java) {
            manager.checkServerTrusted(emptyArray(), "RSA")
        }
    }

    // --- actionable reasons ------------------------------------------------

    @Test
    fun `an untrusted chain is described as untrusted, not as a generic network error`() {
        val platformFailure = CertificateException(
            "PKIX path building failed: unable to find valid certification path to requested target",
        )

        val reason = TlsSupport.describeTlsFailure(platformFailure)

        assertTrue("must name the untrusted chain, was: $reason", reason.contains("untrusted certificate chain"))
        assertFalse("must not read as a generic connection failure, was: $reason", reason.contains("could not connect"))
    }

    @Test
    fun `an expired certificate is described as expired`() {
        val reason = TlsSupport.describeTlsFailure(
            CertificateException("Certificate expired at Sun Jan 01 00:00:00 UTC 2023"),
        )

        assertTrue("must say the certificate expired, was: $reason", reason.contains("expired"))
    }

    @Test
    fun `a hostname mismatch is described as a hostname mismatch`() {
        val reason = TlsSupport.describeTlsFailure(
            SSLPeerUnverifiedException("Hostname location2.navettas.com not verified"),
        )

        assertTrue("must name the hostname mismatch, was: $reason", reason.contains("hostname does not match"))
    }

    @Test
    fun `a trust-manager refusal keeps its actionable message through JSSE wrapping`() {
        val untrusted = UntrustedCertificateException(
            "the server certificate is not trusted by this device: untrusted certificate chain",
        )
        val wrapped = SSLHandshakeException("handshake failed").apply { initCause(untrusted) }

        val reason = TlsSupport.describeTlsFailure(wrapped)

        assertTrue("must keep the actionable reason, was: $reason", reason.contains("not trusted by this device"))
    }

    // --- helpers -----------------------------------------------------------

    /** A platform stand-in whose accept/reject decision the test controls. */
    private class RecordingTrustManager(private val accept: Boolean) : X509TrustManager {
        var calls = 0
            private set

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            calls++
            if (!accept) {
                throw CertificateException(
                    "PKIX path building failed: unable to find valid certification path to requested target",
                )
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun mockCertificate(): X509Certificate {
        val root = System.getProperty("beakon.repoRoot")?.let(::File)
        assumeTrue("repo root property not set", root != null)
        val certFile = File(root, "mock/cert.pem")
        assumeTrue("mock certificate not present", certFile.exists())
        return certFile.inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }
}
