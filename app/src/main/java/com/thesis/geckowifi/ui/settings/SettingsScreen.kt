package com.thesis.geckowifi.ui.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.thesis.geckowifi.eduroam.AccountInfo
import com.thesis.geckowifi.ui.EnterpriseController
import com.thesis.geckowifi.ui.timeText
import com.thesis.geckowifi.ui.viaDescription
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.thesis.geckowifi.di.AppModule
import com.thesis.geckowifi.ui.SERVER_URL
import com.thesis.geckowifi.ui.VerificationViewModel
import com.thesis.geckowifi.ui.components.DetailRow
import com.thesis.geckowifi.ui.components.SectionCard
import com.thesis.geckowifi.ui.components.SectionHeader
import com.thesis.geckowifi.ui.theme.Space
import com.thesis.geckowifi.verification.LookupStatus

/**
 * Deliberately almost empty for the end user: GECKO has nothing they need
 * to configure. What it does and doesn't protect against is explained here
 * once, instead of on every result. The researcher's controls (radius, log,
 * diagnostics) sit behind lab mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: VerificationViewModel, onOpenSavedData: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth()) {
        TopAppBar(title = { Text("Settings") })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Space.xxl)) {
            viewModel.enterprise?.let { AccountsSection(it) }

            SectionHeader("Saved GECKO data")
            SectionCard(null, Modifier.padding(horizontal = Space.lg)) {
                val saved = viewModel.savedLookup
                Text(
                    if (saved == null) "Nothing saved yet."
                    else "Registrations of ${saved.certificates.mapNotNull { it.wifi.ssid }.distinct().size} networks, fetched " +
                        "${timeText(saved.fetchedAt)} over ${saved.viaDescription()}.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(onClick = onOpenSavedData, modifier = Modifier.padding(top = Space.md).heightIn(min = 48.dp)) {
                    Text("Show saved data")
                }
            }

            SectionHeader("How GECKO protects you")
            SectionCard(null, Modifier.padding(horizontal = Space.lg)) {
                Text(
                    "Network operators register their WiFi and its login page for the place where it is. " +
                        "When you join a network, GECKO compares it with that registration and warns you " +
                        "if someone is imitating it.",
                    style = MaterialTheme.typography.bodyLarge
                )
                ScopeLine(true, "Fake networks with a copied name and login page")
                ScopeLine(true, "Login or payment pages swapped for someone else's")
                ScopeLine(true, "Fake enterprise networks (e.g. eduroam clones) trying to steal your password")
                ScopeLine(false, "Networks not certified by GECKO")
                ScopeLine(false, "What happens to your traffic after you log in (TODO; requires Root)")
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Space.xl)
                    .toggleable(value = viewModel.labMode, role = Role.Switch, onValueChange = viewModel::updateLabMode)
                    .heightIn(min = 64.dp)
                    .padding(horizontal = Space.lg, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.lg)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Lab mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Lists every access point, adds a manual login domain and shows diagnostics. For testing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = viewModel.labMode, onCheckedChange = null)
            }

            if (viewModel.labMode) LabSection(viewModel)
        }
    }
}

@Composable
private fun ScopeLine(covered: Boolean, text: String) {
    Row(Modifier.padding(top = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        val icon: ImageVector = if (covered) Icons.Outlined.Check else Icons.Outlined.Close
        Icon(
            icon,
            contentDescription = if (covered) "Detected" else "Not covered",
            tint = if (covered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LabSection(viewModel: VerificationViewModel) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SectionHeader("Lab", Modifier.padding(start = 0.dp))

        SectionCard("Search radius") {
            var radius by remember { mutableFloatStateOf(viewModel.queryRadiusMeters.toFloat()) }
            Text("${radius.toInt()} m around your location", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = radius,
                onValueChange = { radius = it },
                onValueChangeFinished = { viewModel.updateQueryRadius(radius.toInt()) },
                valueRange = 10f..250f
            )
        }

        SectionCard("Diagnostics") {
            DetailRow("Map server", SERVER_URL)
            DetailRow(
                "Lookup",
                when (viewModel.certLookup) {
                    LookupStatus.OK -> "OK, ${viewModel.registeredHere.size} certificates"
                    LookupStatus.UNREACHABLE -> "Unreachable"
                    LookupStatus.UNTRUSTED -> "Proof failed"
                    null -> "Not yet"
                },
                mono = false
            )
            val ssids = viewModel.registeredHere.mapNotNull { it.wifi.ssid }.distinct()
            if (ssids.isNotEmpty()) DetailRow("Registered SSIDs", ssids.joinToString(", "))
            val fix = viewModel.location
            if (fix == null) {
                DetailRow("Location", "No fix", mono = false)
            } else {
                DetailRow("Location", "%.5f, %.5f".format(fix.latitude, fix.longitude))
                DetailRow("Accuracy", "${fix.accuracyMeters.toInt()} m")
                fix.provider?.let { DetailRow("Provider", it) }
            }
            DetailRow("Scan results", "${viewModel.scannedNetworks.size}")
        }

        SectionCard("Check log") {
            Text(
                "Every check is written as one JSON line with all login-page hops and verdicts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
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
                },
                modifier = Modifier.padding(top = Space.md).heightIn(min = 48.dp)
            ) { Text("Share latest log") }
        }
    }
}

/**
 * The user's enterprise accounts (docs/eduroam-decisions.md, D19): one per home
 * provider, used on every network that accepts it. Normally added from the
 * sign-in sheet when a network needs one; here they can be added and removed.
 */
@Composable
private fun AccountsSection(flow: EnterpriseController) {
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<AccountInfo?>(null) }

    SectionHeader("Accounts")
    SectionCard(null, Modifier.padding(horizontal = Space.lg)) {
        Text(
            "Accounts GECKO can sign you in with, such as your eduroam account. For each network, GECKO picks the account it accepts " +
                "and only gives your password to a login server it has verified.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (flow.accounts.isEmpty()) {
            Text(
                "No accounts yet. GECKO asks for one the first time you connect to a network that needs it.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Space.md)
            )
        }
        flow.accounts.forEach { account ->
            Row(
                Modifier.fillMaxWidth().padding(top = Space.sm).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(account.identity, style = MaterialTheme.typography.bodyLarge)
                    Text(account.label ?: account.realm ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { removing = account }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Remove ${account.identity}", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.padding(top = Space.md).heightIn(min = 48.dp)) {
            Text("Add account")
        }
    }

    if (adding) AddAccountDialog(onAdd = { id, pw -> flow.addAccount(id, pw); adding = false }, onDismiss = { adding = false })
    removing?.let { account ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${account.identity}?") },
            text = { Text("GECKO forgets this account and its password. Networks that are already set up keep working until you forget them.") },
            confirmButton = {
                Button(
                    onClick = { flow.removeAccount(account.id); removing = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AddAccountDialog(onAdd: (identity: String, password: String) -> Unit, onDismiss: () -> Unit) {
    var identity by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val valid = identity.trim().substringAfterLast('@', "").contains('.') && password.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                OutlinedTextField(
                    value = identity, onValueChange = { identity = it },
                    label = { Text("Account") }, placeholder = { Text("name@institution") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = { onAdd(identity.trim(), password) }, enabled = valid) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
