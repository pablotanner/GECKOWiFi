package com.thesis.geckowifi.ui.networkdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.network.ScannedNetwork
import com.thesis.geckowifi.ui.VerificationViewModel
import com.thesis.geckowifi.ui.components.StateBadge
import com.thesis.geckowifi.ui.theme.Divider
import com.thesis.geckowifi.ui.theme.MonoFontFamily
import com.thesis.geckowifi.ui.theme.OnSurfaceMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The manual "check this network" flow for a real, scanned network - this
 * app has no automatic captive-portal domain detection yet (see README.md),
 * so the observed domain still has to be typed in, standing in for what
 * root-based traffic capture would eventually supply automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDetailScreen(
    network: ScannedNetwork.Real,
    viewModel: VerificationViewModel,
    onCheckResult: (VerificationResult) -> Unit,
    onBack: () -> Unit
) {
    var domain by remember { mutableStateOf("") }
    // Every AP broadcasting this SSID, strongest first - e.g. Router A and B both as "GeckoTest".
    // The check joins exactly the selected one (BSSID-pinned), so A-vs-B tests are deterministic.
    val accessPoints = viewModel.scannedNetworks
        .filterIsInstance<ScannedNetwork.Real>()
        .filter { it.ssid == network.ssid }
        .sortedByDescending { it.rssi }
        .ifEmpty { listOf(network) }
    var selectedBssid by remember(network.ssid) { mutableStateOf(network.bssid) }
    val selected = accessPoints.firstOrNull { it.bssid == selectedBssid } ?: accessPoints.first()
    val networkKey = selected.bssid ?: selected.ssid
    val certs = viewModel.registeredHere.filter { it.wifi.ssid.equals(network.ssid, ignoreCase = true) }
    val registeredDomain = viewModel.registeredDomainFor(network.ssid)
    val history = viewModel.historyFor(networkKey)

    Column(Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text(network.ssid) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(Modifier.padding(16.dp)) {
            if (certs.isEmpty()) {
                Text("Nothing registered here for this SSID.", color = OnSurfaceMuted)
            } else {
                InfoCard {
                    InfoRow("Registered here", registeredDomain ?: "(no captive-portal domain on this certificate)")
                }
                InfoCard {
                    certs.forEach { cert ->
                        InfoRow("Registered certificate", cert.certificateId)
                        Text(
                            "Valid until ${cert.notValidAfter}",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted
                        )
                    }
                }
            }

            Text(
                if (accessPoints.size == 1) "Access point" else "Access points (${accessPoints.size})",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            accessPoints.forEach { ap ->
                AccessPointRow(ap, isSelected = ap.bssid == selected.bssid, onSelect = { selectedBssid = ap.bssid })
            }

            Text(
                "Domain (optional)",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Empty = detect the captive portal") }
            )
            Button(
                onClick = { viewModel.checkReal(selected, domain, onCheckResult) },
                enabled = viewModel.connectingTo == null,
                modifier = Modifier.padding(top = 8.dp)
            ) { Text(if (viewModel.connectingTo != null) "Connecting…" else "Connect & check") }

            Text(
                "History",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
            )
            if (history.isEmpty()) {
                Text("No checks yet.", color = OnSurfaceMuted)
            } else {
                history.sortedByDescending { it.timestamp }.forEach { record ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(record.timestamp)),
                            modifier = Modifier.weight(1f)
                        )
                        StateBadge(
                            runCatching { com.thesis.geckowifi.data.model.VerificationState.valueOf(record.state) }
                                .getOrDefault(com.thesis.geckowifi.data.model.VerificationState.UNVERIFIED)
                        )
                    }
                    HorizontalDivider(color = Divider)
                }
            }
        }
    }
}

@Composable
private fun AccessPointRow(ap: ScannedNetwork.Real, isSelected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = isSelected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(ap.bssid ?: "(unknown BSSID)", fontFamily = MonoFontFamily, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(
                    ap.channel?.let { "ch $it" },
                    ap.band,
                    ap.rssi.takeIf { it != Int.MIN_VALUE }?.let { "$it dBm" },
                    ap.securityLabel
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted
            )
        }
    }
}

@Composable
private fun InfoCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = com.thesis.geckowifi.ui.theme.SurfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
        Text(value, fontFamily = MonoFontFamily, style = MaterialTheme.typography.bodyLarge)
    }
}
