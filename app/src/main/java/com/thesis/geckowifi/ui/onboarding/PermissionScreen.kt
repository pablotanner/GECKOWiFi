package com.thesis.geckowifi.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.ui.theme.Space

/**
 * Shown before Android's permission dialog, never instead of it: says why
 * GECKO needs location in one sentence, so the system prompt isn't a
 * surprise. After a denial it offers the app settings page, because the
 * system dialog may not appear again.
 */
@Composable
fun PermissionScreen(deniedBefore: Boolean, onAllow: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Space.xl)
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = Space.xxl * 2)) {
            Icon(Icons.Outlined.GppGood, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text(
                "Know which WiFi is the real one",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(top = Space.xl).semantics { heading() }
            )
            Text(
                "GECKO checks networks against what their operators registered for this place, and warns you about imitations.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.lg)
            )
            Spacer(Modifier.size(Space.xl))
            Reason(Icons.Outlined.LocationOn, "Your location decides which registrations apply. The map server is asked about the area around you.")
            Reason(Icons.Outlined.Wifi, "Android also needs location access to list nearby WiFi networks.")
        }
        Column(Modifier.padding(vertical = Space.lg)) {
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Allow location", style = MaterialTheme.typography.titleMedium)
            }
            if (deniedBefore) {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = Space.xs).heightIn(min = 48.dp)
                ) { Text("Open app settings") }
            }
        }
    }
}

@Composable
private fun Reason(icon: ImageVector, text: String) {
    Row(Modifier.padding(top = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
    }
}
