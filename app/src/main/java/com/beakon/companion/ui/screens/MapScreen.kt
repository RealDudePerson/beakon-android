package com.beakon.companion.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.beakon.companion.net.BeakonUrl
import com.beakon.companion.net.CertificateFingerprint
import com.beakon.companion.ui.AppViewModel

/**
 * Map tab: the Beakon web dashboard in a [WebView].
 *
 * The native osmdroid map is gone — it duplicated what the server already
 * renders (history polyline, latest marker, tile layer) and drifted from it.
 * The dashboard is loaded at the instance base URL with the app's `session`
 * cookie synced into the WebView's cookie jar first, so a signed-in user
 * lands on the dashboard without logging in again. If the session has
 * expired the dashboard shows its own login page inside the WebView.
 *
 * TLS: the WebView uses the platform trust store, like the rest of the app.
 * An SSL error is cancelled unless the presented certificate's SHA-256
 * matches a fingerprint the user explicitly accepted for this host (see
 * [Dashboard.shouldProceedOnSslError]) — the same pinning rule the API
 * client uses, never a blanket proceed.
 */
@Composable
fun MapScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val dashboardUrl = viewModel.dashboardUrl()
    // Read once per composition: the session and the URL do not change while
    // this tab is showing, and the cookie is synced before the first load.
    val sessionCookie = viewModel.sessionCookieValue()
    val loading = remember { mutableStateOf(true) }
    val error = remember { mutableStateOf<String?>(null) }

    val webView = remember {
        WebView(context).apply {
            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true // The dashboard is a JS map page.
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = DashboardWebViewClient(
                dashboardHost = dashboardUrl?.let { BeakonUrl.host(it) },
                pinnedFingerprintFor = { host -> viewModel.pinnedFingerprintFor(host) },
                loading = loading,
                error = error,
                openExternal = { uri ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                },
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    LaunchedEffect(dashboardUrl) {
        val url = dashboardUrl
        if (url == null) {
            loading.value = false
            error.value = "Not signed in. Complete Setup first."
            return@LaunchedEffect
        }
        // Sync the app's session cookie into the WebView's jar so the
        // dashboard loads signed in. Without this the dashboard would show
        // its login page even though the app is logged in.
        val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }
        if (!sessionCookie.isNullOrBlank()) {
            cookies.setCookie(url, "session=$sessionCookie")
        }
        cookies.flush()
        if (webView.url != url) {
            loading.value = true
            error.value = null
            webView.loadUrl(url)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (dashboardUrl != null) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (loading.value) {
            LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
        }

        if (dashboardUrl != null) {
            IconButton(
                onClick = { webView.reload() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload dashboard")
            }
        }

        error.value?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }
}

/** Stays in-app for the dashboard host; external links open in the browser. */
private class DashboardWebViewClient(
    private val dashboardHost: String?,
    private val pinnedFingerprintFor: (String) -> String?,
    private val loading: MutableState<Boolean>,
    private val error: MutableState<String?>,
    private val openExternal: (Uri) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        return if (uri.host == dashboardHost) {
            false // Same host: stay in the WebView.
        } else {
            openExternal(uri)
            true
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        loading.value = true
        error.value = null
    }

    override fun onPageFinished(view: WebView, url: String) {
        loading.value = false
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) {
            loading.value = false
            this.error.value = "Could not load the dashboard: ${error.description}"
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val host = error.url?.let { BeakonUrl.host(it) }
        val presented = runCatching {
            error.certificate?.x509Certificate?.let { CertificateFingerprint.sha256(it) }
        }.getOrNull()
        val pinned = host?.let { pinnedFingerprintFor(it) }
        if (Dashboard.shouldProceedOnSslError(presented, pinned)) {
            handler.proceed()
        } else {
            handler.cancel()
            loading.value = false
            this.error.value =
                "The dashboard's certificate is not trusted by this device. " +
                    "If the server uses a self-signed certificate, accept its " +
                    "fingerprint in Setup first."
        }
    }
}
