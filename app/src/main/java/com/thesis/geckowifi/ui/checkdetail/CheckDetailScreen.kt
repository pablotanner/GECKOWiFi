package com.thesis.geckowifi.ui.checkdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.ui.VerificationViewModel
import com.thesis.geckowifi.ui.components.DetailRow
import com.thesis.geckowifi.ui.components.StateBadge
import com.thesis.geckowifi.ui.theme.MonoFontFamily
import com.thesis.geckowifi.ui.theme.OnSurfaceMuted
import com.thesis.geckowifi.ui.theme.SurfaceContainer

/**
 * A full record of the last check's query and response - everything here
 * is real, structured data already produced by the crypto pipeline
 * (`GeckoResponse.Success.certificates.size`/`.unparsedCount`, which
 * README.md flagged as never surfaced anywhere - this screen is where
 * that finally happens) or the query parameters the caller already used.
 * "Server signature / Inclusion proof / Consistency proof: Valid" are
 * unconditionally true for any `Success` result - `verifyResponse()` and
 * `ensureConsistency()` both already threw otherwise, so a `Success`
 * response reaching this screen has necessarily passed all three.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckDetailScreen(viewModel: VerificationViewModel, onBack: () -> Unit) {
    val check = viewModel.lastCheck

    Column(Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("Check") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            }
        )

        if (check == null) {
            Text("No check to show yet.", modifier = Modifier.padding(16.dp), color = OnSurfaceMuted)
            return
        }
        val result = check.result

        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    StateBadge(result.state)
                    Text(
                        check.ssid ?: result.host,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    Text(
                        "${formatTime(result.timestamp)} · " +
                            if (result.usedPreferredNetwork == true) "queried over cellular" else "queried over the default route",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceMuted
                    )
                }
            }

            SectionCard("Query") {
                DetailRow("Location", "${"%.4f".format(check.lat)}, ${"%.4f".format(check.lng)}")
                check.accuracyMeters?.let { DetailRow("Accuracy", "±${it.toInt()} m") }
                DetailRow("Radius", "${check.radiusMeters} m")
                check.altitude?.let { DetailRow("Altitude", "${it.toInt()} m") }
            }

            if (result.certificateCount != null) {
                SectionCard("Response") {
                    DetailRow("Certificates returned", "${result.certificateCount}")
                    DetailRow("Unreadable entries", "${result.unparsedCount ?: 0}")
                    DetailRow("Server signature", "Valid")
                    DetailRow("Inclusion proof", "Valid")
                    DetailRow("Consistency proof", "Valid")
                }
            }

            if (result.state == VerificationState.CONFLICT &&
                (result.registeredIdentifier != null || result.presentedIdentifier != null)
            ) {
                SectionCard("Comparison") {
                    DetailRow("Registered here", result.registeredIdentifier ?: "—")
                    DetailRow("This network presented", result.presentedIdentifier ?: "—")
                }
            }

            SectionCard("How it was checked") {
                check.modeDescription?.let { DetailRow("Method", it) }
                result.reason?.let { DetailRow("Reason", it) }
            }

            if (check.hops.isNotEmpty()) {
                SectionCard("Portal hops") {
                    check.hops.forEach { (hop, verdict) ->
                        val port = if ((hop.isHttps && hop.port == 443) || (!hop.isHttps && hop.port == 80)) "" else ":${hop.port}"
                        Text(
                            "${hop.index}. ${hop.scheme}://${hop.host}$port",
                            fontFamily = MonoFontFamily,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            listOfNotNull(
                                hop.statusCode?.let { "HTTP $it" } ?: hop.error,
                                verdict?.state?.name ?: "not judged (transit)",
                                hop.presentedSpkiHash?.let { "key ${it.take(12)}…" }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
            content()
        }
    }
}

private fun formatTime(timestampMs: Long): String =
    java.text.SimpleDateFormat("MMM d 'at' HH:mm", java.util.Locale.US).format(java.util.Date(timestampMs))
