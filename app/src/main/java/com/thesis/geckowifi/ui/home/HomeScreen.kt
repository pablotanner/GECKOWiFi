package com.thesis.geckowifi.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NetworkWifi1Bar
import androidx.compose.material.icons.outlined.NetworkWifi2Bar
import androidx.compose.material.icons.outlined.NetworkWifi3Bar
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SignalWifi4Bar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.network.ScannedNetwork
import com.thesis.geckowifi.ui.CheckDetail
import com.thesis.geckowifi.ui.VerificationViewModel
import com.thesis.geckowifi.ui.components.SectionHeader
import com.thesis.geckowifi.ui.components.VerdictBadge
import com.thesis.geckowifi.ui.theme.MonoFontFamily
import com.thesis.geckowifi.ui.theme.Space
import com.thesis.geckowifi.ui.verdict.verdict
import com.thesis.geckowifi.verification.LookupStatus

/**
 * The app's one main screen: what the device is connected to and whether
 * GECKO vouches for it (the status card), then the networks to pick from.
 *
 * Picking a network is the only decision the user makes. Joining it starts
 * the check automatically; there is no separate "check" step, no domain to
 * type and no access point to choose. Those exist only in lab mode, for
 * running the testbed scenarios (Router A vs. B, a manual domain).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: VerificationViewModel,
    onOpenCheck: (String) -> Unit
) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    var labTarget by remember { mutableStateOf<ScannedNetwork.Real?>(null) }

    val known = viewModel.certLookup == LookupStatus.OK
    val registeredSsids = viewModel.registeredHere.mapNotNull { it.wifi.ssid?.lowercase() }.toSet()
    val rows = buildRows(viewModel.scannedNetworks, grouped = !viewModel.labMode)
    val busy = viewModel.activeCheck != null

    val connect: (ScannedNetwork) -> Unit = { network ->
        if (viewModel.labMode && network is ScannedNetwork.Real && network.isOpen) labTarget = network
        else viewModel.startCheck(network)
    }

    Column(Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("GECKO WiFi") },
            actions = {
                if (viewModel.labMode) {
                    Icon(
                        Icons.Outlined.Science,
                        contentDescription = "Lab mode is on",
                        modifier = Modifier.padding(end = Space.sm),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (viewModel.isRefreshing) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !busy) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Scan again")
                    }
                }
            }
        )

        LazyColumn(contentPadding = PaddingValues(bottom = Space.xl)) {
            item {
                StatusCard(
                    viewModel = viewModel,
                    onOpenCheck = onOpenCheck,
                    modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)
                )
            }

            val real = rows.filter { it.primary is ScannedNetwork.Real }
            val demo = rows.filter { it.primary !is ScannedNetwork.Real }
            val (checkable, secured) = real.partition { (it.primary as ScannedNetwork.Real).isOpen }

            if (known) {
                val (registered, other) = checkable.partition { it.primary.ssid.lowercase() in registeredSsids }
                networkSection("Registered here", registered, true, busy, viewModel, connect)
                networkSection("Other open networks", other, false, busy, viewModel, connect)
            } else {
                networkSection("Open networks", checkable, false, busy, viewModel, connect)
            }
            networkSection("Demo networks", demo, false, busy, viewModel, connect)
            networkSection("Secured networks", secured, false, busy, viewModel, connect)

            if (rows.isEmpty()) {
                item {
                    Text(
                        if (viewModel.isRefreshing) "Scanning for networks." else "No networks found. Scan again or move closer to an access point.",
                        modifier = Modifier.padding(Space.lg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    labTarget?.let { target ->
        LabConnectSheet(
            network = target,
            registeredDomain = viewModel.registeredDomainFor(target.ssid),
            onDismiss = { labTarget = null },
            onConnect = { domain ->
                labTarget = null
                viewModel.startCheck(target, domain)
            }
        )
    }
}

/**
 * One list row. In the normal view Android's per-access-point scan results
 * are folded into one row per SSID (a campus network would otherwise appear
 * dozens of times) and the strongest AP is joined. Lab mode lists every AP.
 */
private class NetworkRow(val primary: ScannedNetwork, val accessPoints: List<ScannedNetwork.Real>)

private fun buildRows(networks: List<ScannedNetwork>, grouped: Boolean): List<NetworkRow> {
    val real = networks.filterIsInstance<ScannedNetwork.Real>()
    val realRows = if (grouped) {
        real.groupBy { it.ssid }.values
            .map { aps -> aps.sortedByDescending { it.rssi } }
            .sortedByDescending { it.first().rssi }
            .map { NetworkRow(it.first(), it) }
    } else {
        real.sortedWith(compareBy<ScannedNetwork.Real> { it.ssid.lowercase() }.thenByDescending { it.rssi })
            .map { NetworkRow(it, listOf(it)) }
    }
    // Demo entries stay separate rows even when they share an SSID (benign vs. evil twin).
    return realRows + networks.filter { it !is ScannedNetwork.Real }.map { NetworkRow(it, emptyList()) }
}

