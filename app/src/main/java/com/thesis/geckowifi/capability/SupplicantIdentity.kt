package com.thesis.geckowifi.capability

/** The network id of the current connection from `wpa_cli status` output (`id=3`), or null. */
fun parseCurrentNetworkId(status: String): Int? =
    status.lineSequence().firstNotNullOfOrNull { line ->
        line.trim().takeIf { it.startsWith("id=") }?.removePrefix("id=")?.toIntOrNull()
    }

/**
 * The realm (the part after `@`) of an identity as `wpa_cli get_network`
 * returns it (quoted, e.g. `"anonymous@ethz.ch"`). Null for `FAIL`, an unset
 * field, or an identity without a realm. Only the realm is kept: the user part
 * is never needed and never stored or logged.
 */
fun realmOfIdentity(value: String?): String? {
    val identity = value?.trim()?.removeSurrounding("\"") ?: return null
    if (identity.isEmpty() || identity == "FAIL" || '@' !in identity) return null
    return identity.substringAfterLast('@').trim().lowercase().ifEmpty { null }
}
