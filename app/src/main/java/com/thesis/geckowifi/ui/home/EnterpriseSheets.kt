package com.thesis.geckowifi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.eduroam.PinSource
import com.thesis.geckowifi.eduroam.SignInPrompt
import com.thesis.geckowifi.eduroam.UnusableAccount
import com.thesis.geckowifi.ui.ChoiceRequest
import com.thesis.geckowifi.ui.theme.Space

/**
 * What the sheet says, from the network's registration (docs/eduroam-decisions.md,
 * D19): the federation's name, or the operator's name and its realms. Never
 * "eduroam" unless the registration says so.
 */
fun SignInPrompt.explanation(): String {
    val who = if (federation != null) {
        "$ssid is part of $federation. Sign in with your $federation account from your institution or organisation."
    } else {
        val realms = localRealms.joinToString(", ") { "@" + it.removePrefix("*.") }
        "$ssid is run by ${operatorName ?: "its operator"}. Sign in with your account" + (if (realms.isNotEmpty()) " ($realms)." else ".")
    }
    return who
}

/** Why a saved account can't sign in here, in one sentence. */
fun SignInPrompt.reason(unusable: UnusableAccount): String {
    val realm = unusable.account.realm ?: "its domain"
    return when (unusable.why) {
        UnusableAccount.Why.NOT_ACCEPTED -> "$ssid doesn't accept accounts from $realm."
        UnusableAccount.Why.NO_RECORD ->
            "$realm hasn't registered its login server with GECKO" + (federation?.let { " for $it" } ?: "") + ", so GECKO can't confirm it."
        UnusableAccount.Why.NOT_SAVED ->
            "GECKO has no login server details for $realm saved and can't fetch them on this network. Open GECKO once on a network with internet."
        UnusableAccount.Why.INCOMPLETE_REGISTRATION ->
            "The registration of $ssid doesn't include the login server certificate GECKO needs to protect this account."
    }
}

private fun SignInPrompt.identityHint(): String =
    localRealms.firstOrNull()?.takeIf { federation == null }?.let { "name@${it.removePrefix("*.")}" } ?: "name@institution"

/**
 * Asks for an account when none of the saved ones can be used at the network,
 * saying why for each. A question, not a check result: dismissing it changes
 * nothing. [onAndroidSettings] is the way out for networks GECKO can't help with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInSheet(
    prompt: SignInPrompt,
    onSubmit: (identity: String, password: String) -> Unit,
    onDismiss: () -> Unit,
    onAndroidSettings: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var identity by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val identityValid = identity.trim().substringAfterLast('@', "").contains('.')
    val canSubmit = identityValid && password.isNotEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg)
                .padding(bottom = Space.lg)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text("Sign in to ${prompt.ssid}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(prompt.explanation(), style = MaterialTheme.typography.bodyLarge)
            if (prompt.unusable.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text(
                        if (prompt.unusable.size == 1) "Your saved account can't be used here" else "Your saved accounts can't be used here",
                        style = MaterialTheme.typography.titleSmall
                    )
                    prompt.unusable.forEach { u ->
                        Column {
                            Text(u.account.identity, style = MaterialTheme.typography.bodyMedium)
                            Text(prompt.reason(u), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = identity,
                onValueChange = { identity = it },
                label = { Text("Account") },
                placeholder = { Text(prompt.identityHint()) },
                singleLine = true,
                isError = identity.isNotBlank() && !identityValid,
                supportingText = if (identity.isNotBlank() && !identityValid) ({ Text("Use the full account, including @ and your institution's domain") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "GECKO only gives your password to a login server it has verified belongs to your provider. " +
                    "Your account is saved for other networks that accept it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onAndroidSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Android settings") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                Button(onClick = { onSubmit(identity.trim(), password) }, enabled = canSubmit, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Connect")
                }
            }
        }
    }
}

/** Several saved accounts fit; the user picks one, remembered for this network. */
@Composable
fun AccountChooser(request: ChoiceRequest, onChoose: (accountId: String) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(request.options.first().match.account.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which account for ${request.run.target.ssid}?") },
        text = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(
                    "More than one of your accounts can sign in here. GECKO remembers your choice for this network.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = Space.sm)
                )
                request.options.forEach { option ->
                    val account = option.match.account
                    val provider = (option.pins.source as? PinSource.RealmRecord)?.displayName ?: account.label
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = selected == account.id, onClick = { selected = account.id }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == account.id, onClick = null)
                        Column(Modifier.padding(start = Space.sm)) {
                            Text(account.identity, style = MaterialTheme.typography.bodyLarge)
                            provider?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onChoose(selected) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
