package com.beakon.companion.ui

import android.app.Application
import com.beakon.companion.net.BeakonSession
import com.beakon.companion.net.LocationDto
import com.beakon.companion.net.LocationsResult
import com.beakon.companion.net.LoginResult
import com.beakon.companion.net.TokenResult
import com.beakon.companion.net.userMessage
import com.beakon.companion.tracking.TrackingStatus
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2-8 (do not refetch on every tab entry), P2-13 (logout and the non-success
 * history paths) and P2-4 (keep the API's `latest` in state).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelHistoryTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val a = LocationDto(lat = 1.0, lon = 1.0, timestamp = "1 hour ago")
    private val b = LocationDto(lat = 2.0, lon = 2.0, timestamp = "just now")

    // --- P2-8: TTL on tab entry -------------------------------------------

    @Test
    fun `repeated tab entries do not re-request inside the TTL`() = runTest(mainDispatcher) {
        var now = 1_000L
        val session = CountingSession(LocationsResult.Success(listOf(a), a))
        val viewModel = viewModel(session = session, now = { now })

        viewModel.loadHistory()
        awaitSettled { !viewModel.historyLoading }
        viewModel.loadHistory()
        viewModel.loadHistory()

        assertEquals("tab switching must not refetch inside the TTL", 1, session.fetchCalls)
    }

    @Test
    fun `a fetch is repeated once the TTL has elapsed`() = runTest(mainDispatcher) {
        var now = 1_000L
        val session = CountingSession(LocationsResult.Success(listOf(a), a))
        val viewModel = viewModel(session = session, now = { now })

        viewModel.loadHistory()
        awaitSettled { !viewModel.historyLoading }

        now += AppViewModel.HISTORY_TTL_MS
        viewModel.loadHistory()
        awaitSettled { !viewModel.historyLoading }

        assertEquals(2, session.fetchCalls)
    }

    @Test
    fun `the explicit refresh ignores the TTL`() = runTest(mainDispatcher) {
        val now = 1_000L
        val session = CountingSession(LocationsResult.Success(listOf(a), a))
        val viewModel = viewModel(session = session, now = { now })

        viewModel.loadHistory()
        awaitSettled { !viewModel.historyLoading }
        viewModel.loadHistory(force = true)
        awaitSettled { !viewModel.historyLoading }

        assertEquals(2, session.fetchCalls)
    }

    // --- P2-4: keep the API's latest --------------------------------------

    @Test
    fun `the API's latest is kept even when the list order disagrees`() = runTest(mainDispatcher) {
        // `a` is the API's latest, but `b` is last in the locations list.
        val session = CountingSession(LocationsResult.Success(listOf(b, a), a))
        val viewModel = viewModel(session = session)

        viewModel.loadHistory()
        awaitSettled { !viewModel.historyLoading }

        assertEquals("latest must come from the API field", a, viewModel.latest)
        assertEquals("the list must be stored as returned", listOf(b, a), viewModel.history)
    }

    // --- P2-13: logout -----------------------------------------------------

    @Test
    fun `logout stops tracking, clears credentials and empties history`() {
        val state = FakeHistoryState(
            instanceUrl = "https://location2.navettas.com",
            secret = "secret",
            session = "session",
        )
        val viewModel = AppViewModel(application = Application(), state = state)
        assertTrue("precondition: signed in", viewModel.loggedIn)

        viewModel.logout()

        assertFalse(viewModel.loggedIn)
        assertEquals("tracking must be switched off", listOf(false), state.trackingCalls)
        assertEquals(1, state.clearSessionCalls)
        assertEquals(1, state.clearSecretsCalls)
        assertTrue(viewModel.history.isEmpty())
        assertNull(viewModel.latest)
        assertNull(viewModel.historyMessage)
    }

    // --- P2-13: non-success history paths ---------------------------------

    @Test
    fun `every non-success history result surfaces its own message`() = runTest(mainDispatcher) {
        val failures = listOf(
            LocationsResult.AuthRequired,
            LocationsResult.CertificateError("untrusted chain"),
            LocationsResult.NetworkError("timeout"),
            LocationsResult.ParseError("bad json"),
            LocationsResult.ServerError(500),
        )

        failures.forEach { failure ->
            val session = CountingSession(failure)
            val viewModel = viewModel(session = session)

            viewModel.loadHistory()
            awaitSettled { !viewModel.historyLoading }

            assertEquals(
                "wrong message for $failure",
                failure.userMessage(),
                viewModel.historyMessage,
            )
            assertTrue("a failure must not fabricate points: $failure", viewModel.history.isEmpty())
            assertNull("a failure must not fabricate latest: $failure", viewModel.latest)
        }
    }

    @Test
    fun `history without a signed-in instance explains the problem instead of fetching`() =
        runTest(mainDispatcher) {
            val session = CountingSession(LocationsResult.Success(emptyList(), null))
            val viewModel = viewModel(session = session, instanceUrl = null)

            viewModel.loadHistory()
            advanceUntilIdle()

            assertEquals("Not signed in. Complete Setup first.", viewModel.historyMessage)
            assertEquals(0, session.fetchCalls)
        }

    @Test
    fun `an invalid instance URL explains the problem instead of fetching`() =
        runTest(mainDispatcher) {
            val session = CountingSession(LocationsResult.Success(emptyList(), null))
            val viewModel = viewModel(session = session, instanceUrl = "not a url")

            viewModel.loadHistory()
            advanceUntilIdle()

            assertEquals("Instance URL is invalid.", viewModel.historyMessage)
            assertEquals(0, session.fetchCalls)
        }

    // --- helpers -----------------------------------------------------------

    private fun viewModel(
        session: BeakonSession,
        now: () -> Long = { 1_000L },
        instanceUrl: String? = "https://location2.navettas.com",
    ): AppViewModel = AppViewModel(
        application = Application(),
        state = FakeHistoryState(instanceUrl = instanceUrl, secret = "secret", session = "session"),
        clients = HistoryClientFactory(session),
        now = now,
    )

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
}

