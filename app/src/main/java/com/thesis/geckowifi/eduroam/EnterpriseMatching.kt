package com.thesis.geckowifi.eduroam

import com.thesis.geckowifi.data.model.GeoCertificate
import com.thesis.geckowifi.data.model.VerificationResult
import com.thesis.geckowifi.data.model.VerificationState

/**
 * What a verified site registration says about who may log in
 * (docs/eduroam-decisions.md, D19): the [federation] it takes part in (null =
 * closed network, only its own accounts), its own users' realms, and the
 * operator's name for the sign-in wording. Built only from certificates of a
 * verified map answer ([com.thesis.geckowifi.verification.SiteCheck]).
 */
class EnterpriseSite private constructor(
    val ssid: String,
    val federation: String?,
    /** Realm patterns of the operator's own users (`aaa[].realms`), e.g. `ethz.ch`, `*.ethz.ch`. */
    val localRealms: List<String>,
    /** Display name of the operator (`subject.name`), if all registrations agree on one. */
    val operatorName: String?,
    private val certificates: List<GeoCertificate>
) {
    fun isLocal(realm: String): Boolean =
        certificates.any { cert -> cert.wifi.enterprise?.aaa.orEmpty().any { it.coversRealm(realm) } }

    /** Pins for a local [realm] from the registration itself; the first certificate that has usable ones. */
    fun localPins(realm: String): PinResult {
        val results = certificates.filter { cert -> cert.wifi.enterprise?.aaa.orEmpty().any { it.coversRealm(realm) } }
            .map { AuthenticatedEapPins.fromSiteCertificate(it, realm) }
        return results.firstOrNull { it is PinResult.Ok } ?: results.firstOrNull()
            ?: PinResult.Rejected("the registration lists no servers for $realm")
    }

    companion object {
        /** Null if none of [certificates] registers an enterprise network. */
        fun from(ssid: String, certificates: List<GeoCertificate>): EnterpriseSite? {
            val enterprise = certificates.filter { it.wifi.enterprise != null }
            if (enterprise.isEmpty()) return null
            return EnterpriseSite(
                ssid = ssid,
                federation = enterprise.firstNotNullOfOrNull { it.wifi.enterprise?.federation?.takeIf(String::isNotBlank) }?.lowercase(),
                localRealms = enterprise.flatMap { it.wifi.enterprise!!.aaa.flatMap { a -> a.realms } }.distinct(),
                operatorName = enterprise.mapNotNull { it.subject?.name }.distinct().singleOrNull(),
                certificates = enterprise
            )
        }
    }
}

/** A saved account that fits a site, and why. */
sealed interface AccountMatch {
    val account: AccountInfo

    /** The site's registration lists the account's realm: pins from the registration. */
    data class Local(override val account: AccountInfo) : AccountMatch

    /** The site takes part in [federation]: pins from the realm record (federation, realm). */
    data class Visitor(override val account: AccountInfo, val federation: String) : AccountMatch
}

/**
 * Which saved accounts can log in at [site] (D19). An account the site's
 * registration lists is local, whether or not the site is in a federation;
 * otherwise it is a visitor if the site names a federation; otherwise (closed
 * network, foreign realm) it doesn't fit. Accounts without a realm never fit.
 */
fun matchAccounts(site: EnterpriseSite, accounts: List<AccountInfo>): List<AccountMatch> = accounts.mapNotNull { account ->
    val realm = account.realm ?: return@mapNotNull null
    when {
        site.isLocal(realm) -> AccountMatch.Local(account)
        site.federation != null -> AccountMatch.Visitor(account, site.federation)
        else -> null
    }
}

/** What the onboarding session produced for one matching account. */
sealed interface PinCheck {
    val match: AccountMatch

    data class Pinned(override val match: AccountMatch, val pins: AuthenticatedEapPins) : PinCheck

    /**
     * Pins arrived but must not be used. [hostile]: a record that fails its
     * signature or names another federation/realm is evidence of tampering; a
     * registration without CA certificates is only incomplete.
     */
    data class Rejected(override val match: AccountMatch, val reason: String, val hostile: Boolean) : PinCheck

    /** Nothing usable arrived; [cause] says whether trying again could help. */
    data class Missing(override val match: AccountMatch, val reason: String, val cause: MissingCause) : PinCheck
}

enum class MissingCause {
    /** The record server answered: the home institution has no record in this federation. */
    NO_RECORD,
    /** Saved-data path (D21): no saved record, and nothing was fetched. */
    NOT_SAVED,
    /** The record server couldn't be reached: a connection problem, worth retrying. */
    UNREACHABLE
}

