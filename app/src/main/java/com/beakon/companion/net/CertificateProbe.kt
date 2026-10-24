package com.beakon.companion.net

import android.annotation.SuppressLint
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Reads the certificate an instance presents, without sending any credentials.
 *
 * This is a *diagnostic* affordance, never a gate: a normal CA-signed server is
 * logged into without it. It captures the presented chain with a read-only trust
 * manager that aborts the handshake, then separately evaluates that chain with
 * the platform/default trust manager so the UI can tell the user whether the
 * certificate is already trusted (no fingerprint needed) or not.
 */
object CertificateProbe {

    @SuppressLint("CustomX509TrustManager")
    fun probe(host: String, port: Int, timeoutMs: Int = 10_000): CertificateInfo {
        val captured = AtomicReference<Array<X509Certificate>?>(null)
        val capturedAuthType = AtomicReference<String?>(null)
        val captureManager = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certificates = chain?.takeIf { it.isNotEmpty() }
                    ?.let { presented -> Array(presented.size) { presented[it] } }
                    ?: throw CertificateException("Server presented an empty certificate chain")
                captured.set(certificates)
                capturedAuthType.set(authType)
                throw CertificateException("Certificate captured for fingerprint display")
            }

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificates are not used")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(captureManager), SecureRandom())

        try {
            openSocket(context.socketFactory, host, port, timeoutMs).use { socket ->
                try {
                    socket.startHandshake()
                } catch (_: SSLException) {
                    // Expected: the capture manager aborts the handshake.
                }
            }
        } catch (e: IOException) {
            throw IOException("Could not reach $host:$port to read its certificate: ${e.message}", e)
        }

        val chain = captured.get()
            ?: throw IOException("$host:$port did not present a certificate")
        val leaf = chain.first()
        // Whether the device already trusts this chain decides whether the user
        // is offered a fingerprint fallback at all.
        val trustedByPlatform = runCatching {
            TlsSupport.defaultTrustManager().checkServerTrusted(chain, capturedAuthType.get())
        }.isSuccess

        return CertificateInfo(
            host = host,
            port = port,
            sha256 = CertificateFingerprint.sha256(leaf),
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            notBefore = leaf.notBefore.time,
            notAfter = leaf.notAfter.time,
            trustedByPlatform = trustedByPlatform,
        )
    }

    /**
     * Opens the probe socket through the **host-aware** factory overload.
     *
     * Using `createSocket()` followed by `connect()` connects to an address but
     * never tells JSSE which hostname is being contacted, so no SNI extension is
     * sent. On SNI-based virtual hosting the server then presents its default
     * virtual-host certificate — the wrong certificate to fingerprint or pin.
     * `createSocket(host, port)` enables SNI (and the endpoint identification
     * JSSE can derive from the peer host).
     *
     * Note that this is still only the diagnostic probe: hostname verification
     * happens on the real OkHttp request, not here. The [timeoutMs] is applied
     * as a read/handshake timeout because this overload has no connect timeout.
     */
    internal fun openSocket(
        socketFactory: SSLSocketFactory,
        host: String,
        port: Int,
        timeoutMs: Int,
    ): SSLSocket = (socketFactory.createSocket(host, port) as SSLSocket).apply {
        soTimeout = timeoutMs
    }
}
