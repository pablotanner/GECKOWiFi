package com.thesis.geckowifi.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.ui.CheckDetail
import com.thesis.geckowifi.ui.components.SectionHeader
import com.thesis.geckowifi.ui.theme.Space
import com.thesis.geckowifi.ui.verdict.Tone
import com.thesis.geckowifi.ui.verdict.verdict
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Every check this session, newest first, grouped by day. Each row opens its own evidence. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(checks: List<CheckDetail>, onOpenCheck: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TopAppBar(title = { Text("History") })

        if (checks.isEmpty()) {
            Column(Modifier.padding(Space.xl)) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("No checks yet", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = Space.md))
                Text(
                    "Every network you join through GECKO WiFi shows up here, with what was checked.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.sm)
                )
            }
            return
        }

        LazyColumn(contentPadding = PaddingValues(bottom = Space.xl)) {
            checks.groupBy { dayLabel(it.result.timestamp) }.forEach { (day, dayChecks) ->
                item(key = "day:$day") { SectionHeader(day) }
                items(dayChecks, key = { it.id }) { check ->
                    HistoryRow(check, onClick = { onOpenCheck(check.id) })
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(check: CheckDetail, onClick: () -> Unit) {
    val v = check.verdict()
    val accent = when (v.tone) {
        Tone.POSITIVE -> MaterialTheme.colorScheme.primary
        Tone.DANGER -> MaterialTheme.colorScheme.error
        Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.lg)
    ) {
        Icon(v.icon, contentDescription = null, tint = accent)
        Column(Modifier.weight(1f)) {
            Text(check.ssid ?: check.result.host, style = MaterialTheme.typography.bodyLarge)
            Text(
                v.label + (if (check.isDemo) ", demo" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = if (v.tone == Tone.DANGER) accent else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(check.result.timestamp)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

private fun dayLabel(timestampMs: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val today = Calendar.getInstance()
    fun Calendar.key() = get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR)
    return when (today.key() - day.key()) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(timestampMs))
    }
}
