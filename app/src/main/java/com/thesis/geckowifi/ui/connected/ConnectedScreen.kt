package com.thesis.geckowifi.ui.connected

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.ui.VerificationViewModel
import com.thesis.geckowifi.ui.theme.Divider
import com.thesis.geckowifi.ui.theme.MonoFontFamily
import com.thesis.geckowifi.ui.theme.OnSurfaceMuted
import com.thesis.geckowifi.ui.theme.OnSurfaceVariant
import com.thesis.geckowifi.ui.theme.SurfaceContainer

/**
 * Shown for a `VERIFIED` enterprise (802.1X/eduroam-style) result - the
 * observed auth-server-name matched what's registered, and its CA
 * fingerprint matched too. Portal (captive-portal) `VERIFIED` results have
 * no equivalent screen in this build (the design provided none besides the
 * WebView-mockup Portal/Portal Connected screens, which were skipped - see
 * README.md) - they stay silent, consistent with this app's "only interrupt
 * on CONFLICT" policy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedScreen(
    viewModel: VerificationViewModel,
    onTechnicalDetails: () -> Unit,
    onCheckDetails: () -> Unit,
    onBack: () -> Unit
) {
    val check = viewModel.lastCheck
    val result = check?.result

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(check?.ssid ?: result?.host ?: "") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Column(Modifier.padding(16.dp)) {
            Icon(Icons.Outlined.Wifi, contentDescription = null)
            Text(
                "Verified and connected",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 20.dp)
            )
            Text(
                "The login server this network used matches the one registered for this location.",
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )

            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                Column {
                    InfoRow("Login server", result?.host ?: "")
                    HorizontalDivider(color = Divider)
                    InfoRow("Certificate", result?.presentedIdentifier ?: "—")
                }
            }

            androidx.compose.material3.TextButton(
                onClick = onCheckDetails,
                modifier = Modifier.padding(top = 8.dp)
            ) { Text("Check details") }
            androidx.compose.material3.TextButton(
                onClick = onTechnicalDetails
            ) { Text("Technical details") }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.padding(14.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
        Text(value, fontFamily = MonoFontFamily, style = MaterialTheme.typography.bodyMedium)
    }
}
