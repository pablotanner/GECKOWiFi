package com.thesis.geckowifi.network

import com.thesis.geckowifi.verification.ObservedAuthServer

/**
 * PROTOTYPE-ONLY fake network entries. See docs/app-design.md and
 * docs/prototype-decisions.md.
 *
 * Each pair (benign/evil-twin) shares the SAME [ScannedNetwork.ssid] - that
 * is the point: an evil twin is defined by presenting an identical visible
 * network identity while actually being something else. The pair differs
 * only in what it presents (domain/key or auth-server/fingerprint), one of
 * which matches what's registered on the corresponding demo GeoCertificate,
 * one of which doesn't. The eduroam entries need lab/certs/demo-eduroam.json
 * registered on the map server.
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
        /* Registered as lab/certs/demo-eduroam.json (certificate demo-eduroam-ethz):
        SSID eduroam, federation eduroam, realm ethz.ch, server radius.ethz.ch
        pinned by its CA (NLEW...). The hashes are stand-ins, not real keys.
         */
        ScannedNetwork.FakeEduroam(
            ssid = "eduroam",
            bssid = "DE:AD:BE:EF:00:03",
            label = "benign demo",
            observed = ObservedAuthServer(
                leafSpkiSha256 = "PYyHJ31UFOUgCCIwfbX7gx7nJCYMbA92Yd0chphB/QU=",
                leafNames = listOf("radius.ethz.ch"),
                verifiedIssuerSpkiSha256 = listOf("NLEWAnfdeAYUmQvPuzEdZA980jz6RORmyEERVADEFHA=")
            ),
            userRealm = "ethz.ch"
        ),
        // Same server name, but a rogue CA: what an evil twin running its own RADIUS presents.
        ScannedNetwork.FakeEduroam(
            ssid = "eduroam",
            bssid = "DE:AD:BE:EF:00:04",
            label = "evil-twin demo",
            observed = ObservedAuthServer(
                leafSpkiSha256 = "GgXw4UeqY11haFUBbByLlNquoGek024xYcSOONoTNyU=",
                leafNames = listOf("radius.ethz.ch"),
                verifiedIssuerSpkiSha256 = listOf("3bLjgBuyR3omAtwLlxDWXTIFUwDJhKmGjhhdbxJ7WXY=")
            ),
            userRealm = "ethz.ch"
        ),
        // A visitor from another institution: EAP is proxied to their home server.
        ScannedNetwork.FakeEduroam(
            ssid = "eduroam",
            bssid = "DE:AD:BE:EF:00:05",
            label = "visitor demo",
            observed = ObservedAuthServer(
                leafSpkiSha256 = "zH9wM60q97fklY85wVqATCbvHMMaQtOlopaKcF4vaKo=",
                leafNames = listOf("radius.uzh.ch"),
                verifiedIssuerSpkiSha256 = listOf("O0O9rh+ZfRTqUTNFVyyeI9eWmI75Wp9ZMQo0l9PZCSY=")
            ),
            userRealm = "uzh.ch"
        )
    )
}
