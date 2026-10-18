package com.beakon.companion.net

import android.annotation.SuppressLint
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS plumbing for deliberate, user-driven certificate pinning.
 *
 * There is intentionally no trust-all manager here. The only way a self-signed
 * certificate is accepted is by an exact SHA-256 fingerprint match against a
 * value the user explicitly approved and that is stored per host.
 */
object TlsSupport {

    fun defaultTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("No default X509TrustManager available")
    }

    /**
     * An [SSLSocketFactory] that accepts the leaf certificate when its SHA-256
     * fingerprint equals [pinnedSha256]; otherwise it delegates to the platform
     * trust manager (so ordinary publicly-signed certificates still validate).
     *
     * This is NOT "trust all": it accepts exactly one pinned certificate.
     */
    fun pinningSocketFactory(pinnedSha256: String?): Pair<SSLSocketFactory, X509TrustManager> {
        val manager = PinningTrustManager(pinnedSha256)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(manager), SecureRandom())
        return context.socketFactory to manager
    }
}

// Lint warns about custom X509TrustManagers because they usually disable
// validation. This one does not: it accepts exactly one user-pinned certificate
// and otherwise delegates to the platform trust manager.
@SuppressLint("CustomX509TrustManager")
class PinningTrustManager(private val pinnedSha256: String?) : X509TrustManager {

    private val delegate = TlsSupport.defaultTrustManager()

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificates = chain ?: emptyArray()
        val leaf = certificates.firstOrNull()
            ?: throw CertificateException("Server presented an empty certificate chain")
        val presented = CertificateFingerprint.sha256(leaf)
        if (pinnedSha256 != null && CertificateFingerprint.matches(presented, pinnedSha256)) {
            return
        }
        // No pin, or a non-matching pin: normal platform validation decides. A
        // self-signed certificate is rejected here, which is correct until the
        // user has accepted a fingerprint.
        delegate.checkServerTrusted(certificates, authType)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
