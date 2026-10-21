package com.thesis.geckowifi.ui.evidence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.ui.CheckDetail
import com.thesis.geckowifi.ui.QueryRoute
import com.thesis.geckowifi.ui.SERVER_URL
import com.thesis.geckowifi.ui.components.DetailRow
import com.thesis.geckowifi.ui.components.SectionCard
import com.thesis.geckowifi.ui.components.StackedValue
import com.thesis.geckowifi.ui.components.VerdictBadge
import com.thesis.geckowifi.ui.components.colors
import com.thesis.geckowifi.ui.theme.MonoFontFamily
import com.thesis.geckowifi.ui.theme.Space
import com.thesis.geckowifi.ui.verdict.scopeNote
import com.thesis.geckowifi.ui.verdict.Tone
import com.thesis.geckowifi.ui.verdict.hopLabel
import com.thesis.geckowifi.ui.verdict.hopTone
import com.thesis.geckowifi.ui.verdict.verdict
import com.thesis.geckowifi.verification.HopVerdict
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything GECKO saw for one check, ordered from meaning to mechanism:
 * the verdict in plain words, then the login-page path, then the map
 * server's proof, then the query. Opt-in only (reached from "Details" or
 * "What GECKO saw"), so the main flow never has to show any of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvidenceScreen(check: CheckDetail?, labMode: Boolean, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("What GECKO saw") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            }
        )
        if (check == null) {
            Text(
                "This check is no longer available.",
                modifier = Modifier.padding(Space.lg),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return
        }
        val result = check.result
        val v = check.verdict()

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg)
                .padding(bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.lg)
        ) {
            Column(Modifier.padding(top = Space.sm)) {
                VerdictBadge(v.tone, v.label, v.icon)
                Text(
                    check.ssid ?: result.host,
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(top = Space.md)
                )
                Text(
                    listOfNotNull(
                        SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(result.timestamp)),
                        check.bssid
                    ).joinToString("  "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(v.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Space.md))
                if (v.tone == Tone.POSITIVE) {
                    Text(
                        check.scopeNote,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Space.sm)
                    )
                }
            }

            check.enterprise?.let { e ->
                SectionCard("Login server") {
                    e.account?.let { DetailRow("Signed in as", it) }
                    (e.federation ?: e.operatorName)?.let { DetailRow(if (e.federation != null) "Federation" else "Operator", it, mono = false) }
                    e.pinsFrom?.let { StackedValue("Server identity from", it) }
                    if (e.serverNames.isNotEmpty()) StackedValue("Pinned server name", e.serverNames.joinToString("\n"))
                    if (e.caSubjects.isNotEmpty()) StackedValue("Pinned CA", e.caSubjects.joinToString("\n"))
                    e.onboardingFailure?.let { StackedValue("GECKO check connection", it) }
                    if (e.accounts.isNotEmpty()) StackedValue("Your accounts here", e.accounts.joinToString("\n"))
                    if (labMode && e.timings.isNotEmpty()) {
                        StackedValue("Timings", e.timings.joinToString("\n") { (step, ms) -> "$step  +$ms ms" })
                    }
                }
            }
            if (check.restored) {
                Text(
                    "Summary from an earlier session: the full evidence is only kept while the app runs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (result.registeredIdentifier != null || result.presentedIdentifier != null) {
                SectionCard("Registered vs. presented") {
                    result.registeredIdentifier?.let { StackedValue("Registered here", it) }
                    result.presentedIdentifier?.let {
                        StackedValue("This network presented", it, MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (check.hops.isNotEmpty()) {
                SectionCard("Login page path") {
                    Text(
                        "Each page the network sent the app to. Secure pages are compared with the registration.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Space.md)
                    )
                    check.hops.forEachIndexed { i, hop ->
                        HopNode(hop, isLast = i == check.hops.lastIndex, labMode = labMode)
                    }
                }
            }

            SectionCard("Map server answer") {
                check.dataSource?.let { StackedValue("Data from", it) }
                if (result.certificateCount != null) {
                    // Only a response that passed every check carries this metadata
                    // (GeckoClient throws before Success otherwise), so "Valid" is not assumed.
                    DetailRow("Signature", "Valid", mono = false)
                    DetailRow("Inclusion proof", "Valid", mono = false)
                    DetailRow("Consistency proof", "Valid", mono = false)
                    DetailRow("Certificates here", "${result.certificateCount}")
                    if ((result.unparsedCount ?: 0) > 0) DetailRow("Unreadable entries", "${result.unparsedCount}")
                    result.matchedCertificateId?.let { DetailRow("Matched certificate", it) }
                } else {
                    Text(
                        "No verified answer was used for this check.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DetailRow(
                    "Sent over",
                    when (check.queryRoute) {
                        QueryRoute.MOBILE_DATA -> "Mobile Data"
                        QueryRoute.JOINED_WIFI -> "This WiFi Network"
                        QueryRoute.DEFAULT_ROUTE -> "Default connection"
                        null -> "No answer received"
                    },
                    mono = false
                )
            }

            SectionCard("Query") {
                DetailRow("Location", "%.5f, %.5f".format(check.lat, check.lng))
                check.accuracyMeters?.let { DetailRow("Accuracy", "${it.toInt()} m") }
                DetailRow("Search radius", "${check.radiusMeters} m")
                check.modeDescription?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.sm)) }
            }

            if (labMode) {
                SectionCard("Lab") {
                    DetailRow("Map server", SERVER_URL)
                    result.reason?.let { StackedValue("Engine reason", it) }
                    check.failure?.let { DetailRow("Failure", it.name) }
                    if (check.isDemo) DetailRow("Source", "Simulated demo entry", mono = false)
                }
            }
        }
    }
}

