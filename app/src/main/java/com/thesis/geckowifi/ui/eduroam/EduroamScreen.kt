package com.thesis.geckowifi.ui.eduroam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.eduroam.Decision
import com.thesis.geckowifi.eduroam.EduroamOutcome
import com.thesis.geckowifi.eduroam.EduroamStep
import com.thesis.geckowifi.eduroam.InstallOutcome
import com.thesis.geckowifi.eduroam.OnboardingIdentity
import com.thesis.geckowifi.eduroam.PinResult
import com.thesis.geckowifi.eduroam.PinSource
import com.thesis.geckowifi.eduroam.RealmCheck
import com.thesis.geckowifi.ui.components.DetailRow
import com.thesis.geckowifi.ui.components.SectionCard
import com.thesis.geckowifi.ui.components.StackedValue
import com.thesis.geckowifi.ui.components.VerdictBadge
import com.thesis.geckowifi.ui.components.colors
import com.thesis.geckowifi.ui.theme.Space
import com.thesis.geckowifi.ui.verdict.Tone

private val STEP_LABELS = mapOf(
    EduroamStep.ONBOARDING to "Join with the anonymous GECKO identity",
    EduroamStep.SITE to "Check the site's registration",
    EduroamStep.REALM to "Fetch your realm's signed record",
    EduroamStep.DECISION to "Decide whether your password is safe",
    EduroamStep.INSTALL to "Install the pinned profile",
    EduroamStep.CONNECT to "Connect with your account"
)

