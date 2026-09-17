package com.thesis.geckowifi.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import com.thesis.geckowifi.data.local.VerificationRecord
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.ui.VerificationViewModel
import com.thesis.geckowifi.ui.components.StateBadge
import com.thesis.geckowifi.ui.theme.Divider
import com.thesis.geckowifi.ui.theme.OnSurfaceMuted
import com.thesis.geckowifi.ui.theme.OnSurfaceVariant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real, session-scoped (not persisted across app restarts - see
 * `data/local/Stores.kt`'s `InMemoryHistoryStore`) log of every check this
 * app has actually performed, grouped by day like the design. Each row's
 * subtitle is the real `reason` string from the `VerificationResult` that
 * was recorded, not the design's hand-written copy - see the implementation
 * plan for why (the polished copy needs data, like a registered auth mode
 * per history entry, this app doesn't currently track per-record).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(viewModel: VerificationViewModel) {
    Column(Modifier.fillMaxWidth()) {
        TopAppBar(title = { Text("Activity") })

        if (viewModel.history.isEmpty()) {
            Text(
                "No checks yet. Tap a network on the Networks tab to run one.",
                modifier = Modifier.padding(16.dp),
                color = OnSurfaceMuted
            )
            return
        }

        val groups = viewModel.history
            .sortedByDescending { it.timestamp }
            .groupBy { dayLabel(it.timestamp) }

        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            groups.forEach { (day, records) ->
                item {
                    Text(
                        day,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = OnSurfaceVariant
                    )
                }
                items(records) { record ->
                    ActivityRow(record)
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = Divider)
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(record: VerificationRecord) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Outlined.Wifi, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(record.ssid ?: record.host, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${timeLabel(record.timestamp)} · ${record.reason ?: record.state}",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted
            )
        }
        StateBadge(runCatching { VerificationState.valueOf(record.state) }.getOrDefault(VerificationState.UNVERIFIED))
    }
}

private fun dayLabel(timestampMs: Long): String {
    val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    val recordDay = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(timestampMs))
    return when (recordDay) {
        today -> "Today"
        else -> SimpleDateFormat("EEEE, MMM d", Locale.US).format(Date(timestampMs))
    }
}

private fun timeLabel(timestampMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(timestampMs))
