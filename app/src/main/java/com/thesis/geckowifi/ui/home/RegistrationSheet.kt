package com.thesis.geckowifi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.thesis.geckowifi.data.model.WiFiAuthMode
import com.thesis.geckowifi.network.ScannedNetwork
import com.thesis.geckowifi.ui.components.DetailRow
import com.thesis.geckowifi.ui.components.SectionCard
import com.thesis.geckowifi.ui.components.VerdictBadge
import com.thesis.geckowifi.ui.theme.Space
import com.thesis.geckowifi.ui.verdict.Tone
import com.thesis.geckowifi.verification.AdvertisedMatch

/** Plain-language name of a security type, as Android's own settings word it. */
fun WiFiAuthMode.displayName(): String = when (this) {
    WiFiAuthMode.OPEN -> "Open"
    WiFiAuthMode.OWE -> "Enhanced Open"
    WiFiAuthMode.WEP -> "WEP"
    WiFiAuthMode.WPA2_PERSONAL -> "WPA2-Personal"
    WiFiAuthMode.WPA3_PERSONAL -> "WPA3-Personal"
    WiFiAuthMode.WPA2_ENTERPRISE -> "WPA2-Enterprise"
    WiFiAuthMode.WPA3_ENTERPRISE -> "WPA3-Enterprise"
    WiFiAuthMode.WPA3_ENTERPRISE_192 -> "WPA3-Enterprise 192-bit"
}

/** Registered wire names in plain language; a name this app doesn't know is shown as registered. */
fun List<String>.securityNames(): String =
    joinToString(" or ") { wire -> WiFiAuthMode.fromWire(wire)?.displayName() ?: wire }

fun Set<WiFiAuthMode>.securityNames(): String =
    if (isEmpty()) "unrecognised" else sortedBy { it.ordinal }.joinToString(" and ") { it.displayName() }

/**
 * The root-only login-server check offered for a registered enterprise network
 * ([com.thesis.geckowifi.ui.VerificationViewModel.checkLoginServer]).
 * [connected]: the device is on this network now; the check needs that.
 */
data class LoginServerCheck(val connected: Boolean, val onCheck: () -> Unit)

/**
 * What GECKO knows about a registered network before anyone connects. Opened
 * from the list for a registered network that isn't (or can't be) joined:
 * a secured one, or one whose security type contradicts its registration.
 * Never claims verification; on a mismatch the safe action leads.
 *
 * [onConnectAnyway] is offered only for an open mismatching network, which the
 * app can join for a full check; it is deliberately the quiet action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrationSheet(
    network: ScannedNetwork.Real,
    match: AdvertisedMatch,
    onDismiss: () -> Unit,
    onConnectAnyway: (() -> Unit)?,
    loginServerCheck: LoginServerCheck? = null,
    /** GECKO's eduroam setup (pinned profile from verified data); offered for enterprise networks that match. */
    onSetup: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val mismatch = match as? AdvertisedMatch.Mismatch
    val certs = match.certificates
    val operator = certs.mapNotNull { it.subject?.name }.distinct().singleOrNull()
    val enterprise = certs.firstNotNullOfOrNull { it.wifi.enterprise }
    val servers = enterprise?.aaa.orEmpty().flatMap { it.servers }.flatMap { it.names }.distinct()
    val realms = enterprise?.aaa.orEmpty().flatMap { it.realms }.distinct()
    val registeredModes = certs.flatMap { it.wifi.authModeNames }.distinct()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg)
                .padding(bottom = Space.lg)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text(network.ssid, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            if (mismatch != null) {
                VerdictBadge(Tone.DANGER, "Doesn't match its registration", Icons.Outlined.GppBad)
            } else {
                VerdictBadge(Tone.NEUTRAL, "Registered, not verified", Icons.Outlined.Shield)
            }

            Text(
                when {
                    mismatch != null ->
                        "${network.ssid} is registered here as ${mismatch.registered.securityNames()}, but this network is " +
                            "${mismatch.advertised.securityNames()}. The real network doesn't look like this, so someone may be imitating it. Don't connect."
                    match is AdvertisedMatch.Undecided ->
                        "${network.ssid} is registered here, but GECKO can't compare this network with the registration: ${match.reason}."
                    enterprise != null ->
                        "GECKO hasn't verified this network. It only confirms that ${operator ?: "an operator"} registered ${network.ssid} here " +
                            "and that this network uses the registered security type. A fake network can look the same from outside."
                    else ->
                        "GECKO hasn't verified this network. It only confirms that ${operator ?: "an operator"} registered ${network.ssid} here " +
                            "and that this network uses the registered security type."
                },
                style = MaterialTheme.typography.bodyLarge
            )

            SectionCard("Registration") {
                operator?.let { DetailRow("Registered by", it, mono = false) }
                DetailRow("Registered security", registeredModes.securityNames().ifEmpty { "Not specified" }, mono = false)
                DetailRow("This network", network.authModes.securityNames(), mono = false)
                if (servers.isNotEmpty()) DetailRow("Login server", servers.joinToString("\n"))
                if (realms.isNotEmpty()) DetailRow("For accounts", realms.joinToString("\n") { "@$it" })
                enterprise?.federation?.takeIf { it.isNotBlank() }?.let { DetailRow("Roaming", it, mono = false) }
            }

            if (mismatch == null && enterprise != null && loginServerCheck != null) {
                SectionCard("Login server") {
                    Text(
                        if (loginServerCheck.connected)
                            "GECKO can compare the login server of your current connection with the registration. " +
                                "Start the check, then turn Wi-Fi off and on so your device connects again."
                        else
                            "Connect to ${network.ssid} in your device's settings first. GECKO then compares its login server with the registration.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = loginServerCheck.onCheck,
                        enabled = loginServerCheck.connected,
                        modifier = Modifier.padding(top = Space.md)
                    ) { Text("Check login server") }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.End)) {
                if (onSetup != null) {
                    TextButton(onClick = onSetup) { Text("Set up with GECKO") }
                }
                if (mismatch != null && onConnectAnyway != null) {
                    TextButton(onClick = onConnectAnyway) { Text("Connect anyway and check") }
                }
                Button(onClick = onDismiss) { Text(if (mismatch != null) "Don't connect" else "Done") }
            }
        }
    }
}
