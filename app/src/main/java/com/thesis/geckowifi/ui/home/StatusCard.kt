package com.thesis.geckowifi.ui.home

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thesis.geckowifi.ui.ActiveCheck
import com.thesis.geckowifi.ui.CheckDetail
import com.thesis.geckowifi.ui.CheckStep
import com.thesis.geckowifi.ui.CheckFailure
import com.thesis.geckowifi.ui.VerificationViewModel
import com.thesis.geckowifi.ui.components.VerdictBadge
import com.thesis.geckowifi.ui.components.colors
import com.thesis.geckowifi.ui.theme.MonoFontFamily
import com.thesis.geckowifi.ui.theme.Radius
import com.thesis.geckowifi.ui.theme.Space
import com.thesis.geckowifi.ui.verdict.scopeNote
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
    val device = viewModel.deviceSsid
    val state: CardState = when {
        active != null -> CardState.Checking(active)
        current != null -> CardState.Result(current)
        // The device's own connection, with what GECKO last found for it (D20).
        device != null -> CardState.Connected(device, viewModel.latestVerdictFor(device))
        else -> CardState.Idle
    }
    val tone = (state as? CardState.Result)?.check?.verdict()?.tone
    val context = LocalContext.current
    // Where the user switches networks themselves: Android doesn't leave a working one for a suggestion (D27).
    val openWifi = { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
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
                    is CardState.Checking -> CheckingContent(s.check, device, onOpenWifi = openWifi, onCancel = viewModel::cancelCheck)
                    is CardState.Result -> ResultContent(
                        check = s.check,
                        labMode = viewModel.labMode,
                        onDetails = { onOpenCheck(s.check.id) },
                        onDisconnect = viewModel::disconnect,
                        onDismiss = viewModel::dismissCurrent,
                        onOpenWifi = openWifi,
                        onForget = s.check.ssid?.takeIf(viewModel::isSetUpByGecko)?.let { ssid -> { viewModel.forgetEnterpriseNetwork(ssid) } },
                        onRetry = { viewModel.retry(s.check) }
                    )
                    is CardState.Connected -> ConnectedContent(
                        s.ssid, s.verdict, unverifiedNote(viewModel, s.ssid),
                        onDetails = { s.verdict?.let { onOpenCheck(it.id) } },
                        onForget = if (viewModel.isSetUpByGecko(s.ssid)) ({ viewModel.forgetEnterpriseNetwork(s.ssid) }) else null
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
    data class Connected(val ssid: String, val verdict: CheckDetail?) : CardState("connected:$ssid:${verdict?.id}")
}

/**
 * The device is on [ssid] (joined through Android or an earlier GECKO setup); [verdict] is GECKO's latest for it.
 * [onForget] is non-null while GECKO's profile for [ssid] is installed: after "Done" or a restart, this is where it is removed.
 */
@Composable
private fun ConnectedContent(ssid: String, verdict: CheckDetail?, note: String, onDetails: () -> Unit, onForget: (() -> Unit)?) {
    val v = verdict?.verdict()
    val colors = MaterialTheme.colorScheme
    Text("Connected to", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
    Text(ssid, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = Space.xs))
    if (v == null) {
        Text(
            note,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.sm)
        )
    } else {
        Row(Modifier.padding(top = Space.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            VerdictBadge(v.tone, v.label, v.icon)
        }
        Text(v.body, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Space.sm))
    }
    if (v != null || onForget != null) {
        Row(
            Modifier.padding(top = Space.lg),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (v != null) OutlinedButton(onClick = onDetails, modifier = Modifier.heightIn(min = 48.dp)) { Text("Details") }
            if (onForget != null) TextButton(onClick = onForget, modifier = Modifier.heightIn(min = 48.dp)) { Text("Forget network") }
        }
    }
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

/**
 * The connected card without a verdict: why GECKO has nothing to say about
 * [ssid]. A failed lookup is said out loud, so it isn't mistaken for a clean
 * result.
 */
private fun unverifiedNote(viewModel: VerificationViewModel, ssid: String): String {
    val savedAt = viewModel.registrationsSavedAt
    val registered = viewModel.registeredHere.any { it.wifi.ssid.equals(ssid, ignoreCase = true) }
    return when {
        viewModel.location == null -> "Waiting for your location to look up what's registered here."
        viewModel.certLookup == null -> "Looking up what's registered here."
        viewModel.certLookup == LookupStatus.UNREACHABLE ->
            "GECKO can't reach the map server over this network, so it doesn't know whether $ssid is registered here."
        viewModel.certLookup == LookupStatus.UNTRUSTED ->
            "The map server's last answer failed verification, so GECKO can't say anything about $ssid."
        !registered -> "$ssid isn't registered with GECKO here, so there is nothing to check." +
            savedAt?.let { " (Saved registrations from ${com.thesis.geckowifi.ui.timeText(it)}.)" }.orEmpty()
        else -> "GECKO hasn't checked this network yet." +
            savedAt?.let { " Using saved registrations from ${com.thesis.geckowifi.ui.timeText(it)}." }.orEmpty()
    }
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
    val saved = viewModel.registrationsSavedAt?.let { " Saved list from ${com.thesis.geckowifi.ui.timeText(it)}." }.orEmpty()
    return "$lookup$saved $accuracy"
}

private val stepLabels = mapOf(
    CheckStep.JOIN to "Joining the network",
    CheckStep.LOOKUP to "Looking up what's registered here",
    CheckStep.PORTAL to "Following the login page",
    CheckStep.COMPARE to "Comparing with the registration",
    CheckStep.SERVER to "Waiting for the login server. Reconnect to the network now",
    CheckStep.ONBOARD to "Checking the network",
    CheckStep.CONFIRM to "Confirming the login server",
    CheckStep.CONNECT to "Connecting with your account"
)

/** [deviceSsid]: the device's own Wi-Fi; while connecting, being on another network means Android likely stays there. */
@Composable
private fun CheckingContent(check: ActiveCheck, deviceSsid: String?, onOpenWifi: () -> Unit, onCancel: () -> Unit) {
    val onOther = check.current == CheckStep.CONNECT && deviceSsid != null && !deviceSsid.equals(check.ssid, ignoreCase = true)
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
    val hint = when {
        onOther -> "You're on $deviceSsid, and Android usually stays on a working network. Tap ${check.ssid} in Wi-Fi settings to switch."
        check.current == CheckStep.JOIN || check.current == CheckStep.ONBOARD -> "Android may ask you to allow the connection."
        check.current == CheckStep.CONNECT -> "The first time, allow GECKO's suggested networks in Android's notification."
        else -> null
    }
    if (hint != null) {
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.md)
        )
    }
    Row(Modifier.padding(top = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
        if (onOther) OutlinedButton(onClick = onOpenWifi, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Wi-Fi settings") }
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
    }
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
    onDismiss: () -> Unit,
    onOpenWifi: () -> Unit,
    /** Non-null while GECKO's profile for this network is installed. */
    onForget: (() -> Unit)?,
    onRetry: () -> Unit
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
            check.scopeNote,
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
        } else if (check.failure == CheckFailure.SERVER_NOT_CONFIRMED || check.failure == CheckFailure.ONBOARDING_FAILED) {
            // A technical failure: trying again is what helps.
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
            TextButton(onClick = onDetails, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Details", color = toneColors.content)
            }
            TextButton(onClick = leave, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(leaveLabel, color = toneColors.content)
            }
        } else {
            // Set up but Android stayed elsewhere: switching is the one thing left to do (D27).
            if (check.failure == CheckFailure.ANDROID_STAYED) {
                Button(onClick = onOpenWifi, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Wi-Fi settings") }
                TextButton(onClick = onDetails, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Details", color = toneColors.content)
                }
            } else {
                OutlinedButton(onClick = onDetails, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Details", color = toneColors.content)
                }
            }
            TextButton(onClick = leave, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(leaveLabel, color = toneColors.content)
            }
            // A profile GECKO installed stays until removed; also offered on the connected card and in Settings.
            if (onForget != null) {
                TextButton(onClick = onForget, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Forget network", color = toneColors.content)
                }
            }
        }
    }
}
