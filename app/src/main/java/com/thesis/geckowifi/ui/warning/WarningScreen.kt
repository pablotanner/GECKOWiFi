package com.thesis.geckowifi.ui.warning

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.ui.CheckDetail
import com.thesis.geckowifi.ui.components.StackedValue
import com.thesis.geckowifi.ui.theme.Space
import com.thesis.geckowifi.ui.verdict.ConflictCause
import com.thesis.geckowifi.ui.verdict.conflictCause
import com.thesis.geckowifi.ui.verdict.decidingHop
import com.thesis.geckowifi.ui.verdict.verdict

/**
 * The only screen that interrupts the user, and only for a CONFLICT.
 *
 * Built after the opinionated-warning pattern (Felt et al., CHI 2015): one
 * recommended action, visually dominant and in the alarm colour; the
 * explanation in the network's own terms; evidence one tap away; the
 * override is the least prominent element on the screen. System back counts
 * as the safe choice and disconnects, like Chrome's "Back to safety".
 */
@Composable
fun WarningScreen(
    check: CheckDetail,
    onDisconnect: () -> Unit,
    onStay: () -> Unit,
    onEvidence: () -> Unit
) {
    BackHandler(onBack = onDisconnect)
    val v = check.verdict()
    val colors = MaterialTheme.colorScheme
    val name = check.ssid ?: check.result.host

    Surface(Modifier.fillMaxSize(), color = colors.errorContainer, contentColor = colors.onErrorContainer) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Space.xl)
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = Space.xxl)
            ) {
                Icon(Icons.Outlined.GppBad, contentDescription = null, modifier = Modifier.size(56.dp), tint = colors.error)
                Text(
                    "Warning: $name",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = Space.xl)
                )
                Text(
                    v.title,
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.padding(top = Space.sm).semantics { heading() }
                )
                Text(
                    v.body,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = Space.lg)
                )
                Text(
                    "Don't log in or enter payment details on its page.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = Space.md)
                )

                Comparison(check)
            }

            Column(Modifier.padding(vertical = Space.lg)) {
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                ) { Text(if (check.joined) "Disconnect" else "Leave this network", style = MaterialTheme.typography.titleMedium) }
                OutlinedButton(
                    onClick = onEvidence,
                    modifier = Modifier.fillMaxWidth().padding(top = Space.sm).heightIn(min = 48.dp)
                ) { Text("What GECKO saw", color = colors.onErrorContainer) }
                TextButton(
                    onClick = onStay,
                    modifier = Modifier.fillMaxWidth().padding(top = Space.xs).heightIn(min = 48.dp)
                ) {
                    Text(
                        "Stay connected anyway",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onErrorContainer
                    )
                }
            }
        }
    }
}

/** Registered vs. presented, when the check knows both sides. Domains and keys, never paraphrased. */
@Composable
private fun Comparison(check: CheckDetail) {
    // A failed proof is about the map server, not about anything the network presented.
    if (check.conflictCause() == ConflictCause.PROOF_FAILED) return
    val result = check.result
    val hop = check.decidingHop()?.hop
    val presented = result.presentedIdentifier ?: hop?.let { "${it.scheme}://${it.host}" }
    val registered = result.registeredIdentifier
    if (presented == null && registered == null) return
    val isKey = check.conflictCause() == ConflictCause.WRONG_KEY

    Spacer(Modifier.size(Space.xl))
    Surface(color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(Space.lg)) {
            if (isKey && hop != null) StackedValue("Login page", hop.host)
            registered?.let { StackedValue(if (isKey) "Registered key" else "Registered", it) }
            if (registered != null && presented != null) HorizontalDivider(Modifier.padding(vertical = Space.xs), color = MaterialTheme.colorScheme.outlineVariant)
            presented?.let { StackedValue(if (isKey) "Key this network presented" else "This network presented", it, MaterialTheme.colorScheme.error) }
        }
    }
}