/**
 * eduroam setup through GECKO (Design A, docs/eduroam-demo.md): what eduroam
 * CAT does, from data obtained and verified in band. Laid out for a live
 * demo: the steps with their state, then one verdict, then the evidence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EduroamScreen(viewModel: EduroamViewModel, onBack: () -> Unit) {
    val target = viewModel.target
    Column(Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("Set up ${target?.ssid ?: "eduroam"}") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            }
        )
        if (target == null) {
            Text("This network is no longer in the scan list.", Modifier.padding(Space.lg), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg)
                .padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text(
                "GECKO sets up your eduroam profile itself: it joins with a public GECKO identity, fetches your " +
                    "institution's server identity, verifies it, and only then gives Android your password, pinned to that server.",
                style = MaterialTheme.typography.bodyMedium
            )

            SectionCard("Your account") {
                OutlinedTextField(
                    value = viewModel.identity,
                    onValueChange = { viewModel.identity = it },
                    label = { Text("Identity (user@realm)") },
                    singleLine = true,
                    enabled = !viewModel.running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = viewModel.password,
                    onValueChange = { viewModel.password = it },
                    label = { Text(if (viewModel.hasSavedPassword) "Password (saved; leave empty to keep)" else "Password") },
                    singleLine = true,
                    enabled = !viewModel.running,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = Space.sm)
                )
                if (viewModel.hasSavedPassword && !viewModel.running) {
                    TextButton(onClick = viewModel::forgetAccount) { Text("Forget saved password") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                if (viewModel.running) {
                    OutlinedButton(onClick = viewModel::cancel) { Text("Cancel") }
                } else {
                    Button(onClick = viewModel::start) { Text(if (viewModel.outcome == null) "Set up with GECKO" else "Run again") }
                }
            }
            viewModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Steps(viewModel.step, viewModel.outcome, viewModel.running)

            viewModel.outcome?.let { Result(it) }

            if (viewModel.outcome?.install is InstallOutcome.Installed) {
                TextButton(onClick = viewModel::removeProfile) { Text("Remove the installed profile") }
            }

            SectionCard("Access point") {
                DetailRow("SSID", target.ssid)
                DetailRow("BSSID", target.bssid ?: "any")
                if (!viewModel.inScan) DetailRow("Security", "not in the current scan; assuming WPA2-Enterprise", mono = false)
                DetailRow("Onboarding as", OnboardingIdentity.IDENTITY)
            }
            Text(
                "Prototype: Android asks to approve the GECKO connection each time, and once whether this app may " +
                    "suggest networks. A system-level GECKO wouldn't need either.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Steps(current: EduroamStep?, outcome: EduroamOutcome?, running: Boolean) {
    val reached = outcome?.timings?.map { it.first }.orEmpty()
    val failedAt = outcome?.let { o ->
        when {
            o.onboardingFailure != null -> EduroamStep.ONBOARDING
            o.decision is Decision.Block -> EduroamStep.DECISION
            o.install is InstallOutcome.Failed -> EduroamStep.INSTALL
            o.connected == false -> EduroamStep.CONNECT
            else -> null
        }
    }
    SectionCard("Steps") {
        EduroamStep.entries.forEach { step ->
            val state = when {
                running && step == current -> "running"
                step == failedAt -> "stopped"
                running && current != null && step.ordinal < current.ordinal -> "done"
                !running && step in reached -> "done"
                else -> "pending"
            }
            val elapsed = outcome?.timings?.firstOrNull { it.first == step }?.second
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { stateDescription = state },
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (state) {
                    "running" -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    "done" -> Icon(Icons.Outlined.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    "stopped" -> Icon(Icons.Outlined.Block, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    else -> Icon(Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.width(Space.md))
                Text(STEP_LABELS.getValue(step), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (elapsed != null && !running) {
                    Text("${elapsed} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Result(outcome: EduroamOutcome) {
    val decision = outcome.decision
    val (tone, label, icon) = when {
        decision is Decision.Block && decision.hostile -> Triple(Tone.DANGER, "Blocked: attack signs", Icons.Outlined.GppBad)
        decision is Decision.Block -> Triple(Tone.NEUTRAL, "Not set up: password not sent", Icons.Outlined.Shield)
        outcome.connected == true -> Triple(Tone.POSITIVE, "Connected with pinned profile", Icons.Outlined.VerifiedUser)
        outcome.install is InstallOutcome.Failed -> Triple(Tone.NEUTRAL, "Profile not installed", Icons.Outlined.Shield)
        else -> Triple(Tone.NEUTRAL, "Profile installed, not connected yet", Icons.Outlined.Shield)
    }
    val explanation = when {
        decision is Decision.Block -> "${decision.reason.replaceFirstChar { it.uppercase() }}. Your password was not given to Android."
        outcome.connected == true -> "Your institution's server proved its identity inside the handshake (${(decision as Decision.Proceed).why})."
        outcome.install is InstallOutcome.Failed -> (outcome.install as InstallOutcome.Failed).reason
        else -> "The pinned profile is installed (${(outcome.install as? InstallOutcome.Installed)?.detail}). Android joins it on its own " +
            "schedule; if a server can't prove the pinned identity, Android stops before sending your password."
    }

    SectionCard("Result") {
        VerdictBadge(tone, label, icon)
        Text(explanation, Modifier.padding(top = Space.sm), style = MaterialTheme.typography.bodyMedium)
    }

    SectionCard("What GECKO saw") {
        outcome.onboardingFailure?.let { StackedValue("Onboarding", it) }
        outcome.site?.let { site ->
            DetailRow("Site", site.state.label(), mono = false)
            site.matchedCertificateId?.let { DetailRow("Registration", it) }
            site.reason?.let { StackedValue("Site detail", it) }
        }
        (outcome.local as? PinResult.Rejected)?.let { StackedValue("Site registration for your realm", it.reason) }
        when (val realm = outcome.realm) {
            is RealmCheck.Pinned -> DetailRow("Realm record", "signature verified", mono = false)
            is RealmCheck.Rejected -> StackedValue("Realm record rejected", realm.reason, valueColor = Tone.DANGER.colors().content)
            is RealmCheck.Missing -> StackedValue("Realm record", realm.reason)
            null -> Unit
        }
        (decision as? Decision.Proceed)?.pins?.let { pins ->
            DetailRow("Method", "${pins.method} / ${pins.phase2}", mono = false)
            DetailRow("Outer identity", pins.outerIdentity)
            StackedValue("Pinned server name", pins.serverNames.joinToString("\n"))
            StackedValue("Pinned CA", pins.caCertificates.joinToString("\n") { it.subjectX500Principal.name })
            val source = pins.source
            DetailRow("Pins from", when (source) {
                is PinSource.RealmRecord -> "signed realm record (valid until ${source.expiresAt})"
                is PinSource.SiteCertificate -> "site registration ${source.certificateId}"
            }, mono = false)
        }
    }
}

private fun VerificationState.label(): String = when (this) {
    VerificationState.REGISTERED -> "Registered here"
    VerificationState.VERIFIED -> "Verified"
    VerificationState.UNVERIFIED -> "Not registered here"
    VerificationState.UNRECOGNIZED -> "Unrecognized"
    VerificationState.CONFLICT -> "Conflict"
    VerificationState.UNREACHABLE -> "Map server not reachable"
    VerificationState.CHECKING -> "Checking"
}
