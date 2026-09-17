package com.thesis.geckowifi.network

/**
 * PROTOTYPE-ONLY fake network entries. See docs/app-design.md and
 * docs/prototype-decisions.md.
 *
 * Each pair (benign/evil-twin) shares the SAME [ScannedNetwork.ssid] - that
 * is the point: an evil twin is defined by presenting an identical visible
 * network identity while actually being something else. The pair differs
 * only in what it presents (domain/key or auth-server/fingerprint), one of
 * which matches what's registered on the corresponding demo GeoCertificate,
 * one of which doesn't. Requires the server to have `wifi.ssid` set to
 * "Cafe Free WiFi" / "eduroam" respectively on those certificates - see
 * docs/prototype-decisions.md for the insert payloads.
 */
object FakeDemoNetworks {

    val all: List<ScannedNetwork> = listOf(
        /* On server this is registered with:
        Certificate ID: campus-guest-portal-001
        Portal Domains: portal.wifi.example.edu, wifi.example.edu
        SSID: Campus Guest
        Coordinates: lat 47.377900, lon 8.548700, alt 400m
         */
        ScannedNetwork.FakeCaptivePortal(
            ssid = "Campus Guest",
            bssid = "DE:AD:BE:EF:00:01",
            label = "benign demo",
            presumedDomain = "portal.wifi.example.edu",
            presumedSpkiHashBase64 = "vBjsKk7VPT860WzRB5O7LdJh1KP2fX0S7uMyDQw+sD4="
        ),
        ScannedNetwork.FakeCaptivePortal(
            ssid = "Campus Guest",
            bssid = "DE:AD:BE:EF:00:02",
            label = "evil-twin demo",
            presumedDomain = "portal.wifi.example.edu",
            presumedSpkiHashBase64 = "IHZwfYs3i2BMXR3ah28XE5stNrPeUNkVqFmvmFEL7AY="
        ),
        /* On server this is registered with:
        Certificate ID: eduroam-ethz-campus-001
        SSID: eduroam
        Coordinates: lat 47.377900, lon 8.548700, alt 400m
         */
        ScannedNetwork.FakeEduroam(
            ssid = "eduroam",
            bssid = "DE:AD:BE:EF:00:03",
            label = "benign demo",
            presumedAuthServerName = "radius.ethz.ch",
            presumedCaFingerprintBase64 = "NLEWAnfdeAYUmQvPuzEdZA980jz6RORmyEERVADEFHA="
        ),
        ScannedNetwork.FakeEduroam(
            ssid = "eduroam",
            bssid = "DE:AD:BE:EF:00:04",
            label = "evil-twin demo",
            presumedAuthServerName = "radius.ethz.ch",
            presumedCaFingerprintBase64 = "3bLjgBuyR3omAtwLlxDWXTIFUwDJhKmGjhhdbxJ7WXY="
        )
    )
}
