package com.beakon.companion.ui

import android.app.Application
import com.beakon.companion.net.BeakonSession
import com.beakon.companion.net.CertificateInfo
import com.beakon.companion.net.LocationDto
import com.beakon.companion.net.LocationsResult
import com.beakon.companion.net.LoginResult
import com.beakon.companion.net.TokenResult
import com.beakon.companion.tracking.TrackingStatus
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for `NetworkOnMainThreadException` in [AppViewModel].
 *
 * `Dispatchers.Main` is replaced with a [StandardTestDispatcher]; the fakes for
 * every blocking network call assert they are NOT executing on the thread that
 * dispatcher uses and record a violation (and throw) if they are. The tests then
 * assert the ViewModel still reaches the correct state, so a regression that puts
 * a blocking call back on Main fails both loudly and observably.
 *
 * The Android runtime is what enforces `NetworkOnMainThreadException`, so a
 * behaviour-only JVM test would pass even with the bug. These tests assert the
 * dispatcher instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelDispatcherTest {

    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var mainThread: Thread

    @Before
    fun setUp() {
        mainThread = Thread.currentThread()
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `probeCertificate runs the blocking probe off the main dispatcher`() =
        runTest(mainDispatcher) {
            val guard = OffMainGuard(mainThread)
            val info = certificateInfo()
            val state = FakeState(instanceUrl = "https://location2.navettas.com")
            val viewModel = AppViewModel(
                application = Application(),
                state = state,
                probe = FakeProbe(guard, info),
                clients = FakeClientFactory(FakeSession(guard)),
            )

            viewModel.probeCertificate()
            awaitSettled { !viewModel.busy }

            assertTrue("a network call ran on the Main dispatcher: ${guard.violations}", guard.violations.isEmpty())
            assertEquals(info, viewModel.certificateInfo)
            assertNull(viewModel.setupMessage)
            assertNotNull("no fingerprint stored yet, so the user must be warned", viewModel.certificateWarning)
        }

    @Test
    fun `probeCertificate surfaces the specific cause when the probe fails`() =
        runTest(mainDispatcher) {
            val guard = OffMainGuard(mainThread)
            val state = FakeState(instanceUrl = "https://location2.navettas.com")
            val viewModel = AppViewModel(
                application = Application(),
                state = state,
                probe = FakeProbe(guard, certificateInfo(), error = IOException("connection refused")),
                clients = FakeClientFactory(FakeSession(guard)),
            )

            viewModel.probeCertificate()
            awaitSettled { !viewModel.busy }

            assertTrue("a network call ran on the Main dispatcher: ${guard.violations}", guard.violations.isEmpty())
            assertNull(viewModel.certificateInfo)
            val message = viewModel.setupMessage
            assertNotNull("a probe failure must surface a message", message)
            assertTrue(
                "the message must name the specific cause, was: $message",
                message!!.contains("connection refused"),
            )
        }

    @Test
    fun `completeSetup runs login and token calls off the main dispatcher`() =
        runTest(mainDispatcher) {
            val guard = OffMainGuard(mainThread)
            val info = certificateInfo()
            val state = FakeState(instanceUrl = "https://location2.navettas.com", username = "demo")
            val session = FakeSession(guard, sessionCookie = "session-abc")
            val viewModel = AppViewModel(
                application = Application(),
                state = state,
                probe = FakeProbe(guard, info),
                clients = FakeClientFactory(session),
            )

            viewModel.probeCertificate()
            awaitSettled { !viewModel.busy }
            viewModel.acceptCertificate(true)
            viewModel.onPasswordChanged("correct-horse")

            viewModel.completeSetup()
            awaitSettled { !viewModel.busy }

            assertTrue("a network call ran on the Main dispatcher: ${guard.violations}", guard.violations.isEmpty())
            assertTrue("a successful setup must log the user in", viewModel.loggedIn)
            assertNull(viewModel.setupMessage)
            assertEquals(info.sha256, state.storedPin?.second)
            assertNotNull("the generated API secret must be stored", state.storedSecret)
            assertEquals("session-abc", state.storedSession)
            assertEquals("https://location2.navettas.com", state.instanceUrl)
            assertEquals("demo", state.username)
        }

    @Test
    fun `completeSetup surfaces an auth error without running network on main`() =
        runTest(mainDispatcher) {
            val guard = OffMainGuard(mainThread)
            val state = FakeState(instanceUrl = "https://location2.navettas.com", username = "demo")
            val session = FakeSession(guard, loginResult = LoginResult.InvalidCredentials)
            val viewModel = AppViewModel(
                application = Application(),
                state = state,
                probe = FakeProbe(guard, certificateInfo()),
                clients = FakeClientFactory(session),
            )

            viewModel.probeCertificate()
            awaitSettled { !viewModel.busy }
            viewModel.acceptCertificate(true)
            viewModel.onPasswordChanged("wrong")

            viewModel.completeSetup()
            awaitSettled { !viewModel.busy }

            assertTrue("a network call ran on the Main dispatcher: ${guard.violations}", guard.violations.isEmpty())
            assertFalse(viewModel.loggedIn)
            val message = viewModel.setupMessage
            assertNotNull("an auth failure must surface a message", message)
            assertTrue("the message must name the cause, was: $message", message!!.contains("wrong username or password"))
            assertNull(state.storedSecret)
        }

    @Test
    fun `completeSetup logs in without any certificate interaction`() =
        runTest(mainDispatcher) {
            val guard = OffMainGuard(mainThread)
            val state = FakeState(instanceUrl = "https://location2.navettas.com", username = "demo")
            val probe = RecordingProbe(certificateInfo())
            val session = FakeSession(guard, sessionCookie = "session-abc")
            val viewModel = AppViewModel(
                application = Application(),
                state = state,
                probe = probe,
                clients = FakeClientFactory(session),
            )
            viewModel.onPasswordChanged("correct-horse")

            viewModel.completeSetup()
            awaitSettled { !viewModel.busy }

            assertEquals("a CA-signed login must not require the certificate probe", 0, probe.calls)
            assertTrue("a platform-trusted login must succeed with no certificate step", viewModel.loggedIn)
            assertNull(viewModel.setupMessage)
            assertNull("no fingerprint may be stored on the platform-trust path", state.storedPin)
            assertNotNull("the generated API secret must be stored", state.storedSecret)
        }

    @Test
    fun `a certificate failure surfaces an actionable reason and the explicit fallback then succeeds`() =
        runTest(mainDispatcher) {
            val guard = OffMainGuard(mainThread)
            val state = FakeState(instanceUrl = "https://location2.navettas.com", username = "demo")
            val info = certificateInfo()
            val session = SequencedSession(
                guard = guard,
                loginResults = mutableListOf(
                    LoginResult.CertificateError(
                        "the server certificate is not trusted by this device: untrusted certificate chain",
                    ),
                    LoginResult.Success,
                ),
            )
            val viewModel = AppViewModel(
                application = Application(),
                state = state,
                probe = FakeProbe(guard, info),
                clients = FakeClientFactory(session),
            )
            viewModel.onPasswordChanged("correct-horse")

            // First attempt: the platform rejects -> actionable failure, no login,
            // and the fallback is flagged but not yet taken.
            viewModel.completeSetup()
            awaitSettled { !viewModel.busy }
            assertFalse(viewModel.loggedIn)
            assertTrue("a real verification failure must be flagged", viewModel.certificateRejected)
            assertNull("no fingerprint may be stored before the user accepts one", state.storedPin)
            val message = viewModel.setupMessage
            assertNotNull("a certificate failure must surface a message", message)
            assertTrue(
                "the message must name the reason, was: $message",
                message!!.contains("not trusted by this device"),
            )

            // The user deliberately inspects and accepts the fingerprint.
            viewModel.probeCertificate()
            awaitSettled { !viewModel.busy }
            assertNotNull(viewModel.certificateInfo)
            viewModel.acceptCertificate(true)
            assertEquals("an explicit accept must store the fingerprint", info.sha256, state.storedPin?.second)

            // Retry: the accepted fingerprint is now used as the fallback.
            viewModel.completeSetup()
            awaitSettled { !viewModel.busy }
            assertTrue("the explicit fallback must let the retry succeed", viewModel.loggedIn)
        }

    @Test
    fun `unchecking the fingerprint acceptance withdraws the stored pin`() =
        runTest(mainDispatcher) {
            val guard = OffMainGuard(mainThread)
            val state = FakeState(instanceUrl = "https://location2.navettas.com")
            val info = certificateInfo()
            val viewModel = AppViewModel(
                application = Application(),
                state = state,
                probe = FakeProbe(guard, info),
                clients = FakeClientFactory(FakeSession(guard)),
            )

            viewModel.probeCertificate()
            awaitSettled { !viewModel.busy }
            viewModel.acceptCertificate(true)
            assertEquals("accepting must store the fingerprint", info.sha256, state.storedPin?.second)

            viewModel.acceptCertificate(false)
            assertNull("withdrawing consent must remove the fingerprint", state.storedPin)
        }

    @Test
    fun `loadHistory fetches locations off the main dispatcher`() =        runTest(mainDispatcher) {
            val guard = OffMainGuard(mainThread)
            val points = listOf(
                LocationDto(lat = 1.0, lon = 2.0, timestamp = "18 hours ago", batt = 80, isCharging = false),
                LocationDto(lat = 3.0, lon = 4.0, timestamp = "3 hours ago"),
            )
            val state = FakeState(instanceUrl = "https://location2.navettas.com")
            val session = FakeSession(guard, locationsResult = LocationsResult.Success(points, points.last()))
            val viewModel = AppViewModel(
                application = Application(),
                state = state,
                probe = FakeProbe(guard, certificateInfo()),
                clients = FakeClientFactory(session),
            )

            viewModel.loadHistory()
            awaitSettled { !viewModel.historyLoading }

            assertTrue("a network call ran on the Main dispatcher: ${guard.violations}", guard.violations.isEmpty())
            assertEquals(points, viewModel.history)
            assertNull(viewModel.historyMessage)
        }

    // --- helpers -----------------------------------------------------------

    /**
     * Advances the Main test scheduler while any real background work (the
     * `Dispatchers.IO` block) finishes and posts its continuation back to Main.
     */
    private fun TestScope.awaitSettled(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(2)
        }
        advanceUntilIdle()
        assertTrue("timed out waiting for the ViewModel to settle", condition())
    }

    private fun certificateInfo(): CertificateInfo = CertificateInfo(
        host = "location2.navettas.com",
        port = 443,
        sha256 = "AA:BB:CC",
        subject = "CN=location2.navettas.com",
        issuer = "CN=location2.navettas.com",
        notBefore = 0L,
        notAfter = 0L,
    )
}

