package com.beakon.companion.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beakon.companion.BeakonApplication
import com.beakon.companion.data.AppContainer
import com.beakon.companion.net.BeakonClient
import com.beakon.companion.net.BeakonSession
import com.beakon.companion.net.BeakonUrl
import com.beakon.companion.net.CertificateFingerprint
import com.beakon.companion.net.CertificateInfo
import com.beakon.companion.net.CertificateProbe
import com.beakon.companion.net.LocationDto
import com.beakon.companion.net.LocationsResult
import com.beakon.companion.net.LoginResult
import com.beakon.companion.net.TokenResult
import com.beakon.companion.net.userMessage
import com.beakon.companion.tracking.TrackingStatus
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Blocking certificate probe. Injected so tests can assert the dispatcher the
 * probe runs on. Implementations block and must be called off the main thread.
 */
fun interface CertificateProbeFn {
    fun probe(host: String, port: Int): CertificateInfo
}

/** Creates a [BeakonSession] for an instance. Injected so tests can supply a fake. */
fun interface BeakonClientFactory {
    fun create(baseUrl: String, pinnedSha256: String?, initialSession: String?): BeakonSession
}

/**
 * The process-wide state [AppViewModel] reads and writes. Extracted from the
 * concrete [AppContainer] so JVM tests can substitute in-memory fakes.
 */
interface AppViewModelState {
    var instanceUrl: String?
    var username: String?
    var intervalSeconds: Int
    fun clearSession()
    fun pinFor(host: String): String?
    fun storePin(host: String, sha256: String)
    fun secret(): String?
    fun storeSecret(secret: String)
    fun session(): String?
    fun storeSession(value: String)
    fun clearSecrets()
    val trackingStatus: StateFlow<TrackingStatus>
    fun setTracking(on: Boolean)
}

