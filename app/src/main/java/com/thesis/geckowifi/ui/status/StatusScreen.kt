package com.thesis.geckowifi.ui.status

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.ui.theme.OnSurfaceMuted

/**
 * Deliberately a placeholder, not a port of the design's Status screen -
 * that screen needs continuous background network monitoring, a
 * protection on/off enforcement toggle, and a persisted "last checked"
 * timestamp, none of which exist anywhere in this app yet (see README.md's
 * "What needs to be done"). Showing fake versions of any of those would be
 * exactly the "dead code with fabricated data" this build avoids - an
 * honest "not available yet" is the accurate thing to show instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen() {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Status") })
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.Timeline, contentDescription = null, tint = OnSurfaceMuted)
            Text(
                "Live status isn't available yet",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                "This needs continuous background monitoring and an enforcement " +
                    "toggle this build doesn't implement. Use the Networks tab to " +
                    "run a check, or Activity to see past ones.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