/**
 * Fails if a blocking network call executes on the thread used by the
 * TestDispatcher installed as `Dispatchers.Main`.
 */
private class OffMainGuard(private val mainThread: Thread) {
    val violations = mutableListOf<String>()

    fun check(operation: String) {
        if (Thread.currentThread() === mainThread) {
            violations += operation
            throw AssertionError("$operation ran on the Main dispatcher (thread ${mainThread.name})")
        }
    }
}

private class FakeProbe(
    private val guard: OffMainGuard,
    private val info: CertificateInfo,
    private val error: Throwable? = null,
) : CertificateProbeFn {
    override fun probe(host: String, port: Int): CertificateInfo {
        guard.check("CertificateProbe.probe($host:$port)")
        error?.let { throw it }
        return info
    }
}

private class FakeSession(
    private val guard: OffMainGuard,
    private val loginResult: LoginResult = LoginResult.Success,
    private val tokenResult: TokenResult = TokenResult.Success,
    private val locationsResult: LocationsResult = LocationsResult.Success(emptyList(), null),
    private val sessionCookie: String? = "session-cookie",
) : BeakonSession {
    override fun login(username: String, password: String): LoginResult {
        guard.check("BeakonSession.login")
        return loginResult
    }

    override fun setApiToken(secret: String): TokenResult {
        guard.check("BeakonSession.setApiToken")
        return tokenResult
    }

    override fun sessionCookieValue(): String? = sessionCookie

    override fun fetchLocations(date: String?): LocationsResult {
        guard.check("BeakonSession.fetchLocations")
        return locationsResult
    }
}

