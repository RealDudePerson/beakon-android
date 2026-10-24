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
    var accuracyThresholdM: Int
    /** Storage-backed view of the "start tracking at boot" setting. */
    val startTrackingOnBoot: StateFlow<Boolean>
    fun setStartTrackingOnBoot(on: Boolean)
    fun clearSession()
    fun pinFor(host: String): String?
    fun storePin(host: String, sha256: String)
    fun clearPin(host: String)
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
    /** Injectable clock so the history TTL is testable. */
    private val now: () -> Long = System::currentTimeMillis,
    private val historyTtlMs: Long = HISTORY_TTL_MS,
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

    /**
     * True once a login attempt failed because the platform does not trust the
     * certificate. The UI uses this to reveal the explicit fingerprint fallback;
     * the fallback is never offered for a platform-trusted certificate.
     */
    var certificateRejected by mutableStateOf(false)
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

    /**
     * The server's `latest` point, kept verbatim instead of inferring "latest"
     * from list order (the server does not promise an order).
     */
    var latest by mutableStateOf<LocationDto?>(null)
        private set

    var historyMessage by mutableStateOf<String?>(null)
        private set
    var historyLoading by mutableStateOf(false)
        private set

    /** Start time of the last history attempt, used by the tab-entry TTL. */
    private var lastHistoryAttemptAt: Long? = null

    var intervalSeconds by mutableIntStateOf(state.intervalSeconds)
        private set

    var accuracyThresholdM by mutableIntStateOf(state.accuracyThresholdM)
        private set

    /**
     * Collected directly from the preference-backed flow, so the toggle always
     * shows the persisted value rather than a local copy that could drift.
     */
    val startTrackingOnBoot: StateFlow<Boolean> = state.startTrackingOnBoot

    fun setInterval(seconds: Int) {
        state.intervalSeconds = seconds
        intervalSeconds = state.intervalSeconds
    }

    fun setAccuracyThreshold(m: Int) {
        state.accuracyThresholdM = m
        accuracyThresholdM = state.accuracyThresholdM
    }

    fun setStartTrackingOnBoot(on: Boolean) {
        state.setStartTrackingOnBoot(on)
    }

    fun onUrlChanged(value: String) {
        instanceUrl = value
        certificateInfo = null
        certificateAccepted = false
        certificateWarning = null
        certificateRejected = false
        setupMessage = null
    }

    fun onUsernameChanged(value: String) {
        username = value
    }

    fun onPasswordChanged(value: String) {
        password = value
    }

    /**
     * Records the user's explicit decision to trust the presented certificate by
     * fingerprint. This is only offered for a certificate the platform rejects,
     * and only stores the fingerprint once the user has actually accepted it.
     */
    fun acceptCertificate(accepted: Boolean) {
        certificateAccepted = accepted
        val info = certificateInfo ?: return
        if (info.trustedByPlatform) return
        if (accepted) {
            state.storePin(info.host, info.sha256)
        } else {
            // Unchecking withdraws consent: remove the stored fingerprint so the
            // fallback is no longer available for this host.
            state.clearPin(info.host)
        }
    }

    /**
     * Optional diagnostic: fetch and display the certificate. This is never
     * required to log in. When the platform already trusts the certificate the
     * user is told so and no fingerprint is offered; only a certificate the
     * platform rejects can be accepted by fingerprint.
     */
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
                        info.trustedByPlatform -> {
                            // Platform trust: no fingerprint is needed or offered.
                            certificateAccepted = true
                            certificateWarning = null
                        }
                        pinned == null -> {
                            certificateAccepted = false
                            certificateWarning =
                                "This certificate is NOT trusted by this device. If you recognize the " +
                                    "fingerprint below, you can choose to trust it by fingerprint."
                        }
                        CertificateFingerprint.matches(info.sha256, pinned) -> {
                            certificateAccepted = true
                            certificateWarning = null
                        }
                        else -> {
                            certificateAccepted = false
                            certificateWarning =
                                "This certificate is NOT trusted by this device and its fingerprint does " +
                                    "NOT match the one previously accepted for $host. Do not accept unless " +
                                    "you expected the certificate to change."
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
     * secret. No certificate step is required: the connection uses platform
     * trust. A previously accepted fingerprint is passed only as an explicit
     * fallback for a certificate the platform rejects. The UI states plainly,
     * before this is called, that the token is overwritten.
     */
    fun completeSetup() {
        val url = BeakonUrl.normalize(instanceUrl)
        if (url == null) {
            setupMessage = "Enter a valid instance URL (https://host). Plain http:// is not supported."
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
            // Platform trust is the default. A stored fingerprint is used only
            // if the platform rejects the certificate; on the platform-trust
            // path no fingerprint is stored or consulted.
            val client = clients.create(url, state.pinFor(host), null)
            // Blocking network calls: never on the main thread.
            val login = withContext(Dispatchers.IO) { client.login(username.trim(), password) }
            if (login is LoginResult.CertificateError) {
                withContext(Dispatchers.Main) {
                    busy = false
                    certificateRejected = true
                    setupMessage = login.userMessage()
                }
                return@launch
            }
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
                    // No pin is stored here: a successful login means either the
                    // platform trusted the certificate or an already-accepted
                    // fingerprint did.
                    state.storeSecret(secret)
                    client.sessionCookieValue()?.let { state.storeSession(it) }
                    state.instanceUrl = url
                    state.username = username.trim()
                    password = ""
                    loggedIn = true
                    certificateRejected = false
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
        latest = null
        historyMessage = null
        // A later sign-in must fetch immediately rather than inherit the TTL.
        lastHistoryAttemptAt = null
    }

    /**
     * Loads history at most once per [HISTORY_TTL_MS].
     *
     * The History and Map screens call this on every tab entry. Without the TTL
     * each tab switch re-requested the same data from the server. [force] is for
     * the explicit refresh button.
     */
    fun loadHistory(force: Boolean = false) {
        if (historyLoading) return
        if (!force && !historyIsStale()) return

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
        // Record the attempt up front so a slow request cannot be re-triggered
        // and a failed attempt is not retried on every tab switch either.
        lastHistoryAttemptAt = now()
        viewModelScope.launch {
            val client = clients.create(url, state.pinFor(host), state.session())
            // Blocking network call: never on the main thread.
            val result = withContext(Dispatchers.IO) { client.fetchLocations() }
            withContext(Dispatchers.Main) {
                historyLoading = false
                when (result) {
                    is LocationsResult.Success -> {
                        history = result.locations
                        latest = result.latest
                        historyMessage = null
                    }
                    else -> historyMessage = result.userMessage()
                }
            }
        }
    }

    private fun historyIsStale(): Boolean {
        val last = lastHistoryAttemptAt ?: return true
        return now() - last >= historyTtlMs
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * How long a successful/attempted history fetch is reused for tab
         * entries. Under the 90 s reporting cadence, a minute is enough to stop
         * tab-switch storms while still showing new points promptly.
         */
        const val HISTORY_TTL_MS = 60_000L
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

    override var accuracyThresholdM: Int
        get() = container.preferences.accuracyThresholdM
        set(value) {
            container.preferences.accuracyThresholdM = value
        }

    override val startTrackingOnBoot: StateFlow<Boolean> =
        container.preferences.startTrackingOnBootFlow

    override fun setStartTrackingOnBoot(on: Boolean) {
        container.preferences.startTrackingOnBoot = on
    }

    override fun clearSession() = container.preferences.clearSession()

    override fun pinFor(host: String): String? = container.fingerprints.pinFor(host)

    override fun storePin(host: String, sha256: String) = container.fingerprints.store(host, sha256)

    override fun clearPin(host: String) = container.fingerprints.clear(host)

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
