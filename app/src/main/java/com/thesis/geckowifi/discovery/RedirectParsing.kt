package com.thesis.geckowifi.discovery

import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

/** Pure helpers for [RedirectProbeSource] (kept separate so they're unit-testable). */
object RedirectParsing {

    private val metaRefresh = Regex(
        """<meta[^>]*http-equiv\s*=\s*["']?refresh["']?[^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val refreshContent = Regex(
        """content\s*=\s*["']\s*\d*\s*;?\s*url\s*=\s*['"]?([^'">\s]+)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Target of an HTML `<meta http-equiv="refresh" content="0;url=...">`,
     * as used by some portals (and openNDS's 511 page) instead of a 3xx.
     * JavaScript redirects are not followed.
     */
    fun metaRefreshTarget(html: String): String? {
        val tag = metaRefresh.find(html)?.value ?: return null
        return refreshContent.find(tag)?.groupValues?.get(1)?.replace("&amp;", "&")
    }

    /** Resolves a (possibly relative) Location/refresh target against [base]; null if unusable. */
    fun resolve(base: String, target: String): String? = runCatching {
        val resolved = URI(base).resolve(URI(target.trim()))
        if (resolved.scheme?.lowercase() in setOf("http", "https") && !resolved.host.isNullOrEmpty()) {
            resolved.toString()
        } else null
    }.getOrNull()

    /** Same value as CertProbe.spkiHash / the portal server / the GeoCert pins. */
    fun spkiHash(certificate: X509Certificate): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        )
}
