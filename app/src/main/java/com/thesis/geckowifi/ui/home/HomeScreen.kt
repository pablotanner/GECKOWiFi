package com.thesis.geckowifi.ui.home

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GppBad
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
import androidx.compose.ui.platform.LocalContext
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
 * Picking a network is the only decision the user makes: tapping it connects
 * (docs/eduroam-decisions.md, D20). Open networks are joined and checked,
 * enterprise networks go through GECKO's onboarding (asking for an account
 * only when none fits), password networks are Android's to join. The info
 * button shows a network's registration. Lab mode adds the access-point
 * choice, for running the testbed scenarios (Router A vs. B).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: VerificationViewModel,
    onOpenCheck: (String) -> Unit
) {
    val context = LocalContext.current
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
    // The federation an enterprise SSID is registered in ("eduroam"), for the row's second line.
    val federations = viewModel.registeredHere
        .mapNotNull { cert ->
            val ssid = cert.wifi.ssid?.lowercase()
            val federation = cert.wifi.enterprise?.federation?.takeIf { it.isNotBlank() }
            if (ssid != null && federation != null) ssid to federation else null
        }
        .toMap()
    val rows = buildRows(viewModel.scannedNetworks)
    val busy = viewModel.activeCheck != null

    // The registered network whose registration sheet is open, if any.
    var sheetFor by remember { mutableStateOf<ScannedNetwork.Real?>(null) }

    val connect: (ScannedNetwork) -> Unit = { viewModel.startCheck(it) }
    val list = ListContext(
        viewModel = viewModel,
        busy = busy,
        registrantOf = { ssid -> registrants[ssid.lowercase()] },
        federationOf = { ssid -> federations[ssid.lowercase()] },
        onShowRegistration = { sheetFor = it },
        onOpenCheck = onOpenCheck,
        onAndroidSettings = { network ->
            Toast.makeText(
                context,
                if (network.isEnterprise) "GECKO can't sign in to ${network.ssid} in this prototype. Join it in Android's settings."
                else "GECKO doesn't check password networks. Join ${network.ssid} in Android's settings.",
                Toast.LENGTH_LONG
            ).show()
            context.startActivity(Intent(Settings.Panel.ACTION_WIFI))
        },
        isExpanded = { ssid, registered -> expandOverrides[ssid] ?: registered },
        onToggle = { ssid, registered -> expandOverrides[ssid] = !(expandOverrides[ssid] ?: registered) },
        onConnect = connect
    )

    // The enterprise flow's questions: an account when none fits, a choice when several do.
    viewModel.enterprise?.let { flow ->
        flow.signIn?.let { SignInSheet(it.prompt, onSubmit = flow::submitSignIn, onDismiss = flow::dismissSignIn) }
        flow.choice?.let { AccountChooser(it, onChoose = flow::choose, onDismiss = flow::dismissChoice) }
    }
    LaunchedEffect(viewModel.pendingNotice) {
        viewModel.pendingNotice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.noticeShown()
        }
    }

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
            // A network that contradicts its registration leads: it is the one thing to act on.
            val byConcern = registered.sortedByDescending { list.matchOf(it.primary) is AdvertisedMatch.Mismatch }
            networkSection("Registered here", byConcern, true, list)
            networkSection(if (byConcern.isNotEmpty()) "Other networks" else "Networks", unregistered, false, list)
            networkSection("Demo networks", demo, false, list)

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
    val federationOf: (ssid: String) -> String?,
    val onShowRegistration: (ScannedNetwork.Real) -> Unit,
    val onOpenCheck: (String) -> Unit,
    val onAndroidSettings: (ScannedNetwork.Real) -> Unit,
    val isExpanded: (ssid: String, registered: Boolean) -> Boolean,
    val onToggle: (ssid: String, registered: Boolean) -> Unit,
    val onConnect: (ScannedNetwork) -> Unit
)

/** Pre-connection judgement of a registered real network; null for demo entries and unregistered SSIDs. */
private fun ListContext.matchOf(network: ScannedNetwork): AdvertisedMatch? =
    (network as? ScannedNetwork.Real)?.let { viewModel.advertisedMatch(it) }

/**
 * What tapping a row does (docs/eduroam-decisions.md, D20): connect. A network
 * that contradicts its registration shows the warning first; the network the
 * device is already on through GECKO opens its last result instead of
 * connecting again; what GECKO can't handle goes to Android's Wi-Fi settings.
 */
