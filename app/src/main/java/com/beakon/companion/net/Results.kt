package com.beakon.companion.net

/** Outcome of `POST /login`. */
sealed interface LoginResult {
    data object Success : LoginResult
    data object InvalidCredentials : LoginResult
    data class CertificateError(val message: String) : LoginResult
    data class NetworkError(val message: String) : LoginResult
    data class ServerError(val code: Int) : LoginResult
}

/** Outcome of `POST /api/updatetoken`. */
sealed interface TokenResult {
    data object Success : TokenResult
    data object AuthRejected : TokenResult
    data class CertificateError(val message: String) : TokenResult
    data class NetworkError(val message: String) : TokenResult
    data class ServerError(val code: Int) : TokenResult
}

/** Outcome of `POST /api/recordlocation`. */
sealed interface SendResult {
    data object Success : SendResult
    data object BadRequest : SendResult
    data object AuthRejected : SendResult
    data class RateLimited(val retryAfterSeconds: Long?) : SendResult
    data class CertificateChanged(val message: String) : SendResult
    data class NetworkError(val message: String) : SendResult
    data class ServerError(val code: Int) : SendResult
    data object SkippedByBackoff : SendResult
    data object NoFix : SendResult
    data object NotConfigured : SendResult
}

/** Outcome of `GET /api/locations`. */
sealed interface LocationsResult {
    data class Success(val locations: List<LocationDto>, val latest: LocationDto?) : LocationsResult
    data object AuthRequired : LocationsResult
    data class CertificateError(val message: String) : LocationsResult
    data class NetworkError(val message: String) : LocationsResult

    /**
     * The server answered, but the body could not be decoded. Distinct from
     * [NetworkError]: a malformed response is not a connectivity problem, and
     * must not be reported to the user as "no network / timeout".
     */
    data class ParseError(val message: String) : LocationsResult
    data class ServerError(val code: Int) : LocationsResult
}

// --- Actionable, human-readable messages. Never fail silently. ---

fun SendResult.userMessage(): String? = when (this) {
    SendResult.Success -> null
    SendResult.BadRequest -> "The server rejected the report (400): lat/lon were missing. No location was recorded."
    SendResult.AuthRejected ->
        "Authentication rejected by the server (401). The API secret may be wrong or was replaced — sign in again from Setup."
    is SendResult.RateLimited ->
        "Rate limited by the server (429). Backing off before the next report" +
            (retryAfterSeconds?.let { " (server asked to wait ${it}s)" } ?: "") + "."
    is SendResult.CertificateChanged ->
        "The server certificate changed or could not be verified, so nothing was sent. Re-check the fingerprint in Setup. ($message)"
    is SendResult.NetworkError ->
        "Cannot reach the instance (no network / timeout / DNS). Check connectivity. ($message)"
    is SendResult.ServerError ->
        "The instance returned a server error ($code). It may be down or misconfigured."
    SendResult.SkippedByBackoff ->
        "Skipped this tick: still backing off after a rate limit (429)."
    SendResult.NoFix ->
        "No location fix yet. Waiting for a GPS or network fix."
    SendResult.NotConfigured ->
        "Tracking is not configured. Sign in from Setup and start tracking again."
}

fun LoginResult.userMessage(): String? = when (this) {
    LoginResult.Success -> null
    LoginResult.InvalidCredentials -> "Login failed: wrong username or password."
    is LoginResult.CertificateError ->
        "Could not establish a trusted connection to the instance. Verify the certificate fingerprint. ($message)"
    is LoginResult.NetworkError -> "Could not reach the instance. Check the URL and your network. ($message)"
    is LoginResult.ServerError -> "The instance returned an unexpected status ($code) during login."
}

fun TokenResult.userMessage(): String? = when (this) {
    TokenResult.Success -> null
    TokenResult.AuthRejected ->
        "The server rejected the token update (401). Your session may have expired — sign in again."
    is TokenResult.CertificateError -> "TLS problem while updating the API token. ($message)"
    is TokenResult.NetworkError -> "Could not reach the instance to update the API token. ($message)"
    is TokenResult.ServerError -> "The instance returned an unexpected status ($code) while updating the API token."
}

fun LocationsResult.userMessage(): String? = when (this) {
    is LocationsResult.Success -> null
    LocationsResult.AuthRequired -> "Your session expired. Sign in again from Setup to see history."
    is LocationsResult.CertificateError -> "Certificate changed or could not be verified while loading history. ($message)"
    is LocationsResult.NetworkError -> "Could not load history: no network / timeout. ($message)"
    is LocationsResult.ParseError ->
        "Could not load history: the server's response could not be read. ($message)"
    is LocationsResult.ServerError -> "Could not load history: server error ($code)."
}
