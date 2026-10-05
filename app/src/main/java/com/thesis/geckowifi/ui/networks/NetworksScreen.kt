package com.thesis.geckowifi.ui.networks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.network.ScannedNetwork
import com.thesis.geckowifi.ui.VerificationViewModel
import com.thesis.geckowifi.verification.LookupStatus
import com.thesis.geckowifi.ui.theme.Divider
import com.thesis.geckowifi.ui.theme.OnSurfaceMuted
import com.thesis.geckowifi.ui.theme.OnSurfaceVariant
import com.thesis.geckowifi.ui.theme.Outline
import com.thesis.geckowifi.ui.theme.SurfaceContainerHigh

/**
 * "Registered means a certificate claims this network belongs here. The
 * network is actually checked when you connect." - matches the design's own
 * copy, and is an accurate description of what this screen shows: presence
 * in a broad (non-SSID-comparison) GECKO query, not a completed trust
 * decision. The "Mismatch" pre-connection badge from the original design is
 * deliberately not implemented here - see README.md/the implementation plan
 * for why (would need heuristic parsing of scan capability strings against
 * registered auth mode).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworksScreen(
    viewModel: VerificationViewModel,
    onRequestLocationPermission: () -> Unit,
    onNetworkClick: (ScannedNetwork) -> Unit
) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    // Only trust the registered/not-registered split when the lookup actually
    // succeeded; otherwise an empty list would mislabel every network as "not
    // registered" when we simply couldn't ask (see the banner below).
    val known = viewModel.certLookup == LookupStatus.OK
    val registeredSsids = viewModel.registeredHere.mapNotNull { it.wifi.ssid }.toSet()
    val groups = groupBySsid(viewModel.scannedNetworks)
    val (registered, other) = groups.partition { group ->
        registeredSsids.any { it.equals(group.primary.ssid, ignoreCase = true) }
    }

    Column(Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("Networks") },
            actions = {
                viewModel.location?.let { fix ->
                    Text(
                        "±${fix.accuracyMeters.toInt()} m",
                        modifier = Modifier.padding(end = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = OnSurfaceVariant
                    )
                }
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                }
            }
        )
        Text(
            "Registered means a certificate claims this network belongs here. " +
                "The network is actually checked when you connect.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMuted
        )
        if (viewModel.hasLocationPermission && !known) {
            LookupUnknownBanner(viewModel.certLookup)
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            if (!known) {
                // Registration unknown: show every network once, chips as "unknown".
                items(groups) { group ->
                    NetworkRow(group, Registration.UNKNOWN, certs = emptyList(), onClick = { onNetworkClick(group.primary) })
                }
            } else {
                if (registered.isNotEmpty()) {
                    item { SectionHeader("Registered here") }
                    items(registered) { group ->
                        val certs = viewModel.registeredHere.filter { it.wifi.ssid.equals(group.primary.ssid, ignoreCase = true) }
                        NetworkRow(group, Registration.REGISTERED, certs = certs, onClick = { onNetworkClick(group.primary) })
                    }
                }
                if (other.isNotEmpty()) {
                    item { SectionHeader("Other networks") }
                    items(other) { group ->
                        NetworkRow(group, Registration.NOT_REGISTERED, certs = emptyList(), onClick = { onNetworkClick(group.primary) })
                    }
                }
            }
            if (!viewModel.hasLocationPermission) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text("Location permission is required to scan for networks.", color = OnSurfaceMuted)
                        androidx.compose.material3.TextButton(
                            onClick = onRequestLocationPermission,
                            modifier = Modifier.padding(top = 4.dp)
                        ) { Text("Grant permission") }
                    }
                }
            } else if (viewModel.scannedNetworks.isEmpty()) {
                item {
                    Text("No networks found.", modifier = Modifier.padding(16.dp), color = OnSurfaceMuted)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = OnSurfaceVariant
    )
}

/**
 * One list row per SSID. Android reports every access point (BSSID) as its
 * own scan result, so a campus network with many APs on two bands would
 * otherwise appear dozens of times. [primary] is the strongest AP; the
 * individual APs are picked from on Network Detail.
 */
private class NetworkGroup(val primary: ScannedNetwork, val accessPoints: List<ScannedNetwork.Real>)

private fun groupBySsid(networks: List<ScannedNetwork>): List<NetworkGroup> {
    val real = networks.filterIsInstance<ScannedNetwork.Real>()
        .groupBy { it.ssid }
        .values
        .map { aps -> aps.sortedByDescending { it.rssi } }
        .sortedByDescending { it.first().rssi }
        .map { aps -> NetworkGroup(aps.first(), aps) }
    // Demo entries are deliberately distinct rows even when they share an SSID (benign vs evil twin).
    val fake = networks.filter { it !is ScannedNetwork.Real }.map { NetworkGroup(it, emptyList()) }
    return real + fake
}

private fun channelSummary(aps: List<ScannedNetwork.Real>): String {
    val channels = aps.mapNotNull { it.channel }.distinct().sorted()
    return when {
        aps.size == 1 -> listOfNotNull(aps[0].channel?.let { "ch $it" }, aps[0].band).joinToString(" · ")
        channels.isEmpty() -> "${aps.size} access points"
        else -> "${aps.size} access points · ch ${channels.joinToString(", ")}"
    }
}

private fun subtitleFor(group: NetworkGroup, certs: List<GeoCertificate>): String = when (val network = group.primary) {
    is ScannedNetwork.Real -> listOf(network.securityLabel, channelSummary(group.accessPoints))
        .filter { it.isNotEmpty() }.joinToString(" · ")
    is ScannedNetwork.FakeCaptivePortal -> "Open · claims ${network.presumedDomain}"
    is ScannedNetwork.FakeEduroam -> "WPA2-Enterprise · claims ${network.presumedAuthServerName}"
}.let { base ->
    val authMode = certs.firstOrNull()?.wifi?.authMode
    if (authMode != null) "$base · Registered as $authMode" else base
}

private enum class Registration { REGISTERED, NOT_REGISTERED, UNKNOWN }

@Composable
private fun NetworkRow(
    group: NetworkGroup,
    registration: Registration,
    certs: List<GeoCertificate>,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Outlined.Wifi, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(group.primary.ssid, style = MaterialTheme.typography.bodyLarge)
            Text(subtitleFor(group, certs), style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
        }
        RegisteredChip(registration)
    }
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 56.dp)
    ) {
        androidx.compose.material3.HorizontalDivider(color = Divider)
    }
}

@Composable
private fun RegisteredChip(registration: Registration) {
    val label = when (registration) {
        Registration.REGISTERED -> "Registered"
        Registration.NOT_REGISTERED -> "Not registered"
        Registration.UNKNOWN -> "Unknown"
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (registration == Registration.REGISTERED) SurfaceContainerHigh
        else androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, Outline)
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.HelpOutline, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Shown when the "what's registered here" lookup didn't succeed, so the chips can't be trusted. */
@Composable
private fun LookupUnknownBanner(status: LookupStatus?) {
    val message = when (status) {
        LookupStatus.UNREACHABLE ->
            "Can't reach the map server, so it's unknown which networks are registered here. " +
                "On this device the server is only reachable once you connect to a network."
        LookupStatus.UNTRUSTED ->
            "The map server's answer couldn't be verified, so registration here is unknown."
        else ->
            "Registration here hasn't been looked up yet."
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceContainerHigh,
        border = BorderStroke(1.dp, Outline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceVariant
        )
    }
}
