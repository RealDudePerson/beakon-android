package com.beakon.companion.net

import android.annotation.SuppressLint
import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
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
            (context.socketFactory.createSocket() as SSLSocket).use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
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
}
