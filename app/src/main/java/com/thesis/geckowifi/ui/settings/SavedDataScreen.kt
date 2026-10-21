package com.thesis.geckowifi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.data.local.SavedLookup
import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.eduroam.SAVED_REGISTRATION_MAX_AGE
import com.thesis.geckowifi.ui.VerificationViewModel
import com.thesis.geckowifi.ui.components.DetailRow
import com.thesis.geckowifi.ui.components.SectionCard
import com.thesis.geckowifi.ui.theme.MonoFontFamily
import com.thesis.geckowifi.ui.theme.Space
import com.thesis.geckowifi.ui.timeText
import com.thesis.geckowifi.ui.viaDescription

/**
 * Everything GECKO saved (docs/eduroam-decisions.md, D21): the last verified
 * map answer with where, when and over which connection it was fetched, the
 * saved login-server records, and the verdicts; and the way to delete them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedDataScreen(viewModel: VerificationViewModel, onBack: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val lookup = viewModel.savedLookup
    val records = viewModel.savedRealmRecords
    val verdicts = viewModel.checks.count { !it.isDemo }

    Column(Modifier.fillMaxWidth()) {
        TopAppBar(
            title = { Text("Saved GECKO data") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } }
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Space.lg).padding(bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.lg)
        ) {
            Text(
                "What GECKO kept so it doesn't have to ask the map server for every network. Everything here passed GECKO's " +
                    "checks when it arrived. A registration less than ${SAVED_REGISTRATION_MAX_AGE.toHours()} hours old lets " +
                    "GECKO sign you in to an enterprise network without its onboarding step.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionCard("Registrations") {
                if (lookup == null) {
                    Text("Nothing saved yet. GECKO saves the registrations around you whenever it can reach the map server.",
                        style = MaterialTheme.typography.bodyMedium)
                } else {
                    LookupSummary(lookup)
                    lookup.certificates.sortedBy { it.wifi.ssid.orEmpty().lowercase() }.forEach {
                        HorizontalDivider(Modifier.padding(vertical = Space.sm), color = MaterialTheme.colorScheme.outlineVariant)
                        CertificateEntry(it)
                    }
                }
            }

            SectionCard("Login server records") {
                if (records.isEmpty()) {
                    Text("None saved. They are saved when GECKO fetches one for your account during onboarding.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                records.forEach {
                    DetailRow("${it.realm} (${it.federation})", "${timeText(it.fetchedAt)}${it.via?.let { v -> ", $v" }.orEmpty()}", mono = false)
                }
            }

            SectionCard("Verdicts") {
                Text(
                    if (verdicts == 0) "No verdicts saved." else "$verdicts verdicts, listed in History. Each one's details say where its data came from.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Button(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Delete saved data") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete saved data?") },
            text = {
                Text("GECKO forgets the saved registrations, login server records, verdicts and History, and asks the map server " +
                    "again next time. Your accounts and the networks GECKO set up stay.")
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearSavedData(); confirmDelete = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun LookupSummary(lookup: SavedLookup) {
    val ageMinutes = (System.currentTimeMillis() - lookup.fetchedAt) / 60_000
    val usable = ageMinutes < SAVED_REGISTRATION_MAX_AGE.toMinutes()
    DetailRow("Fetched", "${timeText(lookup.fetchedAt)} (${age(ageMinutes)} ago)", mono = false)
    DetailRow("Over", lookup.viaDescription(), mono = false)
    DetailRow("Around", "%.5f, %.5f, %d m".format(lookup.lat, lookup.lng, lookup.radiusMeters))
    lookup.mapHeadMillis?.let { DetailRow("Map signed", timeText(it), mono = false) }
    DetailRow("Joining without onboarding", if (usable) "Yes, for ${age(SAVED_REGISTRATION_MAX_AGE.toMinutes() - ageMinutes)} more" else "No, too old", mono = false)
}

@Composable
private fun CertificateEntry(cert: GeoCertificate) {
    val enterprise = cert.wifi.enterprise
    val kind = when {
        enterprise == null -> cert.wifi.authModeNames.joinToString().ifEmpty { "network" }
        enterprise.isFederated -> "enterprise, ${enterprise.federation}"
        else -> "enterprise, own accounts"
    }
    Column(Modifier.padding(vertical = Space.xs)) {
        Text(cert.wifi.ssid ?: "(no SSID)", style = MaterialTheme.typography.bodyLarge)
        Text(
            listOfNotNull(kind, cert.subject?.name?.let { "by $it" }).joinToString(", "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "${cert.certificateId}, valid until ${cert.notValidAfter.substringBefore('T')}",
            fontFamily = MonoFontFamily,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun age(minutes: Long): String = when {
    minutes < 1 -> "less than a minute"
    minutes < 60 -> "$minutes min"
    else -> "${minutes / 60} h ${minutes % 60} min"
}
