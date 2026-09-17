package com.thesis.geckowifi.ui.conflict

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.ui.VerificationViewModel
import com.thesis.geckowifi.ui.theme.ConflictRed
import com.thesis.geckowifi.ui.theme.Divider
import com.thesis.geckowifi.ui.theme.MonoFontFamily
import com.thesis.geckowifi.ui.theme.OnSurfaceMuted
import com.thesis.geckowifi.ui.theme.OnSurfaceVariant
import com.thesis.geckowifi.ui.theme.SurfaceContainer

/**
 * Shown only for a `CONFLICT` result - the one state this app ever
 * interrupts the user for. The registered-vs-presented comparison uses
 * `VerificationResult.registeredIdentifier`/`presentedIdentifier` (added
 * for this screen - see the implementation plan) - both null in some
 * branches, in which case that card just doesn't render rather than
 * showing blank/fake values. No Disconnect/Connect-anyway buttons: this
 * app never actually joins or drops a WiFi connection (see README.md), so
 * those would be non-functional dead buttons.
 *
 * Has an explicit back arrow (unlike the original design's mockup, which
 * had none) - found 2026-09-17: without one, the only way off this screen
 * was the phone's own back gesture, with zero visible affordance for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictScreen(
    viewModel: VerificationViewModel,
    onTechnicalDetails: () -> Unit,
    onCheckDetails: () -> Unit,
    onBack: () -> Unit
) {
    val check = viewModel.lastCheck
    val result = check?.result

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            }
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
        Icon(Icons.Outlined.Warning, contentDescription = null, tint = ConflictRed)
        Text(
            "This network isn't the one registered here",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            result?.reason?.replaceFirstChar { it.uppercase() }
                ?: "GECKO's response for this network didn't match what's registered at this location.",
            style = MaterialTheme.typography.bodyLarge,
            color = OnSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp)
        )

        if (result?.registeredIdentifier != null || result?.presentedIdentifier != null) {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                Column {
                    ComparisonRow("Registered here", result.registeredIdentifier ?: "—")
                    HorizontalDivider(color = Divider)
                    ComparisonRow("This network presented", result.presentedIdentifier ?: "—", ConflictRed)
                }
            }
        }

        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))

        Button(
            onClick = onCheckDetails,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) { Text("Check details") }
        Button(
            onClick = onTechnicalDetails,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) { Text("Technical details") }
        }
    }
}

@Composable
private fun ComparisonRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Column(Modifier.padding(14.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
        Text(value, fontFamily = MonoFontFamily, color = valueColor, style = MaterialTheme.typography.bodyLarge)
    }
}
