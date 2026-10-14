package com.thesis.geckowifi.ui.verdict

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.ui.graphics.vector.ImageVector
import com.thesis.geckowifi.data.model.VerificationState
import com.thesis.geckowifi.ui.CheckDetail
import com.thesis.geckowifi.ui.CheckFailure

/**
 * How loud a result is. Only [DANGER] interrupts the user (README: "Only
 * CONFLICT is meant to warn"); [POSITIVE] is a quiet confirmation and
 * [NEUTRAL] covers every "GECKO can't say" outcome, which most networks are.
 */
enum class Tone { POSITIVE, NEUTRAL, DANGER }

/** What went wrong on a CONFLICT, read from data already on the check (never from guessing at URLs). */
enum class ConflictCause { AUTH_MODE, WRONG_KEY, UNREGISTERED_PAGE, NO_ENCRYPTION, TLS_FAILED, PROOF_FAILED, ENTERPRISE }

/** The user-facing words for one check: a short [label] for badges, a [title] and a one- or two-sentence [body]. */
data class Verdict(
    val tone: Tone,
    val label: String,
    val title: String,
    val body: String,
    val icon: ImageVector
)

/** What GECKO does and doesn't vouch for (design-decisions.md, section 3). Shown with every verified result. */
const val SCOPE_NOTE = "GECKO confirms who runs the captive portal page."
    //"GECKO confirms who runs the login page. It doesn't protect what you do after logging in."

fun CheckDetail.conflictCause(): ConflictCause? {
    if (result.state != VerificationState.CONFLICT) return null
    if (result.reason?.contains("network advertises") == true) return ConflictCause.AUTH_MODE
    if (isEnterprise) return ConflictCause.ENTERPRISE
    // A successful map-server answer always attaches its metadata; a CONFLICT without it is a failed proof.
    if (result.certificateCount == null) return ConflictCause.PROOF_FAILED
    val hop = decidingHop()
    return when {
        hop?.hop?.error != null -> ConflictCause.TLS_FAILED
        hop != null && !hop.hop.isHttps -> ConflictCause.NO_ENCRYPTION
        result.reason?.contains("unexpected key") == true -> ConflictCause.WRONG_KEY
        else -> ConflictCause.UNREGISTERED_PAGE
    }
}

/** The hop that decided the result: the first CONFLICT, otherwise the last judged hop. */
fun CheckDetail.decidingHop() =
    hops.firstOrNull { it.result?.state == VerificationState.CONFLICT }
        ?: hops.lastOrNull { it.result != null }

fun CheckDetail.verdict(): Verdict {
    val name = ssid ?: result.host.ifEmpty { "This network" }
    return when (result.state) {
        VerificationState.VERIFIED -> Verdict(
            Tone.POSITIVE, "Verified", "This is the registered $name",
            if (isEnterprise) "Its login server matches the one registered for this location."
            else "Its login page matches what the operator registered for this location.",
            Icons.Outlined.GppGood
        )
        VerificationState.CONFLICT -> Verdict(
            Tone.DANGER, "Impersonation risk", "Don't use this network",
            when (conflictCause()) {
                ConflictCause.WRONG_KEY ->
                    "The login page has the right address but the wrong key. Someone is likely imitating $name."
                ConflictCause.UNREGISTERED_PAGE ->
                    "$name is registered here, but this network sent you to a login page its operator never registered. Someone may be imitating it."
                ConflictCause.NO_ENCRYPTION ->
                    "The registered login page for $name came without encryption. The real network doesn't do that."
                ConflictCause.TLS_FAILED ->
                    "The secure connection to the registered login page failed. Someone may be intercepting it, or the page is down."
                ConflictCause.PROOF_FAILED ->
                    "The map server's answer failed its cryptographic check. The lookup may have been tampered with, so this network can't be trusted."
                ConflictCause.AUTH_MODE ->
                    "$name is registered here as a different kind of network (${result.registeredIdentifier}), but this one is ${result.presentedIdentifier}. Someone may be imitating it."
                ConflictCause.ENTERPRISE, null ->
                    "The login server for $name isn't the one registered here. Someone may be imitating it."
            },
            Icons.Outlined.GppBad
        )
        VerificationState.REGISTERED -> Verdict(
            Tone.NEUTRAL, "Registered", "$name is registered here",
            when {
                result.reason?.contains("visitor") == true ->
                    "This location's network is registered with GECKO, but you're a visitor: your own institution's login server can't be registered here, so GECKO can't check it."
                else ->
                    "Its operator registered it with GECKO and its security type matches, but the registration lists nothing else to verify it against, such as a login page. GECKO can't vouch for more than that."
            },
            Icons.Outlined.GppMaybe
        )
        VerificationState.UNVERIFIED -> Verdict(
            Tone.NEUTRAL, "Not registered", "$name isn't registered here",
            "Most networks aren't. GECKO has nothing to compare it with, so it can't vouch for it either way.",
            Icons.Outlined.Shield
        )
        VerificationState.UNRECOGNIZED -> Verdict(
            Tone.NEUTRAL, "Not recognized", "Login page not recognized",
            "Networks are registered here, but none of them lists the login page $name uses. GECKO can't vouch for it.",
            Icons.Outlined.Shield
        )
        VerificationState.UNREACHABLE, VerificationState.CHECKING -> when (failure) {
            CheckFailure.JOIN_FAILED -> Verdict(
                Tone.NEUTRAL, "Not checked", "Couldn't join $name",
                "The connection was declined or timed out. Nothing was checked.",
                Icons.Outlined.WifiOff
            )
            CheckFailure.PORTAL_FAILED -> Verdict(
                Tone.NEUTRAL, "Not checked", "Couldn't load the login page",
                "The network's login page didn't respond, so there was nothing to compare.",
                Icons.Outlined.CloudOff
            )
            CheckFailure.SERVER_UNREADABLE -> Verdict(
                Tone.NEUTRAL, "Not checked", "Couldn't read the login server",
                "No handshake with the login server was seen, or this device doesn't report its certificate. Reconnect to $name while the check is running and try again.",
                Icons.Outlined.CloudOff
            )
            CheckFailure.LOW_ACCURACY -> Verdict(
                Tone.NEUTRAL, "Not checked", "Your location isn't precise enough",
                "GECKO needs a sharper location fix to tell which registration applies here. Move to a spot with a clearer sky view and try again.",
                Icons.Outlined.LocationSearching
            )
            null -> Verdict(
                Tone.NEUTRAL, "Not checked", "Couldn't reach the map server",
                "GECKO couldn't look up what's registered here. On its own this isn't a sign of an attack.",
                Icons.Outlined.CloudOff
            )
        }
    }
}

/** Badge label for a hop's own verdict in the evidence timeline. */
fun VerificationState?.hopLabel(): String = when (this) {
    null -> "Passed through"
    VerificationState.VERIFIED -> "Matches registration"
    VerificationState.REGISTERED -> "Registered"
    VerificationState.CONFLICT -> "Doesn't match"
    VerificationState.UNVERIFIED -> "Not registered"
    VerificationState.UNRECOGNIZED -> "Not recognized"
    VerificationState.UNREACHABLE, VerificationState.CHECKING -> "Not checked"
}

fun VerificationState?.hopTone(): Tone = when (this) {
    VerificationState.VERIFIED -> Tone.POSITIVE
    VerificationState.CONFLICT -> Tone.DANGER
    else -> Tone.NEUTRAL
}
