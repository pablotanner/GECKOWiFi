package com.thesis.geckowifi.eduroam

import java.security.cert.X509Certificate

/**
 * The user's eduroam account. The password can't be read from outside this
 * class: the only code that can is the nested [EnterpriseProfileBuilder], and
 * it only hands it out together with a [ProfilePlan] made from
 * [AuthenticatedEapPins] for the account's own realm. That is the credential
 * invariant of the eduroam flow (eduroam-brief.md, section 2): credentials
 * are only ever attached to an enterprise configuration whose CA and
 * server-name pins came from an authenticated source.
 */
class EduroamCredentials(identity: String, password: String) {
    val identity: String = identity.trim()
    private val password: String = password

    /** Lower-case realm of [identity] (`user@realm`), or null if there is none. */
    val realm: String? = this.identity.substringAfterLast('@', "").lowercase().ifBlank { null }

    val hasPassword: Boolean get() = password.isNotEmpty()

    override fun toString() = "EduroamCredentials($identity, password=***)"

    /**
     * The single code path where credentials meet an enterprise config. Nested
     * so it is the only code outside [EduroamCredentials] that can read the
     * password (Kotlin lets a nested class see its outer class's private members).
     */
    object EnterpriseProfileBuilder {
        /**
         * What would be installed, without the password. Throws
         * [CredentialsRefused] unless [pins] are for [credentials]' realm: pins
         * for another realm say nothing about where this account's password
         * may go.
         */
        fun plan(pins: AuthenticatedEapPins, credentials: EduroamCredentials, network: TargetNetwork): ProfilePlan {
            val realm = credentials.realm ?: throw CredentialsRefused("the identity ${credentials.identity} has no realm")
            if (!realm.equals(pins.realm, ignoreCase = true)) {
                throw CredentialsRefused("pins are for realm ${pins.realm}, the account is in $realm")
            }
            if (!credentials.hasPassword) throw CredentialsRefused("no password")
            // AuthenticatedEapPins already guarantees these; checked again because
            // this is the last point before the password leaves the app's hands.
            if (pins.caCertificates.isEmpty() || pins.serverNames.isEmpty()) {
                throw CredentialsRefused("pins without a CA certificate and a server name")
            }
            return ProfilePlan(
                ssid = network.ssid,
                wpa3 = network.wpa3,
                realm = pins.realm,
                method = pins.method,
                phase2 = pins.phase2,
                identity = credentials.identity,
                outerIdentity = pins.outerIdentity,
                caCertificates = pins.caCertificates,
                serverNames = pins.serverNames,
                source = pins.source
            )
        }

        /** Builds the [plan] and gives it, with the password, to [installer]. Throws [CredentialsRefused] like [plan]. */
        suspend fun install(
            pins: AuthenticatedEapPins,
            credentials: EduroamCredentials,
            network: TargetNetwork,
            installer: EnterpriseProfileInstaller
        ): InstallOutcome = installer.install(plan(pins, credentials, network), credentials.password)
    }
}

typealias EnterpriseProfileBuilder = EduroamCredentials.EnterpriseProfileBuilder

class CredentialsRefused(reason: String) : IllegalStateException("credentials refused: $reason")

/** The network the profile is for. No BSSID: a pinned profile is safe on every access point of the SSID. */
data class TargetNetwork(val ssid: String, val wpa3: Boolean = false)

/** Everything an installer writes into the supplicant's configuration, except the password. */
data class ProfilePlan(
    val ssid: String,
    val wpa3: Boolean,
    val realm: String,
    val method: EapMethod,
    val phase2: Phase2,
    val identity: String,
    val outerIdentity: String,
    val caCertificates: List<X509Certificate>,
    val serverNames: List<String>,
    val source: PinSource
)

sealed interface InstallOutcome {
    data class Installed(val detail: String) : InstallOutcome
    data class Failed(val reason: String) : InstallOutcome
}

/**
 * Writes a pinned profile into the device's WiFi configuration. Non-root:
 * [SuggestionProfileInstaller] (`WifiNetworkSuggestion`). A root/system
 * implementation would add the network to wpa_supplicant directly (see
 * docs/eduroam-decisions.md, D1); either way it only ever gets a password
 * from [EnterpriseProfileBuilder].
 */
interface EnterpriseProfileInstaller {
    suspend fun install(plan: ProfilePlan, password: String): InstallOutcome

    /** Removes what [install] added for [ssid]; a no-op if nothing was. */
    suspend fun remove(ssid: String)

    /** Whether the device is connected to [ssid] within [timeoutMs]. */
    suspend fun awaitConnected(ssid: String, timeoutMs: Long): Boolean
}
