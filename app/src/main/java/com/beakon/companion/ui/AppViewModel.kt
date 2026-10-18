package com.beakon.companion.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beakon.companion.BeakonApplication
import com.beakon.companion.data.AppPreferences
import com.beakon.companion.net.BeakonClient
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

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as BeakonApplication).container

    val preferences: AppPreferences get() = container.preferences
    val status: StateFlow<TrackingStatus> = container.trackingState.status

    // Setup / login form.
    var instanceUrl by mutableStateOf(preferences.instanceUrl.orEmpty())
    var username by mutableStateOf(preferences.username.orEmpty())
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
    var loggedIn by mutableStateOf(container.secrets.secret() != null)
        private set

    // History / map.
    var history by mutableStateOf<List<LocationDto>>(emptyList())
        private set
    var historyMessage by mutableStateOf<String?>(null)
        private set
    var historyLoading by mutableStateOf(false)
        private set

    var intervalSeconds by mutableIntStateOf(preferences.intervalSeconds)
        private set

    fun setInterval(seconds: Int) {
        preferences.intervalSeconds = seconds
        intervalSeconds = preferences.intervalSeconds
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
            val result = runCatching { CertificateProbe.probe(host, port) }
            withContext(Dispatchers.Main) {
                busy = false
                result.onSuccess { info ->
                    certificateInfo = info
                    val pinned = container.fingerprints.pinFor(host)
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
            val client = BeakonClient(url, info.sha256)
            when (val login = client.login(username.trim(), password)) {
                LoginResult.Success -> Unit
                else -> {
                    withContext(Dispatchers.Main) {
                        busy = false
                        setupMessage = login.userMessage()
                    }
                    return@launch
                }
            }
            val secret = generateSecret()
            val token = client.setApiToken(secret)
            withContext(Dispatchers.Main) {
                busy = false
                if (token == TokenResult.Success) {
                    container.fingerprints.store(host, info.sha256)
                    container.secrets.store(secret)
                    client.sessionCookieValue()?.let { container.secrets.storeSession(it) }
                    container.preferences.instanceUrl = url
                    container.preferences.username = username.trim()
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
        container.trackingState.setTracking(false)
        container.preferences.clearSession()
        container.secrets.clear()
        container.secrets.clearSession()
        loggedIn = false
        history = emptyList()
        historyMessage = null
    }

    fun loadHistory() {
        val url = preferences.instanceUrl
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
            val client = BeakonClient(url, container.fingerprints.pinFor(host), container.secrets.session())
            val result = client.fetchLocations()
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
