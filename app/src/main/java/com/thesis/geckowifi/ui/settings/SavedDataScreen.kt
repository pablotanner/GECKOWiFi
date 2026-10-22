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
import com.thesis.geckowifi.ui.REGISTRATION_REFRESH_INTERVAL_MS
import com.thesis.geckowifi.ui.SAVED_AREA_RADIUS_METERS
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
                    "checks when it arrived. The registrations are only used within ${SAVED_AREA_RADIUS_METERS} m of where they " +
                    "were fetched and for ${SAVED_REGISTRATION_MAX_AGE.toHours()} hours. There they let GECKO check networks " +
                    "when the map server can't be reached, and sign you in to an enterprise network without its onboarding step. " +
                    "While the app is open, GECKO looks the place up again every ${REGISTRATION_REFRESH_INTERVAL_MS / 60_000} " +
                    "minutes and whenever you join a network.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionCard("Registrations") {
                if (lookup == null) {
                    Text("Nothing saved yet. GECKO saves the registrations around you whenever it can reach the map server.",
                        style = MaterialTheme.typography.bodyMedium)
                } else {
                    LookupSummary(lookup, viewModel.savedLookupDistanceMeters(), viewModel.savedLookupAppliesHere())
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

/**
 * Where, when and how the saved answer was fetched, and whether it is used
 * where the device is now (D26). [distanceMeters] and [appliesHere] are null
 * while there is no location fix.
 */
@Composable
private fun LookupSummary(lookup: SavedLookup, distanceMeters: Double?, appliesHere: Boolean?) {
    val ageMinutes = (System.currentTimeMillis() - lookup.fetchedAt) / 60_000
    val leftMinutes = SAVED_REGISTRATION_MAX_AGE.toMinutes() - ageMinutes
    DetailRow("Fetched", "${timeText(lookup.fetchedAt)} (${age(ageMinutes)} ago)", mono = false)
    DetailRow("Over", lookup.viaDescription(), mono = false)
    DetailRow("Around", "%.5f, %.5f, within %d m".format(lookup.lat, lookup.lng, lookup.radiusMeters))
    lookup.mapHeadMillis?.let { DetailRow("Map signed", timeText(it), mono = false) }
    DetailRow("Valid for", if (leftMinutes > 0) "${age(leftMinutes)} more" else "No longer, too old", mono = false)
    DetailRow(
        "Used here",
        when {
            appliesHere == null || distanceMeters == null -> "Not known yet: GECKO has no location"
            appliesHere -> "Yes, you're ${distance(distanceMeters)} from where it was fetched"
            leftMinutes <= 0 -> "No, too old"
            else -> "No, you're ${distance(distanceMeters)} from where it was fetched. GECKO uses it again if you come back, " +
                "and replaces it the next time it reaches the map server"
        },
        mono = false
    )
}

private fun distance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} m" else "%.1f km".format(meters / 1000)

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
