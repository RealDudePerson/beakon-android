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
 * Reads the certificate an instance presents, before any credentials are sent.
 *
 * The trust manager below captures the leaf certificate and then *aborts* the
 * handshake. Nothing is trusted here: this is a read-only probe whose only job
 * is to surface the fingerprint, subject and issuer to the user.
 */
object CertificateProbe {

    @SuppressLint("CustomX509TrustManager")
    fun probe(host: String, port: Int, timeoutMs: Int = 10_000): CertificateInfo {
        val captured = AtomicReference<X509Certificate?>(null)
        val captureManager = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull()
                    ?: throw CertificateException("Server presented an empty certificate chain")
                captured.set(leaf)
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

        val certificate = captured.get()
            ?: throw IOException("$host:$port did not present a certificate")
        return CertificateInfo(
            host = host,
            port = port,
            sha256 = CertificateFingerprint.sha256(certificate),
            subject = certificate.subjectX500Principal.name,
            issuer = certificate.issuerX500Principal.name,
            notBefore = certificate.notBefore.time,
            notAfter = certificate.notAfter.time,
        )
    }
}
