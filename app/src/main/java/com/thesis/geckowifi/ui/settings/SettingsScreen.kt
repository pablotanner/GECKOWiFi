package com.thesis.geckowifi.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.thesis.geckowifi.di.AppModule
import com.thesis.geckowifi.ui.VerificationViewModel
import com.thesis.geckowifi.ui.theme.Divider
import com.thesis.geckowifi.ui.theme.OnSurfaceMuted

/**
 * No Claude Design screen was provided for Settings, so this is plain
 * Material3, not styled to match the other screens. Deliberately minimal:
 * exposes exactly the two preferences that already have real backing
 * (`AppModule.trustPreferences` / `TrustPreferenceStore`) rather than
 * inventing settings this app doesn't act on anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: VerificationViewModel) {
    Column(Modifier.fillMaxWidth()) {
        TopAppBar(title = { Text("Settings") })

        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Strict mode", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Not yet consumed by any verification path in this build - flipping it has no effect elsewhere yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted
                )
            }
            Switch(checked = viewModel.strictMode, onCheckedChange = { viewModel.strictMode = it })
        }
        HorizontalDivider(color = Divider)

        Column(Modifier.padding(16.dp)) {
            Text("Query radius: ${viewModel.queryRadiusMeters} m", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = viewModel.queryRadiusMeters.toFloat(),
                onValueChange = { viewModel.queryRadiusMeters = it.toInt() },
                valueRange = 10f..250f
            )
        }
        HorizontalDivider(color = Divider)

        val context = LocalContext.current
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Check log", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
            Text(
                "Every check is recorded as one JSON line, with all portal hops and verdicts.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted
            )
            TextButton(
                onClick = {
                    val file = AppModule.portalCheckLog.latestFile()
                    if (file == null) {
                        Toast.makeText(context, "No checks logged yet", Toast.LENGTH_SHORT).show()
                    } else {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "Share check log"))
                    }
                }
            ) { Text("Share latest check log") }
        }
    }
}