/** One step of the portal chain as a vertical timeline node: dot, connector line, host, verdict. */
@Composable
private fun HopNode(hopVerdict: HopVerdict, isLast: Boolean, labMode: Boolean) {
    val hop = hopVerdict.hop
    val state = hopVerdict.result?.state
    val tone = state.hopTone()
    val colors = MaterialTheme.colorScheme
    val dot = when (tone) {
        Tone.POSITIVE -> colors.primary
        Tone.DANGER -> colors.error
        Tone.NEUTRAL -> colors.outline
    }

    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 6.dp).size(10.dp)) {
                Surface(shape = CircleShape, color = dot, modifier = Modifier.size(10.dp)) {}
            }
            if (!isLast) {
                Box(Modifier.padding(vertical = Space.xs).width(2.dp).weight(1f)) {
                    Surface(color = colors.outlineVariant, modifier = Modifier.fillMaxHeight().width(2.dp)) {}
                }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = Space.sm, bottom = if (isLast) 0.dp else Space.lg)
                .semantics { stateDescription = state.hopLabel() }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Icon(
                    if (hop.isHttps) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    contentDescription = if (hop.isHttps) "Secure" else "Not encrypted",
                    modifier = Modifier.size(16.dp),
                    tint = colors.onSurfaceVariant
                )
                Text(hop.host, fontFamily = MonoFontFamily, style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                Modifier.padding(top = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                if (state != null) VerdictBadge(tone, state.hopLabel(), null)
                Text(
                    hop.error ?: hop.statusCode?.let { "HTTP $it" } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                if (state == null) {
                    Text("Passed through", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            if (labMode) {
                Text(
                    hop.url,
                    fontFamily = MonoFontFamily,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.xs)
                )
                hop.presentedSpkiHash?.let {
                    Text("key $it", fontFamily = MonoFontFamily, style = MaterialTheme.typography.bodySmall, color = tone.colors().content.takeIf { tone == Tone.DANGER } ?: colors.onSurfaceVariant)
                }
            }
        }
    }
}
