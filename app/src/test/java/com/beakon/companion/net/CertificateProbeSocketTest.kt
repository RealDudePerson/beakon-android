package com.beakon.companion.net

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * P2-1: the probe must create a **host-aware** socket so JSSE sends SNI (and the
 * endpoint hostname is known). The old code used the unconnected
 * `createSocket()` overload and connected manually, which sends no SNI and can
 * fingerprint the default virtual host's certificate on SNI-based servers.
 */
class CertificateProbeSocketTest {

    @Test
    fun `the probe socket is created for the hostname, not unconnected`() {
        val factory = RecordingSslSocketFactory()

        val socket = CertificateProbe.openSocket(
            socketFactory = factory,
            host = "location2.navettas.com",
            port = 443,
            timeoutMs = 5_000,
        )

        assertEquals("SNI requires the requested hostname", "location2.navettas.com", factory.createdHost)
        assertEquals(443, factory.createdPort)
        assertFalse(
            "the unconnected createSocket() overload must not be used (it sends no SNI)",
            factory.usedUnconnectedOverload,
        )
        assertEquals("a handshake must not be able to block forever", 5_000, socket.soTimeout)
        socket.close()
    }
}

/** Records which [SSLSocketFactory] overload [CertificateProbe] uses. */
private class RecordingSslSocketFactory : SSLSocketFactory() {
    var createdHost: String? = null
        private set
    var createdPort: Int = -1
        private set
    var usedUnconnectedOverload: Boolean = false
        private set

    override fun createSocket(): Socket {
        usedUnconnectedOverload = true
        return FakeSslSocket()
    }

    override fun createSocket(host: String, port: Int): Socket {
        createdHost = host
        createdPort = port
        return FakeSslSocket()
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket = FakeSslSocket()

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = FakeSslSocket()

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        FakeSslSocket()

    override fun getDefaultCipherSuites(): Array<String> = emptyArray()

    override fun getSupportedCipherSuites(): Array<String> = emptyArray()
}

/** Minimal [SSLSocket] so the factory can return the type `openSocket` promises. */
private class FakeSslSocket : SSLSocket() {
    override fun getSupportedCipherSuites(): Array<String> = emptyArray()

    override fun getEnabledCipherSuites(): Array<String> = emptyArray()

    override fun setEnabledCipherSuites(suites: Array<out String>?) = Unit

    override fun getSupportedProtocols(): Array<String> = emptyArray()

    override fun getEnabledProtocols(): Array<String> = emptyArray()

    override fun setEnabledProtocols(protocols: Array<out String>?) = Unit

    override fun getSession(): SSLSession = throw UnsupportedOperationException("test socket")

    override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit

    override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit

    override fun startHandshake() = Unit

    override fun setUseClientMode(mode: Boolean) = Unit

    override fun getUseClientMode(): Boolean = true

    override fun setNeedClientAuth(need: Boolean) = Unit

    override fun getNeedClientAuth(): Boolean = false

    override fun setWantClientAuth(want: Boolean) = Unit

    override fun getWantClientAuth(): Boolean = false

    override fun setEnableSessionCreation(flag: Boolean) = Unit

    override fun getEnableSessionCreation(): Boolean = true
}
