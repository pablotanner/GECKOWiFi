package com.thesis.geckowifi.network

import com.thesis.geckowifi.data.model.WiFiAuthMode

/**
 * What one BSS claims about its security in its beacon / probe response: the
 * RSN element (EID 48), the WPA1 vendor element (221, 00:50:F2 type 1) and the
 * OWE transition element (221, 50:6F:9A type 0x1C). These claims are
 * unauthenticated (trust level 1 of docs/design-decisions.md, section 6): the
 * GeoCert is the signed expectation they are compared against, and only the
 * state negotiated after association is authenticated.
 *
 * Suite selectors under 00-0F-AC (RSN) and 00-50-F2 (WPA1) are stored as their
 * type number; another vendor's (DPP, OSEN, ...) as the full 32-bit selector,
 * so it never collides with a known number.
 */
data class RsnProfile(
    val akms: Set<Int> = emptySet(),
    val pairwise: Set<Int> = emptySet(),
    val group: Int? = null,
    val groupMgmt: Int? = null,
    /** Management frame protection capable / required (RSN capabilities bits 7 / 6). */
    val mfpc: Boolean = false,
    val mfpr: Boolean = false,
    val wpa1: Boolean = false,
    /** Privacy without an RSN or WPA element. */
    val wep: Boolean = false,
    /** Open BSS advertising its hidden OWE twin. */
    val oweTransition: Boolean = false
) {
    /** Anything to authenticate with, even if this app doesn't know the AKM. */
    val secured: Boolean get() = akms.isNotEmpty() || wep

    companion object {
        /**
         * From the raw elements of a scan result (`ScanResult.getInformationElements()`,
         * API 30+) as (element id, body). [wep] comes from the capability string:
         * the privacy bit isn't an element.
         */
        fun fromIes(ies: List<Pair<Int, ByteArray>>, wep: Boolean = false): RsnProfile {
            var rsn: Suites? = null
            var wpa: Suites? = null
            var oweTransition = false
            for ((id, body) in ies) when {
                id == EID_RSN -> rsn = parseSuites(body, offset = 0, defaultCipher = Cipher.CCMP, hasCapabilities = true)
                id == EID_VENDOR && body.startsWith(WPA1_OUI_TYPE) ->
                    wpa = parseSuites(body, offset = WPA1_OUI_TYPE.size, defaultCipher = Cipher.TKIP, hasCapabilities = false)
                id == EID_VENDOR && body.startsWith(OWE_TRANSITION_OUI_TYPE) -> oweTransition = true
            }
            val caps = rsn?.capabilities ?: 0
            return RsnProfile(
                akms = (rsn?.akms.orEmpty() + wpa?.akms.orEmpty()).toSet(),
                pairwise = (rsn?.pairwise.orEmpty() + wpa?.pairwise.orEmpty()).toSet(),
                group = (rsn ?: wpa)?.group,
                groupMgmt = rsn?.groupMgmt,
                mfpc = caps and 0x80 != 0,
                mfpr = caps and 0x40 != 0,
                wpa1 = wpa != null,
                wep = wep && rsn == null && wpa == null,
                oweTransition = oweTransition
            )
        }

        /**
         * Fallback when there are no elements (API 29, the demo's fake networks):
         * the capability string Android builds from them, e.g.
         * `[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][MFPC][ESS]`. Names as in AOSP
         * `InformationElementUtil.Capabilities` (API 31+). It carries no group
         * ciphers.
         */
        fun fromCapabilities(capabilities: String): RsnProfile {
            val elements = Regex("""\[([^\]]*)]""").findAll(capabilities).map { it.groupValues[1] }.toList()
            val akms = mutableSetOf<Int>()
            val pairwise = mutableSetOf<Int>()
            var wpa1 = false
            for (element in elements) {
                val protocol = element.substringBefore('-')
                if ('-' !in element || protocol !in setOf("WPA", "WPA2", "RSN", "OSEN", "WAPI")) continue
                if (protocol == "WPA") wpa1 = true
                if (protocol == "OSEN" || protocol == "WAPI") { akms += UNKNOWN_AKM; continue }
                val (names, ciphers) = splitAkmNames(element.substringAfter('-'))
                names.forEach { akms += AKM_NAMES[it] ?: UNKNOWN_AKM }
                ciphers.split('+').mapNotNullTo(pairwise) { CIPHER_NAMES[it] }
            }
            return RsnProfile(
                akms = akms - OWE_TRANSITION,
                pairwise = pairwise,
                mfpc = "MFPC" in elements,
                mfpr = "MFPR" in elements,
                wpa1 = wpa1,
                wep = "WEP" in elements,
                oweTransition = OWE_TRANSITION in akms
            )
        }
    }
}

