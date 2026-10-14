package com.thesis.geckowifi.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NetworkWifi1Bar
import androidx.compose.material.icons.outlined.NetworkWifi2Bar
import androidx.compose.material.icons.outlined.NetworkWifi3Bar
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SignalWifi4Bar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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
import com.thesis.geckowifi.ui.verdict.Tone
import com.thesis.geckowifi.ui.verdict.verdict
import com.thesis.geckowifi.verification.AdvertisedMatch
import com.thesis.geckowifi.verification.LookupStatus

/**
 * The app's one main screen: what the device is connected to and whether
 * GECKO vouches for it (the status card), then the networks to pick from.
 *
 * Picking a network is the only decision the user makes. Joining it starts
 * the check automatically; there is no separate "check" step and no access
 * point to choose. Lab mode adds the access-point choice, for running the
 * testbed scenarios (Router A vs. B).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: VerificationViewModel,
    onOpenCheck: (String) -> Unit
) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    // Lab mode: SSIDs expanded or collapsed by hand; otherwise registered ones start expanded.
    val expandOverrides = remember { mutableStateMapOf<String, Boolean>() }

    val known = viewModel.certLookup == LookupStatus.OK
    val registeredSsids = viewModel.registeredHere.mapNotNull { it.wifi.ssid?.lowercase() }.toSet()
    // Who registered each SSID here, for the list ("Registered with GECKO by ETH Zürich").
    val registrants = viewModel.registeredHere
        .mapNotNull { cert -> cert.wifi.ssid?.lowercase()?.let { it to cert.subject?.name } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, names) -> names.filterNotNull().distinct().singleOrNull() }
    val rows = buildRows(viewModel.scannedNetworks)
    val busy = viewModel.activeCheck != null

    // The registered network whose registration sheet is open, if any.
    var sheetFor by remember { mutableStateOf<ScannedNetwork.Real?>(null) }

    val connect: (ScannedNetwork) -> Unit = { viewModel.startCheck(it) }
    val list = ListContext(
        viewModel = viewModel,
        busy = busy,
        registrantOf = { ssid -> registrants[ssid.lowercase()] },
        onShowRegistration = { sheetFor = it },
        isExpanded = { ssid, registered -> expandOverrides[ssid] ?: registered },
        onToggle = { ssid, registered -> expandOverrides[ssid] = !(expandOverrides[ssid] ?: registered) },
        onConnect = connect
    )

    sheetFor?.let { network ->
        val match = viewModel.advertisedMatch(network)
        if (match == null) {
            sheetFor = null
        } else {
            RegistrationSheet(
                network = network,
                match = match,
                onDismiss = { sheetFor = null },
                onConnectAnyway = if (network.isOpen && !busy) ({ sheetFor = null; connect(network) }) else null,
                loginServerCheck = if (viewModel.rootAvailable && !network.isOpen && !busy) LoginServerCheck(
                    connected = viewModel.isConnectedTo(network.ssid),
                    onCheck = { sheetFor = null; viewModel.checkLoginServer(network) }
                ) else null
            )
        }
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
            // A registered secured network is checked from its scan, without joining.
            val (registered, unregistered) =
                if (known) real.partition { it.primary.ssid.lowercase() in registeredSsids } else emptyList<NetworkRow>() to real
            val (checkable, secured) = unregistered.partition { (it.primary as ScannedNetwork.Real).isOpen }

            // A network that contradicts its registration leads: it is the one thing to act on.
            val byConcern = registered.sortedByDescending { list.matchOf(it.primary) is AdvertisedMatch.Mismatch }
            networkSection("Registered here", byConcern, true, list)
            networkSection(if (known) "Other open networks" else "Open networks", checkable, false, list)
            networkSection("Demo networks", demo, false, list)
            networkSection("Secured networks", secured, false, list)

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
}

/**
 * One list row per SSID: Android reports every access point (BSSID) as its
 * own scan result, so a campus network would otherwise appear dozens of
 * times. Groups are ordered by their strongest AP. In the normal view the
 * strongest AP is joined; in lab mode a group expands to its APs so a
 * specific one (Router A vs. B) can be joined.
 */
private class NetworkRow(val primary: ScannedNetwork, val accessPoints: List<ScannedNetwork.Real>)

