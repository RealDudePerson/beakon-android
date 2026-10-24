package com.beakon.companion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.beakon.companion.net.CertificateInfo
import com.beakon.companion.ui.AppViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun SetupScreen(
    viewModel: AppViewModel,
    onLoggedIn: () -> Unit,
) {
    var acknowledged by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.loggedIn) {
        if (viewModel.loggedIn) onLoggedIn()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connect to Beakon", style = MaterialTheme.typography.headlineSmall)

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Before you continue", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Signing in will call POST /api/updatetoken with a newly generated secret. " +
                        "This OVERWRITES the account's existing API token. Any other client using " +
                        "the current token will stop working until it is reconfigured.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        OutlinedTextField(
            value = viewModel.instanceUrl,
            onValueChange = viewModel::onUrlChanged,
            label = { Text("Instance URL (https://…)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Optional diagnostic. A CA-signed server (trusted by the device) needs
        // no certificate step at all: sign in directly below.
        OutlinedButton(
            onClick = viewModel::probeCertificate,
            enabled = !viewModel.busy && viewModel.instanceUrl.isNotBlank(),
        ) {
            Text("Check certificate (optional)")
        }

        if (viewModel.certificateRejected && viewModel.certificateInfo == null) {
            Text(
                "The certificate is not trusted by this device. Check it to review its " +
                    "fingerprint, then choose whether to trust it.",
                color = MaterialTheme.colorScheme.error,
            )
        }

        viewModel.certificateInfo?.let { info ->
            CertificateCard(
                info = info,
                accepted = viewModel.certificateAccepted,
                onAcceptChange = viewModel::acceptCertificate,
            )
        }

        viewModel.certificateWarning?.let { warning ->
            Text(warning, color = MaterialTheme.colorScheme.error)
        }

        OutlinedTextField(
            value = viewModel.username,
            onValueChange = viewModel::onUsernameChanged,
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = viewModel.password,
            onValueChange = viewModel::onPasswordChanged,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
            Text("I understand the account's API token will be overwritten.")
        }

        // Deliberately NOT gated on a certificate fingerprint: platform trust is
        // the default path and needs no user interaction.
        Button(
            onClick = viewModel::completeSetup,
            enabled = !viewModel.busy &&
                acknowledged &&
                viewModel.username.isNotBlank() &&
                viewModel.password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign in and set API token")
        }

        viewModel.setupMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }

        if (viewModel.busy) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CertificateCard(
    info: CertificateInfo,
    accepted: Boolean,
    onAcceptChange: (Boolean) -> Unit,
) {
    val dateFormat = remember { DateFormat.getDateTimeInstance() }
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Server certificate", style = MaterialTheme.typography.titleMedium)
            Text(
                "This probe reads the certificate the server presents for this hostname. " +
                    "Hostname verification happens on the real request (sign-in and location " +
                    "reports), not in the probe.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Subject: ${info.subject}", style = MaterialTheme.typography.bodySmall)
            Text("Issuer: ${info.issuer}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Valid: ${dateFormat.format(Date(info.notBefore))} – ${dateFormat.format(Date(info.notAfter))}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (info.trustedByPlatform) {
                // Trusted by the device's roots: no fingerprint is shown or
                // required, and there is nothing to accept.
                Text(
                    "Trusted by this device. No fingerprint is needed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    "NOT trusted by this device. Review the fingerprint and choose to trust it " +
                        "only if you recognize it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text("SHA-256 fingerprint:", style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text(
                        info.sha256,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = accepted, onCheckedChange = onAcceptChange)
                    Text("I recognize this fingerprint and choose to trust it for ${info.host}")
                }
            }
        }
    }
}