private fun ListContext.open(network: ScannedNetwork) {
    val real = network as? ScannedNetwork.Real
    if (real != null && matchOf(real) is AdvertisedMatch.Mismatch) return onShowRegistration(real)
    val connectedVerdict = real?.takeIf { it.ssid.equals(viewModel.deviceSsid, ignoreCase = true) }
        ?.let { viewModel.latestVerdictFor(it.ssid) }?.takeIf { it.enterprise != null }
    if (connectedVerdict != null) return onOpenCheck(connectedVerdict.id)
    when (viewModel.tapActionFor(network)) {
        VerificationViewModel.TapAction.CHECK, VerificationViewModel.TapAction.ENTERPRISE -> onConnect(network)
        VerificationViewModel.TapAction.ANDROID_SETTINGS -> real?.let(onAndroidSettings)
    }
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
                supporting = supportingText(row, ctx, registered),
                onInfo = (row.primary as? ScannedNetwork.Real)?.takeIf { registered }?.let { r -> { ctx.onShowRegistration(r) } },
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
    supporting: String,
    /** Shows the registration; null for networks nothing is registered for. */
    onInfo: (() -> Unit)?,
    latest: CheckDetail?,
    labMode: Boolean,
    expandable: Boolean,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val network = row.primary
    val real = network as? ScannedNetwork.Real
    val colors = MaterialTheme.colorScheme
    val content = colors.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = Space.lg, end = if (onInfo != null) Space.xs else Space.lg, top = Space.md, bottom = Space.md)
            .semantics {
                if (match is AdvertisedMatch.Mismatch) stateDescription = "Doesn't match its registration"
                else if (expandable) stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.lg)
    ) {
        Icon(signalIcon(real?.rssi), contentDescription = null, tint = content)
        Column(Modifier.weight(1f)) {
            Text(network.ssid, style = MaterialTheme.typography.bodyLarge, color = content)
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
            // Judged from the scan, before anyone connects: shown until a check says otherwise.
            match is AdvertisedMatch.Mismatch && latest?.result?.state != VerificationState.VERIFIED ->
                VerdictBadge(Tone.DANGER, "Doesn't match", Icons.Outlined.GppBad)
            // Only results worth remembering in the list: a confirmation and, above all, a warning.
            latest != null && latest.result.state in BADGED_STATES -> {
                val v = latest.verdict()
                VerdictBadge(v.tone, v.label, v.icon)
            }
        }
        if (onInfo != null) {
            IconButton(onClick = onInfo, enabled = enabled) {
                Icon(Icons.Outlined.Info, contentDescription = "What GECKO knows about ${network.ssid}", tint = colors.onSurfaceVariant)
            }
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

/** The row's second line: what the network is, and what tapping it does. */
private fun supportingText(row: NetworkRow, ctx: ListContext, registered: Boolean): String = when (val n = row.primary) {
    is ScannedNetwork.Real -> {
        val labMode = ctx.viewModel.labMode
        val match = if (registered) ctx.matchOf(n) else null
        val connected = n.ssid.equals(ctx.viewModel.deviceSsid, ignoreCase = true)
        val base = when {
            match is AdvertisedMatch.Mismatch -> "Registered as ${match.registered.securityNames()}, but this one is ${match.advertised.securityNames()}"
            n.isEnterprise -> {
                val kind = ctx.federationOf(n.ssid)?.let { "$it, enterprise" } ?: "Enterprise"
                if (ctx.viewModel.tapActionFor(n) == VerificationViewModel.TapAction.ENTERPRISE) "$kind. GECKO signs you in"
                else "$kind. Unsupported: Not operated by Pablo"
            }
            !n.isOpen -> "Password network. Unsupported: TODO"
            labMode && row.accessPoints.size == 1 ->
                listOfNotNull(n.bssid, n.channel?.let { "ch $it" }, n.rssi.takeIf { it != Int.MIN_VALUE }?.let { "$it dBm" })
                    .joinToString("  ")
            row.accessPoints.size > 1 -> "Open, ${row.accessPoints.size} access points"
            else -> "Open"
        }
        if (connected) "Connected. $base" else base
    }
    is ScannedNetwork.FakeCaptivePortal -> "Simulated ${n.label}" + if (ctx.viewModel.labMode) ", presents ${n.presumedDomain}" else ""
    is ScannedNetwork.FakeEduroam -> "Simulated ${n.label}" + if (ctx.viewModel.labMode) ", login server ${n.observed.displayName}" + (n.userRealm?.let { ", realm $it" } ?: "") else ""
}

private fun signalIcon(rssi: Int?): ImageVector = when {
    rssi == null || rssi == Int.MIN_VALUE -> Icons.Outlined.SignalWifi4Bar
    rssi >= -55 -> Icons.Outlined.SignalWifi4Bar
    rssi >= -67 -> Icons.Outlined.NetworkWifi3Bar
    rssi >= -78 -> Icons.Outlined.NetworkWifi2Bar
    else -> Icons.Outlined.NetworkWifi1Bar
}