private fun buildRows(networks: List<ScannedNetwork>): List<NetworkRow> {
    // Grouped by SSID *and* security type: an open "eduroam" next to the real one is a
    // different network and gets its own row (and its own verdict), as in Android's settings.
    val realRows = networks.filterIsInstance<ScannedNetwork.Real>()
        .groupBy { it.ssid to it.authModes }.values
        .map { aps -> aps.sortedByDescending { it.rssi } }
        .sortedByDescending { it.first().rssi }
        .map { NetworkRow(it.first(), it) }
    // Demo entries stay separate rows even when they share an SSID (benign vs. evil twin).
    return realRows + networks.filter { it !is ScannedNetwork.Real }.map { NetworkRow(it, emptyList()) }
}

private class ListContext(
    val viewModel: VerificationViewModel,
    val busy: Boolean,
    val registrantOf: (ssid: String) -> String?,
    val onShowRegistration: (ScannedNetwork.Real) -> Unit,
    val isExpanded: (ssid: String, registered: Boolean) -> Boolean,
    val onToggle: (ssid: String, registered: Boolean) -> Unit,
    val onConnect: (ScannedNetwork) -> Unit
)

/** Pre-connection judgement of a registered real network; null for demo entries and unregistered SSIDs. */
private fun ListContext.matchOf(network: ScannedNetwork): AdvertisedMatch? =
    (network as? ScannedNetwork.Real)?.let { viewModel.advertisedMatch(it) }

/**
 * What tapping a row does. Joining only happens for an open network that
 * doesn't contradict its registration; a mismatching one, or a registered
 * secured one (no credentials to join with), opens its registration instead.
 */
private fun ListContext.open(network: ScannedNetwork) {
    val real = network as? ScannedNetwork.Real
    val match = real?.let { matchOf(it) }
    if (real != null && match != null && (match is AdvertisedMatch.Mismatch || !real.isOpen)) onShowRegistration(real)
    else onConnect(network)
}

private fun ListContext.latestFor(aps: List<ScannedNetwork>): CheckDetail? =
    aps.mapNotNull { viewModel.latestCheckFor(it.bssid ?: it.ssid) }.maxByOrNull { it.result.timestamp }

private fun LazyListScope.networkSection(title: String, rows: List<NetworkRow>, registered: Boolean, ctx: ListContext) {
    if (rows.isEmpty()) return
    item(key = "header:$title") { SectionHeader(title) }
    rows.forEach { row ->
        val ssid = row.primary.ssid
        val open = (row.primary as? ScannedNetwork.Real)?.isOpen ?: true
        // Only an open SSID with several APs has a choice worth expanding; a single AP is joined directly.
        val expandable = ctx.viewModel.labMode && open && row.accessPoints.size > 1 &&
            ctx.matchOf(row.primary) !is AdvertisedMatch.Mismatch
        val expanded = expandable && ctx.isExpanded(ssid, registered)
        item(key = "$title:$ssid:${row.primary.bssid}") {
            NetworkListItem(
                row = row,
                registered = registered,
                registrant = if (registered) ctx.registrantOf(ssid) else null,
                match = if (registered) ctx.matchOf(row.primary) else null,
                // An expanded group shows each AP's own result instead.
                latest = if (expanded) null else ctx.latestFor(row.accessPoints.ifEmpty { listOf(row.primary) }),
                labMode = ctx.viewModel.labMode,
                expandable = expandable,
                expanded = expanded,
                enabled = !ctx.busy,
                onClick = { if (expandable) ctx.onToggle(ssid, registered) else ctx.open(row.primary) }
            )
        }
        if (expanded) {
            items(row.accessPoints, key = { "$title:$ssid:ap:${it.bssid}" }) { ap ->
                AccessPointItem(ap, ctx.latestFor(listOf(ap)), enabled = !ctx.busy, onClick = { ctx.open(ap) })
            }
        }
    }
}

