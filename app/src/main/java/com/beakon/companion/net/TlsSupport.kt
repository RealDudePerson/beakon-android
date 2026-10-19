package com.beakon.companion.net

import android.annotation.SuppressLint
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS plumbing. **Platform trust is the default**: a certificate whose chain
 * validates against the device's trusted roots is accepted with no user
 * interaction, and no fingerprint is stored or consulted. This is what removes
 * the recurring "re-accept the certificate after every renewal" burden for a
 * CA-signed (e.g. Let's Encrypt) server.
 *
 * Fingerprint pinning survives only as an explicit fallback for a certificate
 * the platform *rejects* (for example the self-signed adhoc certificate on the
 * app's own port). There is intentionally no trust-all manager here: an
 * untrusted certificate is accepted only by an exact SHA-256 match against a
 * value the user explicitly approved and that is stored per host. Platform
 * trust is stricter than a hand-pinned leaf, so this is a tightening, not a
 * loosening.
 */
object TlsSupport {

    fun defaultTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("No default X509TrustManager available")
    }

    /**
     * An [SSLSocketFactory] that trusts whatever the platform trusts and, **only
     * when the platform rejects the chain**, additionally accepts the leaf
     * certificate when its SHA-256 fingerprint equals [pinnedSha256].
     *
     * This is NOT "trust all": it accepts exactly one user-pinned certificate,
     * and only as a fallback.
     */
    fun pinningSocketFactory(pinnedSha256: String?): Pair<SSLSocketFactory, X509TrustManager> {
        val manager = PinningTrustManager(pinnedSha256)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(manager), SecureRandom())
        return context.socketFactory to manager
    }

    /**
     * Turns a TLS failure into an actionable reason naming the cause (untrusted
     * chain, hostname mismatch, expired, not yet valid, ...) rather than a
     * generic "could not connect".
     *
     * Walks the cause chain because JSSE wraps the trust manager's exception
     * inside an [javax.net.ssl.SSLHandshakeException].
     */
    fun describeTlsFailure(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.toList()
        causes.filterIsInstance<SSLPeerUnverifiedException>().firstOrNull()?.let { peer ->
            return "the server's hostname does not match its certificate " +
                "(${peer.message ?: "hostname verification failed"})"
        }
        causes.filterIsInstance<UntrustedCertificateException>().firstOrNull()?.let { untrusted ->
            return untrusted.message ?: "the server certificate is not trusted by this device"
        }
        causes.filterIsInstance<CertificateException>().firstOrNull()?.let { certificateFailure ->
            return certificateFailureReason(certificateFailure)
        }
        return error.message ?: error.javaClass.simpleName
    }

    /** Maps a platform certificate failure to a short, human-readable reason. */
    internal fun certificateFailureReason(failure: CertificateException): String {
        val raw = failure.message.orEmpty()
        return when {
            raw.contains("unable to find valid certification path", ignoreCase = true) ||
                raw.contains("PKIX", ignoreCase = true) ||
                raw.contains("CertPathValidator", ignoreCase = true) ->
                "untrusted certificate chain (self-signed or unknown issuer)"
            raw.contains("expired", ignoreCase = true) -> "certificate has expired"
            raw.contains("not yet valid", ignoreCase = true) -> "certificate is not yet valid"
            raw.isBlank() -> "certificate could not be verified"
            else -> raw
        }
    }
}

/**
 * Thrown when a certificate is neither trusted by the platform nor a match for
 * a fingerprint the user explicitly accepted.
 */
class UntrustedCertificateException(
    message: String,
    cause: Throwable? = null,
) : CertificateException(message, cause)

// Lint warns about custom X509TrustManagers because they usually disable
// validation. This one does not: it consults the platform trust manager FIRST
// and only falls back to an exact, user-accepted SHA-256 fingerprint match when
// the platform rejects the chain.
@SuppressLint("CustomX509TrustManager")
class PinningTrustManager(
    private val pinnedSha256: String?,
    private val delegate: X509TrustManager = TlsSupport.defaultTrustManager(),
    private val fingerprintMatches: (presented: String, pinned: String) -> Boolean =
        CertificateFingerprint::matches,
) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificates = chain ?: emptyArray()
        val leaf = certificates.firstOrNull()
            ?: throw CertificateException("Server presented an empty certificate chain")

        try {
            // Platform trust is the default path. When it succeeds the pin is
            // never consulted, so a CA-signed certificate needs no fingerprint
            // and survives renewal without user interaction.
            delegate.checkServerTrusted(certificates, authType)
            return
        } catch (platformFailure: CertificateException) {
            // The platform rejected the chain. Fall back to an exact match
            // against a fingerprint the user explicitly accepted, if any.
            if (pinnedSha256 != null &&
                fingerprintMatches(CertificateFingerprint.sha256(leaf), pinnedSha256)
            ) {
                return
            }
            val reason = TlsSupport.certificateFailureReason(platformFailure)
            val pinNote = if (pinnedSha256 != null) {
                " and it does not match the accepted fingerprint"
            } else {
                ""
            }
            throw UntrustedCertificateException(
                "the server certificate is not trusted by this device: $reason$pinNote",
                platformFailure,
            )
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
