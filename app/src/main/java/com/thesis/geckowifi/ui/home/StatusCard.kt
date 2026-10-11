package com.thesis.geckowifi.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.ui.ActiveCheck
import com.thesis.geckowifi.ui.CheckDetail
import com.thesis.geckowifi.ui.CheckStep
import com.thesis.geckowifi.ui.VerificationViewModel
import com.thesis.geckowifi.ui.components.VerdictBadge
import com.thesis.geckowifi.ui.components.colors
import com.thesis.geckowifi.ui.theme.MonoFontFamily
import com.thesis.geckowifi.ui.theme.Radius
import com.thesis.geckowifi.ui.theme.Space
import com.thesis.geckowifi.ui.verdict.SCOPE_NOTE
import com.thesis.geckowifi.ui.verdict.Tone
import com.thesis.geckowifi.ui.verdict.verdict
import com.thesis.geckowifi.verification.LookupStatus

/**
 * The status card at the top of the home screen. One surface that moves
 * through idle, checking and result, so the answer always appears where the
 * user is already looking. A live region, so TalkBack reads each change.
 */
@Composable
fun StatusCard(viewModel: VerificationViewModel, onOpenCheck: (String) -> Unit, modifier: Modifier = Modifier) {
    val active = viewModel.activeCheck
    val current = viewModel.current
    val state: CardState = when {
        active != null -> CardState.Checking(active)
        current != null -> CardState.Result(current)
        else -> CardState.Idle
    }
    val tone = (state as? CardState.Result)?.check?.verdict()?.tone
    val container by animateColorAsState(
        when (tone) {
            null -> MaterialTheme.colorScheme.surfaceContainer
            else -> tone.colors().container
        },
        animationSpec = tween(250),
        label = "statusContainer"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(Radius.lg),
        color = container
    ) {
        AnimatedContent(
            targetState = state,
            contentKey = { it.key },
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
            label = "statusCard"
        ) { s ->
            Column(Modifier.padding(Space.xl)) {
                when (s) {
                    CardState.Idle -> IdleContent(viewModel)
                    is CardState.Checking -> CheckingContent(s.check, onCancel = viewModel::cancelCheck)
                    is CardState.Result -> ResultContent(
                        check = s.check,
                        labMode = viewModel.labMode,
                        onDetails = { onOpenCheck(s.check.id) },
                        onDisconnect = viewModel::disconnect,
                        onDismiss = viewModel::dismissCurrent
                    )
                }
            }
        }
    }
}

private sealed class CardState(val key: String) {
    data object Idle : CardState("idle")
    data class Checking(val check: ActiveCheck) : CardState("checking")
    data class Result(val check: CheckDetail) : CardState("result:${check.id}")
}

@Composable
private fun IdleContent(viewModel: VerificationViewModel) {
    Icon(
        Icons.Outlined.WifiFind,
        contentDescription = null,
        modifier = Modifier.size(32.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        "Not connected",
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.padding(top = Space.md)
    )
    Text(
        "Choose a network below. GECKO checks it while joining and only speaks up if something is wrong.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.sm)
    )
    Text(
        lookupSummary(viewModel),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Space.lg)
    )
}

private fun lookupSummary(viewModel: VerificationViewModel): String {
    val fix = viewModel.location ?: return "Waiting for your location."
    val accuracy = "Location accurate to ${fix.accuracyMeters.toInt()} m."
    val lookup = when (viewModel.certLookup) {
        LookupStatus.OK -> when (val n = viewModel.registeredHere.mapNotNull { it.wifi.ssid }.distinct().size) {
            0 -> "No networks are registered around you."
            1 -> "1 network is registered around you."
            else -> "$n networks are registered around you."
        }
        LookupStatus.UNREACHABLE -> "Map server not reachable yet. Registrations show up once you join a network."
        LookupStatus.UNTRUSTED -> "The map server's last answer failed verification."
        null -> "Looking up registrations."
    }
    return "$lookup $accuracy"
}

private val stepLabels = mapOf(
    CheckStep.JOIN to "Joining the network",
    CheckStep.LOOKUP to "Looking up what's registered here",
    CheckStep.PORTAL to "Following the login page",
    CheckStep.COMPARE to "Comparing with the registration"
)

@Composable
private fun CheckingContent(check: ActiveCheck, onCancel: () -> Unit) {
    Text("Checking", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Text(
        check.ssid,
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.padding(top = Space.xs)
    )
    Column(
        Modifier.padding(top = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        val currentIndex = check.steps.indexOf(check.current)
        check.steps.forEachIndexed { i, step ->
            StepRow(stepLabels.getValue(step), done = i < currentIndex, active = i == currentIndex)
        }
    }
    if (check.current == CheckStep.JOIN) {
        Text(
            "Android may ask you to allow the connection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.md)
        )
    }
    TextButton(onClick = onCancel, modifier = Modifier.padding(top = Space.sm)) { Text("Cancel") }
}

@Composable
private fun StepRow(label: String, done: Boolean, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Surface(shape = CircleShape, color = colors.primary, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Outlined.Check, contentDescription = "Done", tint = colors.onPrimary, modifier = Modifier.padding(3.dp))
                }
                active -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else -> Surface(shape = CircleShape, color = Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.5.dp, colors.outline), modifier = Modifier.size(14.dp)) {}
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (done || active) colors.onSurface else colors.onSurfaceVariant
        )
    }
}

@Composable
private fun ResultContent(
    check: CheckDetail,
    labMode: Boolean,
    onDetails: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val v = check.verdict()
    val toneColors = v.tone.colors()
    val colors = MaterialTheme.colorScheme

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Icon(v.icon, contentDescription = null, tint = toneColors.content, modifier = Modifier.size(28.dp))
        Text(
            check.ssid ?: check.result.host,
            style = MaterialTheme.typography.titleMedium,
            color = toneColors.content,
            modifier = Modifier.weight(1f)
        )
        if (check.isDemo) VerdictBadge(Tone.NEUTRAL, "Demo", null)
    }
    Text(
        v.title,
        style = MaterialTheme.typography.headlineMedium,
        color = toneColors.content,
        modifier = Modifier.padding(top = Space.md)
    )
    Text(
        v.body,
        style = MaterialTheme.typography.bodyLarge,
        color = toneColors.content,
        modifier = Modifier.padding(top = Space.sm)
    )
    if (v.tone == Tone.POSITIVE) {
        Text(
            SCOPE_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = toneColors.content,
            modifier = Modifier.padding(top = Space.md)
        )
    }
    if (labMode && check.bssid != null) {
        Text(
            check.bssid,
            fontFamily = MonoFontFamily,
            style = MaterialTheme.typography.bodySmall,
            color = toneColors.content,
            modifier = Modifier.padding(top = Space.sm)
        )
    }

    Row(
        Modifier.padding(top = Space.lg),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val leaveLabel = if (check.joined) "Disconnect" else "Done"
        val leave = if (check.joined) onDisconnect else onDismiss
        if (v.tone == Tone.DANGER) {
            // The safe action leads, in the alarm colour; details stay secondary.
            Button(
                onClick = leave,
                colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(leaveLabel) }
            TextButton(onClick = onDetails, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("What GECKO saw", color = toneColors.content)
            }
        } else {
            OutlinedButton(onClick = onDetails, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Details", color = toneColors.content)
            }
            TextButton(onClick = leave, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(leaveLabel, color = toneColors.content)
            }
        }
    }
}