/** Lab mode: one access point inside an expanded SSID group. Tapping it joins exactly this BSSID. */
@Composable
private fun AccessPointItem(ap: ScannedNetwork.Real, latest: CheckDetail?, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 56.dp, end = Space.lg, top = Space.sm, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        Column(Modifier.weight(1f)) {
            Text(ap.bssid ?: "Unknown BSSID", fontFamily = MonoFontFamily, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(ap.channel?.let { "ch $it" }, ap.band, ap.rssi.takeIf { it != Int.MIN_VALUE }?.let { "$it dBm" })
                    .joinToString("  "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        if (latest != null && latest.result.state in BADGED_STATES) {
            val v = latest.verdict()
            VerdictBadge(v.tone, v.label, v.icon)
        }
    }
    HorizontalDivider(Modifier.padding(start = 56.dp), color = colors.outlineVariant)
}

@Composable
private fun NetworkListItem(
    row: NetworkRow,
    registered: Boolean,
    registrant: String?,
    match: AdvertisedMatch?,
    latest: CheckDetail?,
    labMode: Boolean,
    expandable: Boolean,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val network = row.primary
    val real = network as? ScannedNetwork.Real
    val checkable = real == null || real.isOpen || registered
    val colors = MaterialTheme.colorScheme
    val content = if (checkable) colors.onSurface else colors.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled && checkable, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.md)
            .semantics {
                if (!checkable) stateDescription = "Can't be checked"
                else if (match is AdvertisedMatch.Mismatch) stateDescription = "Doesn't match its registration"
                else if (expandable) stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.lg)
    ) {
        Icon(signalIcon(real?.rssi), contentDescription = null, tint = content)
        Column(Modifier.weight(1f)) {
            Text(network.ssid, style = MaterialTheme.typography.bodyLarge, color = content)
            val supporting = supportingText(row, labMode, registered, match)
            if (supporting.isNotEmpty()) {
                val mono = labMode && real != null && real.isOpen && row.accessPoints.size == 1
                Text(
                    supporting,
                    style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily)
                    else MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
            if (registered) {
                // Neutral, not the theme's green: registration is information, not a verification.
                Text(
                    "Registered with GECKO" + registrant?.let { " by $it" }.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant
                )
            }
        }
        when {
            !checkable -> Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.onSurfaceVariant)
            // Judged from the scan, before anyone connects: shown until a check says otherwise.
            match is AdvertisedMatch.Mismatch && latest?.result?.state != VerificationState.VERIFIED ->
                VerdictBadge(Tone.DANGER, "Doesn't match", Icons.Outlined.GppBad)
            // Only results worth remembering in the list: a confirmation and, above all, a warning.
            latest != null && latest.result.state in BADGED_STATES -> {
                val v = latest.verdict()
                VerdictBadge(v.tone, v.label, v.icon)
            }
            // Tapping opens the registration, not a connection.
            real != null && !real.isOpen ->
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
        if (expandable) {
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = colors.onSurfaceVariant
            )
        }
    }
    if (!expanded) HorizontalDivider(Modifier.padding(start = 56.dp), color = colors.outlineVariant)
}

/** Results worth remembering in the list: a confirmation and, above all, a warning. "Registered" is the label's job. */
private val BADGED_STATES = setOf(VerificationState.VERIFIED, VerificationState.CONFLICT)

private fun supportingText(row: NetworkRow, labMode: Boolean, registered: Boolean, match: AdvertisedMatch?): String = when (val n = row.primary) {
    is ScannedNetwork.Real -> when {
        match is AdvertisedMatch.Mismatch -> "Registered as ${match.registered.securityNames()}, but this one is ${match.advertised.securityNames()}"
        !n.isOpen && registered -> n.securityLabel
        !n.isOpen -> "${n.securityLabel}. Can't be checked yet"
        labMode && row.accessPoints.size == 1 ->
            listOfNotNull(n.bssid, n.channel?.let { "ch $it" }, n.rssi.takeIf { it != Int.MIN_VALUE }?.let { "$it dBm" })
                .joinToString("  ")
        row.accessPoints.size > 1 -> "Open, ${row.accessPoints.size} access points"
        else -> "Open"
    }
    is ScannedNetwork.FakeCaptivePortal -> "Simulated ${n.label}" + if (labMode) ", presents ${n.presumedDomain}" else ""
    is ScannedNetwork.FakeEduroam -> "Simulated ${n.label}" + if (labMode) ", login server ${n.observed.displayName}" + (n.userRealm?.let { ", realm $it" } ?: "") else ""
}

private fun signalIcon(rssi: Int?): ImageVector = when {
    rssi == null || rssi == Int.MIN_VALUE -> Icons.Outlined.SignalWifi4Bar
    rssi >= -55 -> Icons.Outlined.SignalWifi4Bar
    rssi >= -67 -> Icons.Outlined.NetworkWifi3Bar
    rssi >= -78 -> Icons.Outlined.NetworkWifi2Bar
    else -> Icons.Outlined.NetworkWifi1Bar
}
