package com.beakon.companion.integration

import com.beakon.companion.net.BeakonClient
import com.beakon.companion.net.CertificateProbe
import com.beakon.companion.net.LocationReport
import com.beakon.companion.net.LoginResult
import com.beakon.companion.net.SendResult
import com.beakon.companion.net.TokenResult
import com.beakon.companion.tracking.RateLimitBackoff
import com.beakon.companion.tracking.ReportCoordinator
import com.beakon.companion.tracking.TrackingStateStore
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Drives the real client against the local Python mock (mock/beakon_mock.py),
 * over HTTPS with a self-signed certificate, exercising the fingerprint flow
 * and every documented response code.
 *
 * Skipped (not failed) when python3 or the mock script is unavailable.
 */
class MockServerIntegrationTest {

    private val repoRoot = File(System.getProperty("beakon.repoRoot")!!)
    private val processes = mutableListOf<Process>()

    @After
    fun tearDown() {
        processes.forEach { it.destroy() }
        processes.forEach { it.waitFor(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `bad password surfaces an auth error without crashing`() {
        val port = startServer()
        val certificate = CertificateProbe.probe("127.0.0.1", port)
        val client = BeakonClient("https://127.0.0.1:$port", certificate.sha256)

        val result = client.login("demo", "not-the-password")

        assertEquals(LoginResult.InvalidCredentials, result)
        assertFalse(client.hasSession())
    }

    @Test
    fun `good password with an accepted certificate proceeds`() {
        val port = startServer()
        val certificate = CertificateProbe.probe("127.0.0.1", port)
        assertTrue("fingerprint should be non-empty", certificate.sha256.isNotBlank())

        val client = BeakonClient("https://127.0.0.1:$port", certificate.sha256)

        assertEquals(LoginResult.Success, client.login("demo", "correct-horse"))
        assertTrue(client.hasSession())
        assertEquals(TokenResult.Success, client.setApiToken("integration-secret"))
    }

    @Test
    fun `a 201 updates last successful send`() {
        val port = startServer()
        val certificate = CertificateProbe.probe("127.0.0.1", port)
        val client = BeakonClient("https://127.0.0.1:$port", certificate.sha256)
        client.login("demo", "correct-horse")
        client.setApiToken("integration-secret")

        val state = TrackingStateStore()
        val coordinator = ReportCoordinator(client, "demo", "integration-secret", state)

        val result = coordinator.report(LocationReport(40.7128, -74.0060, 8.0, 77, false))

        assertEquals(SendResult.Success, result)
        assertNotNull("last successful send must be recorded", state.status.value.lastSuccessfulSendAt)
        assertEquals(1, state.status.value.pointsSentToday)
        assertNull(state.status.value.lastError)
    }

    @Test
    fun `a 401 surfaces an auth rejected message`() {
        val port = startServer()
        val certificate = CertificateProbe.probe("127.0.0.1", port)
        val client = BeakonClient("https://127.0.0.1:$port", certificate.sha256)
        client.login("demo", "correct-horse")
        client.setApiToken("integration-secret")

        val state = TrackingStateStore()
        val coordinator = ReportCoordinator(client, "demo", "wrong-secret", state)

        val result = coordinator.report(LocationReport(1.0, 2.0))

        assertEquals(SendResult.AuthRejected, result)
        val message = state.status.value.lastError
        assertNotNull("an auth rejection must surface a message", message)
        assertTrue("message should mention 401: $message", message!!.contains("401"))
    }

    @Test
    fun `a 429 triggers backoff and surfaces a rate limit message`() {
        // rate-limit-max 2: success (1), auth failure (2), then 429.
        val port = startServer(rateLimitMax = 2, rateLimitWindow = 300)
        val certificate = CertificateProbe.probe("127.0.0.1", port)
        val client = BeakonClient("https://127.0.0.1:$port", certificate.sha256)
        client.login("demo", "correct-horse")
        client.setApiToken("integration-secret")

        val state = TrackingStateStore()
        val backoff = RateLimitBackoff(skipTicksAfterRateLimit = 1)
        val good = ReportCoordinator(client, "demo", "integration-secret", state, backoff)
        val bad = ReportCoordinator(client, "demo", "wrong-secret", state, backoff)

        assertEquals(SendResult.Success, good.report(LocationReport(1.0, 1.0)))
        assertEquals(SendResult.AuthRejected, bad.report(LocationReport(1.0, 1.0)))

        val limited = good.report(LocationReport(1.0, 1.0))
        assertTrue("expected a 429, got $limited", limited is SendResult.RateLimited)
        val message = state.status.value.lastError
        assertNotNull("a rate limit must surface a message", message)
        assertTrue("message should mention 429: $message", message!!.contains("429"))

        // Backoff: the next tick is skipped without touching the network.
        assertEquals(SendResult.SkippedByBackoff, good.report(LocationReport(1.0, 1.0)))
    }

    @Test
    fun `a changed certificate fingerprint is rejected on a credentialed call`() {
        val portA = startServer(cert = "cert.pem", key = "key.pem")
        val certificateA = CertificateProbe.probe("127.0.0.1", portA)

        // A second instance presenting a different self-signed certificate.
        val portB = startServer(cert = "cert2.pem", key = "key2.pem")
        val certificateB = CertificateProbe.probe("127.0.0.1", portB)
        assertNotEquals(certificateA.sha256, certificateB.sha256)

        // Pinned to A, but talking to B: must be refused.
        val client = BeakonClient("https://127.0.0.1:$portB", certificateA.sha256)
        val result = client.login("demo", "correct-horse")

        assertTrue("expected a certificate error, got $result", result is LoginResult.CertificateError)
    }

    // --- helpers -----------------------------------------------------------

    private fun startServer(
        cert: String = "cert.pem",
        key: String = "key.pem",
        rateLimitMax: Int = 5,
        rateLimitWindow: Int = 300,
    ): Int {
        val python = pythonExecutable() ?: run {
            assumeTrue("python3 is not available; skipping mock integration tests", false)
            error("unreachable")
        }
        val script = File(repoRoot, "mock/beakon_mock.py")
        assumeTrue("mock script missing at $script", script.exists())

        val port = freePort()
        val process = ProcessBuilder(
            python,
            script.absolutePath,
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "--cert", File(repoRoot, "mock/$cert").absolutePath,
            "--key", File(repoRoot, "mock/$key").absolutePath,
            "--rate-limit-max", rateLimitMax.toString(),
            "--rate-limit-window", rateLimitWindow.toString(),
        ).redirectErrorStream(true).start()
        processes += process
        pump(process)

        assertTrue("mock did not start listening on port $port", waitUntilListening(port))
        return port
    }

    private fun pythonExecutable(): String? {
        for (candidate in listOf("python3", "python")) {
            try {
                val probe = ProcessBuilder(candidate, "--version").redirectErrorStream(true).start()
                if (probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0) return candidate
            } catch (_: Exception) {
                // try the next candidate
            }
        }
        return null
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun waitUntilListening(port: Int, timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("127.0.0.1", port).use { return true }
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        return false
    }

    private fun pump(process: Process) {
        Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { println("[mock] $it") }
            } catch (_: Exception) {
                // process ended
            }
        }.apply { isDaemon = true }.start()
    }
}
