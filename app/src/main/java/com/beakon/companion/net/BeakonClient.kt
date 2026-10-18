package com.beakon.companion.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The session-authenticated calls the UI makes on a [BeakonClient].
 *
 * Extracted as an interface so JVM tests can substitute a fake that asserts
 * which dispatcher these blocking calls run on.
 */
interface BeakonSession {
    /** `POST /login` — blocking. */
    fun login(username: String, password: String): LoginResult

    /** `POST /api/updatetoken` — blocking. Overwrites the account's API token. */
    fun setApiToken(secret: String): TokenResult

    /** The current `session` cookie value, if any. */
    fun sessionCookieValue(): String?

    /** `GET /api/locations` — blocking, session-authenticated. */
    fun fetchLocations(date: String? = null): LocationsResult
}

/**
 * HTTP client for a single Beakon instance.
 *
 * Every connection is TLS with [PinningTrustManager] using [pinnedSha256]. The
 * `session` cookie (from login) authenticates the session endpoints; reporting
 * uses the `username`/`secret` headers.
 */
class BeakonClient(
    baseUrl: String,
    pinnedSha256: String?,
    initialSession: String? = null,
    connectTimeoutMs: Long = 15_000,
    readTimeoutMs: Long = 20_000,
) : BeakonSession {

    val baseUrl: String = BeakonUrl.normalize(baseUrl)
        ?: throw IllegalArgumentException("Invalid instance URL: $baseUrl")

    private val cookieJar = SimpleCookieJar()
    private val json = Json { ignoreUnknownKeys = true }
    private val client: OkHttpClient = run {
        val (socketFactory, trustManager) = TlsSupport.pinningSocketFactory(pinnedSha256)
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .sslSocketFactory(socketFactory, trustManager)
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }

    init {
        if (!initialSession.isNullOrBlank()) {
            BeakonUrl.host(baseUrl)?.let { cookieJar.seed(it, "session", initialSession) }
        }
    }

    fun hasSession(): Boolean = cookieJar.cookie("session") != null

    /** The current `session` cookie value, if any (persisted so history survives restart). */
    override fun sessionCookieValue(): String? = cookieJar.cookie("session")?.value

    /** `POST /login` — form-encoded username + password. */
    override fun login(username: String, password: String): LoginResult {
        val body = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .build()
        val request = Request.Builder().url("$baseUrl/login").post(body).build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> LoginResult.InvalidCredentials
                    cookieJar.cookie("session") != null -> LoginResult.Success
                    response.isSuccessful || response.isRedirect -> LoginResult.InvalidCredentials
                    else -> LoginResult.ServerError(response.code)
                }
            }
        } catch (e: SSLPeerUnverifiedException) {
            LoginResult.CertificateError("hostname verification failed: ${e.message}")
        } catch (e: SSLException) {
            LoginResult.CertificateError(e.message ?: "TLS handshake failed")
        } catch (e: IOException) {
            LoginResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    /** `POST /api/updatetoken` — overwrites the user's existing API token. Session-authenticated. */
    override fun setApiToken(secret: String): TokenResult {
        val body = FormBody.Builder().add("secret", secret).build()
        val request = Request.Builder().url("$baseUrl/api/updatetoken").post(body).build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> TokenResult.Success
                    response.code == 401 || response.code == 403 -> TokenResult.AuthRejected
                    else -> TokenResult.ServerError(response.code)
                }
            }
        } catch (e: SSLPeerUnverifiedException) {
            TokenResult.CertificateError("hostname verification failed: ${e.message}")
        } catch (e: SSLException) {
            TokenResult.CertificateError(e.message ?: "TLS handshake failed")
        } catch (e: IOException) {
            TokenResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    /** `POST /api/recordlocation` — authenticated by `username`/`secret` headers. */
    fun recordLocation(username: String, secret: String, report: LocationReport): SendResult {
        val requestBuilder = Request.Builder()
            .url("$baseUrl/api/recordlocation")
            .post(RecordLocationRequestBuilder.body(report).toRequestBody(JSON_MEDIA_TYPE))
        RecordLocationRequestBuilder.headers(username, secret)
            .forEach { (name, value) -> requestBuilder.header(name, value) }
        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                when (response.code) {
                    200, 201 -> SendResult.Success
                    400 -> SendResult.BadRequest
                    401, 403 -> SendResult.AuthRejected
                    429 -> SendResult.RateLimited(parseRetryAfter(response))
                    else -> SendResult.ServerError(response.code)
                }
            }
        } catch (e: SSLPeerUnverifiedException) {
            SendResult.CertificateChanged("hostname verification failed: ${e.message}")
        } catch (e: SSLException) {
            SendResult.CertificateChanged(e.message ?: "TLS handshake failed")
        } catch (e: IOException) {
            SendResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    /** `GET /api/locations` — session-authenticated, optional `?date=`. */
    override fun fetchLocations(date: String?): LocationsResult {
        val urlBuilder = "$baseUrl/api/locations".toHttpUrlOrNull()?.newBuilder()
            ?: return LocationsResult.NetworkError("invalid instance URL")
        if (!date.isNullOrBlank()) {
            urlBuilder.addQueryParameter("date", date)
        }
        val request = Request.Builder().url(urlBuilder.build()).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> LocationsResult.AuthRequired
                    !response.isSuccessful -> LocationsResult.ServerError(response.code)
                    else -> {
                        val body = response.body?.string().orEmpty()
                        val parsed = json.decodeFromString(LocationsResponse.serializer(), body)
                        LocationsResult.Success(parsed.locations, parsed.latest)
                    }
                }
            }
        } catch (e: SSLPeerUnverifiedException) {
            LocationsResult.CertificateError("hostname verification failed: ${e.message}")
        } catch (e: SSLException) {
            LocationsResult.CertificateError(e.message ?: "TLS handshake failed")
        } catch (e: IOException) {
            LocationsResult.NetworkError(e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            LocationsResult.NetworkError("unreadable response: ${e.message}")
        }
    }

    private fun parseRetryAfter(response: Response): Long? =
        response.header("Retry-After")?.trim()?.toLongOrNull()

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