class AppViewModel @JvmOverloads constructor(
    application: Application,
    private val state: AppViewModelState = AndroidAppViewModelState(
        (application as BeakonApplication).container,
    ),
    private val probe: CertificateProbeFn = CertificateProbeFn { host, port ->
        CertificateProbe.probe(host, port)
    },
    private val clients: BeakonClientFactory = BeakonClientFactory { url, pin, session ->
        BeakonClient(url, pin, session)
    },
) : AndroidViewModel(application) {

    val status: StateFlow<TrackingStatus> = state.trackingStatus

    // Setup / login form.
    var instanceUrl by mutableStateOf(state.instanceUrl.orEmpty())
    var username by mutableStateOf(state.username.orEmpty())
    var password by mutableStateOf("")

    var certificateInfo by mutableStateOf<CertificateInfo?>(null)
        private set
    var certificateAccepted by mutableStateOf(false)
        private set
    var certificateWarning by mutableStateOf<String?>(null)
        private set
    var setupMessage by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var loggedIn by mutableStateOf(state.secret() != null)
        private set

    // History / map.
    var history by mutableStateOf<List<LocationDto>>(emptyList())
        private set
    var historyMessage by mutableStateOf<String?>(null)
        private set
    var historyLoading by mutableStateOf(false)
        private set

    var intervalSeconds by mutableIntStateOf(state.intervalSeconds)
        private set

    fun setInterval(seconds: Int) {
        state.intervalSeconds = seconds
        intervalSeconds = state.intervalSeconds
    }

    fun onUrlChanged(value: String) {
        instanceUrl = value
        certificateInfo = null
        certificateAccepted = false
        certificateWarning = null
        setupMessage = null
    }

    fun onUsernameChanged(value: String) {
        username = value
    }

    fun onPasswordChanged(value: String) {
        password = value
    }

    fun acceptCertificate(accepted: Boolean) {
        certificateAccepted = accepted
    }

    /** Fetch and display the certificate before any credentials are sent. */
    fun probeCertificate() {
        val url = BeakonUrl.normalize(instanceUrl)
        if (url == null) {
            setupMessage = "Enter a valid instance URL (https://host). Plain http:// is not supported."
            return
        }
        val host = BeakonUrl.host(url)
            ?: run {
                setupMessage = "Could not parse the host from that URL."
                return
            }
        val port = BeakonUrl.port(url) ?: 443
        busy = true
        setupMessage = null
        viewModelScope.launch {
            // Blocking socket I/O: never on the main thread.
            val result = runCatching {
                withContext(Dispatchers.IO) { probe.probe(host, port) }
            }
            withContext(Dispatchers.Main) {
                busy = false
                result.onSuccess { info ->
                    certificateInfo = info
                    val pinned = state.pinFor(host)
                    when {
                        pinned == null -> {
                            certificateAccepted = false
                            certificateWarning =
                                "No fingerprint is stored for $host yet. Review it carefully, then accept."
                        }
                        CertificateFingerprint.matches(info.sha256, pinned) -> {
                            certificateAccepted = true
                            certificateWarning = null
                        }
                        else -> {
                            certificateAccepted = false
                            certificateWarning =
                                "The presented fingerprint does NOT match the one previously accepted for $host. " +
                                    "Do not accept unless you expected the certificate to change."
                        }
                    }
                }.onFailure { error ->
                    certificateInfo = null
                    certificateAccepted = false
                    setupMessage = "Could not read the certificate from $host:$port. ${error.message}"
                }
            }
        }
    }

    /**
     * Log in, then overwrite the account's API token with a freshly generated
     * secret. The UI states plainly, before this is called, that the token is
     * overwritten.
     */
    fun completeSetup() {
        val url = BeakonUrl.normalize(instanceUrl)
        if (url == null) {
            setupMessage = "Enter a valid instance URL (https://host). Plain http:// is not supported."
            return
        }
        val info = certificateInfo
        if (info == null) {
            setupMessage = "Fetch and review the certificate before signing in."
            return
        }
        if (!certificateAccepted) {
            setupMessage = "You must accept the certificate fingerprint before signing in."
            return
        }
        if (username.isBlank()) {
            setupMessage = "Enter your username."
            return
        }
        if (password.isEmpty()) {
            setupMessage = "Enter your password."
            return
        }
        val host = BeakonUrl.host(url)
            ?: run {
                setupMessage = "Could not parse the host from that URL."
                return
            }
        busy = true
        setupMessage = null
        viewModelScope.launch {
            val client = clients.create(url, info.sha256, null)
            // Blocking network calls: never on the main thread.
            val login = withContext(Dispatchers.IO) { client.login(username.trim(), password) }
            if (login != LoginResult.Success) {
                withContext(Dispatchers.Main) {
                    busy = false
                    setupMessage = login.userMessage()
                }
                return@launch
            }
            val secret = generateSecret()
            val token = withContext(Dispatchers.IO) { client.setApiToken(secret) }
            withContext(Dispatchers.Main) {
                busy = false
                if (token == TokenResult.Success) {
                    state.storePin(host, info.sha256)
                    state.storeSecret(secret)
                    client.sessionCookieValue()?.let { state.storeSession(it) }
                    state.instanceUrl = url
                    state.username = username.trim()
                    password = ""
                    loggedIn = true
                    setupMessage = null
                } else {
                    setupMessage = token.userMessage()
                }
            }
        }
    }

    fun logout() {
        state.setTracking(false)
        state.clearSession()
        state.clearSecrets()
        loggedIn = false
        history = emptyList()
        historyMessage = null
    }

    fun loadHistory() {
        val url = state.instanceUrl
        if (url == null) {
            historyMessage = "Not signed in. Complete Setup first."
            return
        }
        val host = BeakonUrl.host(url)
        if (host == null) {
            historyMessage = "Instance URL is invalid."
            return
        }
        historyLoading = true
        historyMessage = null
        viewModelScope.launch {
            val client = clients.create(url, state.pinFor(host), state.session())
            // Blocking network call: never on the main thread.
            val result = withContext(Dispatchers.IO) { client.fetchLocations() }
            withContext(Dispatchers.Main) {
                historyLoading = false
                when (result) {
                    is LocationsResult.Success -> {
                        history = result.locations
                        historyMessage = null
                    }
                    else -> historyMessage = result.userMessage()
                }
            }
        }
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Adapts the production [AppContainer] to [AppViewModelState]. Kept private so
 * the concrete Android stores never leak into the ViewModel's API.
 */
private class AndroidAppViewModelState(
    private val container: AppContainer,
) : AppViewModelState {

    override var instanceUrl: String?
        get() = container.preferences.instanceUrl
        set(value) {
            container.preferences.instanceUrl = value
        }

    override var username: String?
        get() = container.preferences.username
        set(value) {
            container.preferences.username = value
        }

    override var intervalSeconds: Int
        get() = container.preferences.intervalSeconds
        set(value) {
            container.preferences.intervalSeconds = value
        }

    override fun clearSession() = container.preferences.clearSession()

    override fun pinFor(host: String): String? = container.fingerprints.pinFor(host)

    override fun storePin(host: String, sha256: String) = container.fingerprints.store(host, sha256)

    override fun secret(): String? = container.secrets.secret()

    override fun storeSecret(secret: String) = container.secrets.store(secret)

    override fun session(): String? = container.secrets.session()

    override fun storeSession(value: String) = container.secrets.storeSession(value)

    override fun clearSecrets() {
        container.secrets.clear()
        container.secrets.clearSession()
    }

    override val trackingStatus: StateFlow<TrackingStatus> = container.trackingState.status

    override fun setTracking(on: Boolean) = container.trackingState.setTracking(on)
}