/**
 * Every mode a client could end up in with this BSS, after the WFA WPA3
 * definitions. A set, because a transition BSS admits several. AKMs this app
 * doesn't know (FILS, DPP, OSEN, ...) add nothing: they're never misreported as
 * another mode, and a BSS with only those yields an empty set (not open).
 */
fun RsnProfile.admissibleModes(): Set<WiFiAuthMode> = buildSet {
    if (wep) add(WiFiAuthMode.WEP)
    if (!secured) {
        add(WiFiAuthMode.OPEN)
        if (oweTransition) add(WiFiAuthMode.OWE)
    }
    if (akms.any { it in Akm.ANY_SUITE_B_192 } && Cipher.GCMP_256 in pairwise && mfpr) add(WiFiAuthMode.WPA3_ENTERPRISE_192)
    if (akms.any { it in Akm.ANY_EAP }) when {
        mfpr -> add(WiFiAuthMode.WPA3_ENTERPRISE)
        Akm.EAP_SHA1 in akms && Akm.EAP_SHA256 in akms && mfpc -> {
            add(WiFiAuthMode.WPA2_ENTERPRISE); add(WiFiAuthMode.WPA3_ENTERPRISE)
        }
        else -> add(WiFiAuthMode.WPA2_ENTERPRISE)
    }
    // SAE needs PMF; PSK stays crackable offline with or without it.
    if (akms.any { it in Akm.ANY_SAE } && mfpc) add(WiFiAuthMode.WPA3_PERSONAL)
    if (akms.any { it in Akm.ANY_PSK }) add(WiFiAuthMode.WPA2_PERSONAL)
    if (Akm.OWE in akms) add(WiFiAuthMode.OWE)
}

/** The weakest mode a client can be held to (enum order: weaker first within each kind of network). */
val Set<WiFiAuthMode>.floor: WiFiAuthMode? get() = minOrNull()

/** The strongest mode of the set that is also in [supported] (what this device / flow can join). */
fun Set<WiFiAuthMode>.ceiling(supported: Set<WiFiAuthMode> = WiFiAuthMode.entries.toSet()): WiFiAuthMode? =
    filter { it in supported }.maxOrNull()

// --- suite numbers (IEEE 802.11-2020, 9.4.2.24) ---

object Akm {
    const val EAP_SHA1 = 1
    const val PSK_SHA1 = 2
    const val FT_EAP = 3
    const val FT_PSK = 4
    const val EAP_SHA256 = 5
    const val PSK_SHA256 = 6
    const val SAE = 8
    const val FT_SAE = 9
    const val EAP_SUITE_B_192 = 12
    const val FT_EAP_SHA384 = 13
    const val OWE = 18
    const val SAE_EXT_KEY = 24
    const val FT_SAE_EXT_KEY = 25

    val ANY_EAP = setOf(EAP_SHA1, FT_EAP, EAP_SHA256)
    val ANY_SUITE_B_192 = setOf(EAP_SUITE_B_192, FT_EAP_SHA384)
    val ANY_SAE = setOf(SAE, FT_SAE, SAE_EXT_KEY, FT_SAE_EXT_KEY)
    val ANY_PSK = setOf(PSK_SHA1, FT_PSK, PSK_SHA256)
}

object Cipher {
    const val TKIP = 2
    const val CCMP = 4
    const val GCMP_128 = 8
    const val GCMP_256 = 9
    const val CCMP_256 = 10
}

private const val EID_RSN = 48
private const val EID_VENDOR = 221
private val WPA1_OUI_TYPE = byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x01)
private val OWE_TRANSITION_OUI_TYPE = byteArrayOf(0x50, 0x6F, 0x9A.toByte(), 0x1C)

/** Capability-string AKMs that aren't a mode. */
private const val UNKNOWN_AKM = -1
private const val OWE_TRANSITION = -2