private class FakeClientFactory(private val session: BeakonSession) : BeakonClientFactory {
    override fun create(baseUrl: String, pinnedSha256: String?, initialSession: String?): BeakonSession = session
}

/** A probe that records whether it was called, to prove a login needs no certificate step. */
private class RecordingProbe(private val info: CertificateInfo) : CertificateProbeFn {
    var calls = 0
        private set

    override fun probe(host: String, port: Int): CertificateInfo {
        calls++
        return info
    }
}

/** A session whose login results are consumed in order (failure first, then success). */
private class SequencedSession(
    private val guard: OffMainGuard,
    private val loginResults: MutableList<LoginResult>,
    private val sessionCookie: String? = "session-cookie",
) : BeakonSession {
    override fun login(username: String, password: String): LoginResult {
        guard.check("BeakonSession.login")
        return loginResults.removeAt(0)
    }

    override fun setApiToken(secret: String): TokenResult {
        guard.check("BeakonSession.setApiToken")
        return TokenResult.Success
    }

    override fun sessionCookieValue(): String? = sessionCookie

    override fun fetchLocations(date: String?): LocationsResult {
        guard.check("BeakonSession.fetchLocations")
        return LocationsResult.Success(emptyList(), null)
    }
}

private class FakeState(
    override var instanceUrl: String? = null,
    override var username: String? = null,
    override var intervalSeconds: Int = 90,
    override var accuracyThresholdM: Int = 30,
) : AppViewModelState {

    private val _trackingStatus = MutableStateFlow(TrackingStatus())
    override val trackingStatus: StateFlow<TrackingStatus> = _trackingStatus

    private val _startTrackingOnBoot = MutableStateFlow(false)
    override val startTrackingOnBoot: StateFlow<Boolean> = _startTrackingOnBoot

    override fun setStartTrackingOnBoot(on: Boolean) {
        _startTrackingOnBoot.value = on
    }

    private val pins = mutableMapOf<String, String>()
    var storedSecret: String? = null
        private set
    var storedSession: String? = null
        private set
    var storedPin: Pair<String, String>? = null
        private set

    override fun clearSession() = Unit

    override fun pinFor(host: String): String? = pins[host]

    override fun storePin(host: String, sha256: String) {
        pins[host] = sha256
        storedPin = host to sha256
    }

    override fun clearPin(host: String) {
        pins.remove(host)
        if (storedPin?.first == host) storedPin = null
    }

    override fun secret(): String? = storedSecret

    override fun storeSecret(secret: String) {
        storedSecret = secret
    }

    override fun session(): String? = storedSession

    override fun storeSession(value: String) {
        storedSession = value
    }

    override fun clearSecrets() {
        storedSecret = null
        storedSession = null
    }

    override fun setTracking(on: Boolean) {
        _trackingStatus.value = _trackingStatus.value.copy(tracking = on)
    }
}
