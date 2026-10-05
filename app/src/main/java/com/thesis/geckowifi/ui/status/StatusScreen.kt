package com.thesis.geckowifi.ui.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
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
import com.thesis.geckowifi.ui.VerificationViewModel
import com.thesis.geckowifi.ui.components.DetailRow
import com.thesis.geckowifi.ui.components.StateBadge
import com.thesis.geckowifi.ui.theme.OnSurfaceMuted
import com.thesis.geckowifi.ui.theme.SurfaceContainer
import com.thesis.geckowifi.verification.LookupStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Current, real state of the things a check depends on - location, what the
 * last scan found, and the most recent result. Everything here is read from
 * [VerificationViewModel] state the app already holds; there is still no
 * continuous background monitoring (a check runs only when you start one on
 * the Networks tab), so nothing on this screen is live or fabricated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(viewModel: VerificationViewModel, onCheckDetails: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        TopAppBar(
            title = { Text("Status") },
            actions = {
                IconButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                }
            }
        )

        Column(Modifier.padding(16.dp)) {
            StatusCard("Location") {
                if (!viewModel.hasLocationPermission) {
                    Text("Permission not granted", color = OnSurfaceMuted)
                } else {
                    val fix = viewModel.location
                    if (fix == null) {
                        Text("No fix yet", color = OnSurfaceMuted)
                    } else {
                        DetailRow("Coordinates", "%.5f, %.5f".format(fix.latitude, fix.longitude))
                        DetailRow("Accuracy", "±${fix.accuracyMeters.toInt()} m")
                        fix.altitude?.let { DetailRow("Altitude", "${it.toInt()} m") }
                        fix.provider?.let { DetailRow("Provider", it) }
                    }
                }
            }

            val registeredSsids = viewModel.registeredHere.mapNotNull { it.wifi.ssid }.distinct()
            StatusCard("Nearby networks") {
                DetailRow("Scanned", "${viewModel.scannedNetworks.size}")
                when (viewModel.certLookup) {
                    LookupStatus.OK -> {
                        DetailRow("Registered here", "${registeredSsids.size}")
                        if (registeredSsids.isNotEmpty()) DetailRow("SSIDs", registeredSsids.joinToString(", "))
                    }
                    LookupStatus.UNREACHABLE ->
                        DetailRow("Registered here", "unknown - map server unreachable")
                    LookupStatus.UNTRUSTED ->
                        DetailRow("Registered here", "unknown - server answer not trusted")
                    null ->
                        DetailRow("Registered here", "not checked yet")
                }
            }

            val last = viewModel.lastCheck
            StatusCard("Last check", onClick = if (last != null) onCheckDetails else null) {
                if (last == null) {
                    Text("No checks yet - start one from the Networks tab.", color = OnSurfaceMuted)
                } else {
                    StateBadge(last.result.state)
                    Text(
                        last.ssid ?: last.result.host,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        SimpleDateFormat("MMM d 'at' HH:mm", Locale.US).format(Date(last.result.timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted
                    )
                    last.modeDescription?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val base = Modifier.fillMaxWidth().padding(bottom = 16.dp)
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = OnSurfaceMuted)
            content()
        }
    }
}
