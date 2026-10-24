package com.beakon.companion.ui

import android.app.Application
import com.beakon.companion.net.BeakonSession
import com.beakon.companion.net.LocationsResult
import com.beakon.companion.net.LoginResult
import com.beakon.companion.net.TokenResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An unchecked failure from the login network layer must surface as a setup
 * message, not escape the ViewModel coroutine and crash the app, and must
 * not leave the UI stuck busy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelSetupFailureTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an unchecked login failure surfaces a message and clears busy`() =
        runTest(mainDispatcher) {
            val state = FakeSetupState(
                instanceUrl = "https://location2.navettas.com",
                username = "demo",
            )
            val viewModel = AppViewModel(
                application = Application(),
                state = state,
                clients = BeakonClientFactory { _, _, _ -> ThrowingSession() },
            )
            viewModel.password = "secret"

            viewModel.completeSetup()
            // Login runs on Dispatchers.IO (a real thread), so a single
            // advanceUntilIdle() is not enough: advance the Main test
            // scheduler while the IO work finishes and posts back to Main,
            // as the existing ViewModel tests do.
            awaitSettled { !viewModel.busy }

            assertFalse("the UI must not stay busy after a failed sign-in", viewModel.busy)
            assertFalse("a thrown login must not sign the user in", viewModel.loggedIn)
            val message = viewModel.setupMessage
            assertNotNull("a thrown login must surface a setup message", message)
            assertTrue(
                "the message must name the cause, was: $message",
                message!!.contains("boom"),
            )
        }

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
}

/** A session whose login throws unchecked, as an unmapped HTTP-layer failure would. */
private class ThrowingSession : BeakonSession {
    override fun login(username: String, password: String): LoginResult =
        throw IllegalStateException("boom")

    override fun setApiToken(secret: String): TokenResult = TokenResult.Success
    override fun sessionCookieValue(): String? = null
    override fun fetchLocations(date: String?): LocationsResult =
        LocationsResult.Success(emptyList(), null)
}

private class FakeSetupState(
    override var instanceUrl: String? = null,
    override var username: String? = null,
    override var intervalSeconds: Int = 90,
    override var accuracyThresholdM: Int = 30,
) : AppViewModelState {
    override val startTrackingOnBoot: StateFlow<Boolean> = MutableStateFlow(false)
    override fun setStartTrackingOnBoot(on: Boolean) = Unit
    override fun clearSession() = Unit
    override fun pinFor(host: String): String? = null
    override fun storePin(host: String, sha256: String) = Unit
    override fun clearPin(host: String) = Unit
    override fun secret(): String? = null
    override fun storeSecret(secret: String) = Unit
    override fun session(): String? = null
    override fun storeSession(value: String) = Unit
    override fun clearSecrets() = Unit
    override val trackingStatus: StateFlow<TrackingStatus> = MutableStateFlow(TrackingStatus())
    override fun setTracking(on: Boolean) = Unit
}