private fun LazyListScope.networkSection(
    title: String,
    rows: List<NetworkRow>,
    registered: Boolean,
    busy: Boolean,
    viewModel: VerificationViewModel,
    onConnect: (ScannedNetwork) -> Unit
) {
    if (rows.isEmpty()) return
    item { SectionHeader(title) }
    items(rows, key = { "$title:${it.primary.ssid}:${it.primary.bssid}" }) { row ->
        val latest = row.accessPoints.ifEmpty { listOf(row.primary) }
            .mapNotNull { viewModel.latestCheckFor(it.bssid ?: it.ssid) }
            .maxByOrNull { it.result.timestamp }
        NetworkListItem(
            row = row,
            registered = registered,
            latest = latest,
            labMode = viewModel.labMode,
            enabled = !busy,
            onClick = { onConnect(row.primary) }
        )
    }
}

@Composable
private fun NetworkListItem(
    row: NetworkRow,
    registered: Boolean,
    latest: CheckDetail?,
    labMode: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val network = row.primary
    val real = network as? ScannedNetwork.Real
    val checkable = real == null || real.isOpen
    val colors = MaterialTheme.colorScheme
    val content = if (checkable) colors.onSurface else colors.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled && checkable, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.md)
            .semantics { if (!checkable) stateDescription = "Can't be checked" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.lg)
    ) {
        Icon(signalIcon(real?.rssi), contentDescription = null, tint = content)
        Column(Modifier.weight(1f)) {
            Text(network.ssid, style = MaterialTheme.typography.bodyLarge, color = content)
            val supporting = supportingText(row, labMode)
            if (supporting.isNotEmpty()) {
                Text(
                    supporting,
                    style = if (labMode && real != null) MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily)
                    else MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
            if (registered) {
                Text("Registered with GECKO", style = MaterialTheme.typography.labelMedium, color = colors.primary)
            }
        }
        when {
            !checkable -> Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.onSurfaceVariant)
            // Only results worth remembering in the list: a confirmation and, above all, a warning.
            latest != null && latest.result.state in setOf(VerificationState.VERIFIED, VerificationState.CONFLICT) -> {
                val v = latest.verdict()
                VerdictBadge(v.tone, v.label, v.icon)
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 56.dp), color = colors.outlineVariant)
}

private fun supportingText(row: NetworkRow, labMode: Boolean): String = when (val n = row.primary) {
    is ScannedNetwork.Real -> when {
        !n.isOpen -> "${n.securityLabel}. Can't be checked yet"
        labMode -> listOfNotNull(n.bssid, n.channel?.let { "ch $it" }, n.rssi.takeIf { it != Int.MIN_VALUE }?.let { "$it dBm" })
            .joinToString("  ")
        row.accessPoints.size > 1 -> "Open, ${row.accessPoints.size} access points"
        else -> "Open"
    }
    is ScannedNetwork.FakeCaptivePortal -> "Simulated ${n.label}" + if (labMode) ", presents ${n.presumedDomain}" else ""
    is ScannedNetwork.FakeEduroam -> "Simulated ${n.label}" + if (labMode) ", login server ${n.presumedAuthServerName}" else ""
}

private fun signalIcon(rssi: Int?): ImageVector = when {
    rssi == null || rssi == Int.MIN_VALUE -> Icons.Outlined.SignalWifi4Bar
    rssi >= -55 -> Icons.Outlined.SignalWifi4Bar
    rssi >= -67 -> Icons.Outlined.NetworkWifi3Bar
    rssi >= -78 -> Icons.Outlined.NetworkWifi2Bar
    else -> Icons.Outlined.NetworkWifi1Bar
}

/** Lab mode only: the exact access point is already chosen by the row; this adds the manual domain override. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabConnectSheet(
    network: ScannedNetwork.Real,
    registeredDomain: String?,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit
) {
    var domain by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = Space.xl).padding(bottom = Space.xl)) {
            Text(network.ssid, style = MaterialTheme.typography.headlineSmall)
            Text(
                listOfNotNull(network.bssid, network.channel?.let { "ch $it" }, network.band).joinToString("  "),
                fontFamily = MonoFontFamily,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(Space.xl))
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                singleLine = true,
                label = { Text("Login domain (optional)") },
                supportingText = {
                    Text(
                        "Leave empty to follow the network's login page." +
                            (registeredDomain?.let { " Registered here: $it" } ?: "")
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { onConnect(domain) },
                modifier = Modifier.fillMaxWidth().padding(top = Space.lg).heightIn(min = 48.dp)
            ) { Text("Join and check") }
        }
    }
}