private val AKM_NAMES = mapOf(
    "EAP" to Akm.EAP_SHA1, "EAP/SHA1" to Akm.EAP_SHA1, "PSK" to Akm.PSK_SHA1, "FT/EAP" to Akm.FT_EAP,
    "FT/PSK" to Akm.FT_PSK, "EAP/SHA256" to Akm.EAP_SHA256, "PSK-SHA256" to Akm.PSK_SHA256, "SAE" to Akm.SAE,
    "FT/SAE" to Akm.FT_SAE, "EAP_SUITE_B_192" to Akm.EAP_SUITE_B_192, "EAP-FT-SHA384" to Akm.FT_EAP_SHA384,
    "OWE" to Akm.OWE, "SAE_EXT_KEY" to Akm.SAE_EXT_KEY, "FT/SAE_EXT_KEY" to Akm.FT_SAE_EXT_KEY,
    "OWE_TRANSITION" to OWE_TRANSITION,
    // Known names that contain '-', listed so they aren't split; no mode.
    "EAP-FILS-SHA256" to UNKNOWN_AKM, "EAP-FILS-SHA384" to UNKNOWN_AKM, "FT/PSK-SHA384" to UNKNOWN_AKM
)

private val CIPHER_NAMES = mapOf(
    "TKIP" to Cipher.TKIP, "CCMP" to Cipher.CCMP, "GCMP-128" to Cipher.GCMP_128,
    "GCMP-256" to Cipher.GCMP_256, "CCMP-256" to Cipher.CCMP_256
)

/**
 * `PSK+SAE-CCMP` → ([PSK, SAE], `CCMP`). Names may contain '-' themselves
 * (`PSK-SHA256`), so the longest known name wins; an unknown one runs to the next separator.
 */
private fun splitAkmNames(rest: String): Pair<List<String>, String> {
    val names = mutableListOf<String>()
    var s = rest
    while (true) {
        val name = AKM_NAMES.keys
            .filter { s.startsWith(it) && (s.length == it.length || s[it.length] == '+' || s[it.length] == '-') }
            .maxByOrNull { it.length }
            ?: s.takeWhile { it != '+' && it != '-' }
        names += name
        s = s.removePrefix(name)
        if (!s.startsWith("+")) return names to s.removePrefix("-")
        s = s.drop(1)
    }
}

private class Suites(val group: Int, val pairwise: List<Int>, val akms: List<Int>, val capabilities: Int, val groupMgmt: Int?)

/**
 * Version, group cipher, pairwise list, AKM list, then for RSN capabilities,
 * PMKIDs and the group management cipher. Every field after the version may be
 * cut off; missing ones take the standard's defaults. Null if even the version is missing.
 */
private fun parseSuites(body: ByteArray, offset: Int, defaultCipher: Int, hasCapabilities: Boolean): Suites? {
    val r = Reader(body, offset)
    r.u16() ?: return null
    val group = r.suite() ?: defaultCipher
    val pairwise = r.suites() ?: listOf(defaultCipher)
    val akms = r.suites() ?: listOf(Akm.EAP_SHA1)
    if (!hasCapabilities) return Suites(group, pairwise, akms, 0, null)
    val capabilities = r.u16() ?: 0
    r.u16()?.let { r.skip(16 * it) }
    return Suites(group, pairwise, akms, capabilities, r.suite())
}

/** Little-endian reader; once a read runs past the end, every later read returns null. */
private class Reader(private val b: ByteArray, private var pos: Int) {
    private fun take(n: Int): Int? = if (pos + n <= b.size) pos.also { pos += n } else null.also { pos = b.size + 1 }
    private fun u8(i: Int) = b[i].toInt() and 0xFF

    fun u16(): Int? = take(2)?.let { u8(it) or (u8(it + 1) shl 8) }
    fun skip(n: Int) { take(n) }

    fun suite(): Int? = take(4)?.let {
        val oui = (u8(it) shl 16) or (u8(it + 1) shl 8) or u8(it + 2)
        if (oui == 0x000FAC || oui == 0x0050F2) u8(it + 3) else (oui shl 8) or u8(it + 3)
    }

    fun suites(): List<Int>? {
        val n = u16() ?: return null
        return List(n) { suite() ?: return null }
    }
}

private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
