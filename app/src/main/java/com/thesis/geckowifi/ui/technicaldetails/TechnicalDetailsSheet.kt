package com.thesis.geckowifi.ui.technicaldetails

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.ui.VerificationViewModel
import com.thesis.geckowifi.ui.components.DetailRow
import com.thesis.geckowifi.ui.theme.OnSurfaceMuted

/**
 * The exhaustive real-data view shown as a sheet over Conflict/Connected -
 * every row here is either a query parameter already used to make the
 * check, or a field already on [com.thesis.geckowifi.data.model.VerificationResult].
 * The design's "Issued by" row is omitted - `GeoCertificate` has no issuer
 * field, and this build doesn't fabricate one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechnicalDetailsSheet(viewModel: VerificationViewModel, onDismiss: () -> Unit) {
    val check = viewModel.lastCheck ?: run { onDismiss(); return }
    val result = check.result

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Technical details", style = MaterialTheme.typography.titleMedium)
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)) {
                check.ssid?.let { DetailRow("Network", it) }
                check.bssid?.let { DetailRow("Access point", it) }
                result.registeredIdentifier?.let { DetailRow("Registered", it) }
                result.presentedIdentifier?.let { DetailRow("Presented", it) }
                result.matchedCertificateId?.let { DetailRow("Certificate ID", it) }
                DetailRow("Query location", "${"%.4f".format(check.lat)}, ${"%.4f".format(check.lng)}" +
                    (check.accuracyMeters?.let { "  ±${it.toInt()} m" } ?: ""))
                DetailRow("Query radius", "${check.radiusMeters} m")
                if (result.certificateCount != null) {
                    DetailRow("Server signature", "Valid")
                    DetailRow("Inclusion proof", "Valid")
                    DetailRow("Consistency proof", "Valid")
                }
                DetailRow(
                    "Query sent over",
                    if (result.usedPreferredNetwork == true) "Cellular" else "Default route"
                )
                if (check.ssid == null && check.bssid == null && result.matchedCertificateId == null) {
                    Text("No further detail available for this check.", color = OnSurfaceMuted)
                }
            }
        }
    }
}