/** A saved account that can't sign in at this network, and why (for the sign-in sheet). */
data class UnusableAccount(val account: AccountInfo, val why: Why) {
    enum class Why {
        /** The network doesn't accept the account's realm (closed network, foreign realm). */
        NOT_ACCEPTED,
        /** The home institution hasn't registered its login server for the site's federation. */
        NO_RECORD,
        /** GECKO has no saved login-server details and can't fetch them on this network. */
        NOT_SAVED,
        /** The site's registration lacks what Android needs to pin the server (e.g. CA certificates). */
        INCOMPLETE_REGISTRATION
    }
}

/** Wording for the sign-in sheet, taken from the registration (never "eduroam" by default). */
data class SignInPrompt(
    val ssid: String,
    val federation: String?,
    val operatorName: String?,
    val localRealms: List<String>,
    /** Saved accounts that can't be used here, with the reason, so the sheet can say so. */
    val unusable: List<UnusableAccount>
)

sealed interface Decision {
    data class Proceed(val account: AccountInfo, val pins: AuthenticatedEapPins, val why: String) : Decision

    /** Several accounts have verified pins: the user picks one (remembered per network). */
    data class ChooseAccount(val options: List<PinCheck.Pinned>) : Decision

    /**
     * No saved account can be used here (none fits, or none has confirmed
     * login-server details): a question for the user, not a check result.
     */
    data class NeedAccount(val prompt: SignInPrompt) : Decision

    /** [hostile]: something contradicted GECKO (a warning), rather than GECKO just not knowing. */
    data class Block(val reason: String, val hostile: Boolean) : Decision
}

/**
 * The gate (docs/eduroam-decisions.md, D2, D19): an account's password goes
 * out only with pins from an authenticated source for that account's realm and
 * the site's federation, never to a network that contradicts its registration,
 * and never when any record shows signs of tampering.
 *
 * [siteResult]/[site] come from the verified site query, [checks] from the
 * onboarding session (one per matching account), [preferredAccountId] from an
 * earlier choice on this network.
 */
fun decide(
    siteResult: VerificationResult?,
    site: EnterpriseSite?,
    checks: List<PinCheck>,
    accounts: List<AccountInfo>,
    preferredAccountId: String?
): Decision {
    if (siteResult?.state == VerificationState.CONFLICT) {
        return Decision.Block("this network contradicts its GECKO registration: ${siteResult.reason}", hostile = true)
    }
    if (site == null) {
        return Decision.Block(
            if (siteResult?.state == VerificationState.UNREACHABLE)
                "GECKO wasn't reachable over this network (${siteResult.reason}), so it couldn't confirm any login server"
            else "this network isn't registered with GECKO as an enterprise network here, so GECKO can't tell which accounts it accepts",
            hostile = false
        )
    }
    checks.filterIsInstance<PinCheck.Rejected>().firstOrNull { it.hostile }?.let {
        return Decision.Block("the login server details for ${it.match.account.identity} were rejected: ${it.reason}", hostile = true)
    }
    val pinned = checks.filterIsInstance<PinCheck.Pinned>()
    when {
        pinned.size == 1 -> return pinned.single().let { Decision.Proceed(it.match.account, it.pins, why(it)) }
        pinned.size > 1 -> {
            pinned.firstOrNull { it.match.account.id == preferredAccountId }?.let { return Decision.Proceed(it.match.account, it.pins, why(it)) }
            return Decision.ChooseAccount(pinned)
        }
    }
    // A connection problem: trying again may help, so it is a (neutral) result.
    checks.filterIsInstance<PinCheck.Missing>().filter { it.cause == MissingCause.UNREACHABLE }.takeIf { it.isNotEmpty() }?.let { missing ->
        return Decision.Block(
            "GECKO couldn't confirm the login server (" + missing.joinToString("; ") { "${it.match.account.identity}: ${it.reason}" } + ")",
            hostile = false
        )
    }
    // Otherwise nothing will change by retrying: the user needs another account.
    val unusable = accounts.map { account ->
        val why = when (val check = checks.firstOrNull { it.match.account.id == account.id }) {
            null, is PinCheck.Pinned -> UnusableAccount.Why.NOT_ACCEPTED
            is PinCheck.Rejected -> UnusableAccount.Why.INCOMPLETE_REGISTRATION
            is PinCheck.Missing -> if (check.cause == MissingCause.NO_RECORD) UnusableAccount.Why.NO_RECORD else UnusableAccount.Why.NOT_SAVED
        }
        UnusableAccount(account, why)
    }
    return Decision.NeedAccount(SignInPrompt(site.ssid, site.federation, site.operatorName, site.localRealms, unusable))
}

private fun why(check: PinCheck.Pinned): String = when (val source = check.pins.source) {
    is PinSource.SiteCertificate -> "the network's registration (${source.certificateId}) lists the server for ${check.pins.realm}"
    is PinSource.RealmRecord -> "signed record of ${source.displayName ?: check.pins.realm} in ${check.pins.federation} verified"
}