private class CountingSession(private val result: LocationsResult) : BeakonSession {
    var fetchCalls: Int = 0
        private set

    override fun login(username: String, password: String): LoginResult = LoginResult.Success

    override fun setApiToken(secret: String): TokenResult = TokenResult.Success

    override fun sessionCookieValue(): String? = "session"

    override fun fetchLocations(date: String?): LocationsResult {
        fetchCalls++
        return result
    }
}

private class HistoryClientFactory(private val session: BeakonSession) : BeakonClientFactory {
    override fun create(baseUrl: String, pinnedSha256: String?, initialSession: String?): BeakonSession =
        session
}

private class FakeHistoryState(
    override var instanceUrl: String? = null,
    override var username: String? = "demo",
    override var intervalSeconds: Int = 90,
    override var accuracyThresholdM: Int = 30,
    secret: String? = null,
    session: String? = null,
) : AppViewModelState {

    private var storedSecret: String? = secret
    private var storedSession: String? = session

    var clearSessionCalls: Int = 0
        private set
    var clearSecretsCalls: Int = 0
        private set
    val trackingCalls = mutableListOf<Boolean>()

    private val _trackingStatus = MutableStateFlow(TrackingStatus())
    override val trackingStatus: StateFlow<TrackingStatus> = _trackingStatus

    override val startTrackingOnBoot: StateFlow<Boolean> = MutableStateFlow(false)

    override fun setStartTrackingOnBoot(on: Boolean) = Unit

    override fun clearSession() {
        clearSessionCalls++
    }

    override fun pinFor(host: String): String? = null

    override fun storePin(host: String, sha256: String) = Unit

    override fun clearPin(host: String) = Unit

    override fun secret(): String? = storedSecret

    override fun storeSecret(secret: String) {
        storedSecret = secret
    }

    override fun session(): String? = storedSession

    override fun storeSession(value: String) {
        storedSession = value
    }

    override fun clearSecrets() {
        clearSecretsCalls++
        storedSecret = null
        storedSession = null
    }

    override fun setTracking(on: Boolean) {
        trackingCalls += on
    }
}
