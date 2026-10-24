# Sequence diagrams

The diagrams are
[Mermaid](https://mermaid.js.org/syntax/sequenceDiagram.html): 

```
mkdir docs/diagrams
npx -p @mermaid-js/mermaid-cli@11.4.2 mmdc -i docs/sequence-diagrams.md -o docs/diagrams/out.md -e pdf
```

This writes one PDF per diagram (`out-1.pdf`, ...) next to a copy of this
file. Use `-e svg` for SVG. 

Flows 1 to 7 are system level: devices and servers, as in the thesis. Flow 8
is code level: the same enterprise flow as classes, for working on the app.
When a flow changes in the code, change its diagram in the same commit.

## Conventions

## Components

| Participant | What it does | In the lab | In the code |
|---|---|---|---|
| User | Taps a network, approves Android's dialogs, enters accounts | | |
| GECKO app | Looks up registrations, verifies answers, decides, installs profiles | Galaxy Tab A11| `ui/`, `verification/`, `eduroam/` |
| Android Wi-Fi | Joins networks for the app, runs EAP (wpa_supplicant), shows approval dialogs, picks networks from suggestions | One UI 8.5 | `NetworkObserver`, `SpecifierOnboardingSession`, `SuggestionProfileInstaller` |
| Local store | Saved lookup (the "cached GeoCerts"), saved realm records, accounts (passwords encrypted with a Keystore key), verdicts | App storage | `RegistrationCache`, `AccountStore`, `VerdictStore` |
| Access point | Broadcasts the SSID. Open networks: captive portal (openNDS). Enterprise: 802.1X authenticator in front of RADIUS | Router A genuine, Router B evil twin | |
| Portal server | Login page of the captive portal (openNDS external FAS) | `192.168.137.10` genuine, `.20` attacker | `portal/` |
| SP RADIUS | The visited network's RADIUS server. Handles the GECKO identity itself (walled garden) and proxies everyone else by realm | FreeRADIUS `legit` in WSL, through `udp-relay.py` | `lab/eduroam/radius/` |
| Home RADIUS | The user's home institution's server (realm `homeuni.test`). Checks the password | Same FreeRADIUS as the SP (D7). In eduroam it sits behind federation proxies | |
| Map server | GECKO map: answers "what is registered here" with inclusion proofs and a signed map head | geopki + Trillian, `192.168.137.1:1234` | `GeckoClient`, key in `PinnedServerKey` |
| Realm-record server | Serves signed realm records: the login-server pins for a realm within a federation | `lab/realm`, `192.168.137.1:1235`, signed offline | `RealmRecordClient`, key in `PinnedRealmKey` |

## Flow 1: Registration lookup

It runs on its own, not when the user taps a network.

```mermaid
sequenceDiagram
    autonumber
    participant App as GECKO app
    participant Store as Local store
    participant Net as Current route<br/>(Wi-Fi AP or mobile data)
    participant Map as Map server

    Note over App: Runs at app start, every 10 min while the app is visible,<br/>and 10 s / 30 s after the device joins a Wi-Fi network (D26).<br/>Not while a check holds a network.
    App->>App: Location fix (last known, else request one)
    App->>App: Bind the map client: mobile data if any, else the device's Wi-Fi
    App->>Net: POST /v1/query?c (cells covering a 253 m circle, all altitudes)
    Net->>Map: forwarded
    alt Server reachable
        Map-->>App: Certificates + inclusion proofs + signed map head
        Note over App: Verify: map-head signature (pinned key), inclusion and<br/>consistency proofs, every referenced certificate delivered
        alt Proofs valid
            App->>App: Drop expired certificates and those whose area misses the device
            App->>Store: Save lookup: certificates, position, 253 m radius,<br/>time, route, map-head time
            App->>App: Home list shows what is registered here
        else Proof fails
            App->>App: Untrusted. Saved data never replaces a failed proof
        end
    else Unreachable (Wi-Fi-only device before joining, portal, blocked)
        Map--xApp: no answer
        App->>Store: Saved lookup at most 24 h old whose area contains this circle?
        Store-->>App: Saved certificates, if so
        App->>App: Home list shows the saved answer, marked "saved"
    end
```

## Flow 2: Open network with a captive portal

The user taps an open network (lab: `GeckoTest` on Router A). GECKO joins it
itself and checks the login page before the user logs in.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant App as GECKO app
    participant OS as Android Wi-Fi
    participant AP as Access point<br/>(openNDS)
    participant Portal as Portal server
    participant Map as Map server

    U->>App: Tap GeckoTest
    App->>OS: requestNetwork: SSID + BSSID from the scan, no INTERNET capability
    OS->>U: Approval dialog (non-root prototype)
    U->>OS: Allow
    OS->>AP: Associate (open)
    OS-->>App: onAvailable(network)
    App->>App: Forget cached verdicts and portal session for this BSSID (AC22)

    rect rgba(70, 130, 220, 0.12)
    Note over App,Map: Lookup over the joined network. openNDS lets the map server through before login
    App->>AP: POST /v1/query?c (accuracy radius)
    AP->>Map: forwarded
    Map-->>App: Certificates + proofs + signed map head
    Note over App: Verify and save, as in Flow 1
    end

    rect rgba(70, 130, 220, 0.12)
    Note over App,Portal: Portal discovery: plain GETs, any certificate accepted, every HTTPS key recorded
    App->>AP: GET http://connectivitycheck.gstatic.com/generate_204
    alt Portal in the way
        AP-->>App: 302 to http://portal.gecko-a.lab/?tok=...
        App->>Portal: GET http://portal.gecko-a.lab/?tok=...
        Portal-->>App: 302 to https://portal.gecko-a.lab/?tok=...
        App->>Portal: TLS handshake + GET https://portal.gecko-a.lab/?tok=...
        Portal-->>App: Server certificate (SPKI hash recorded) + 200 login page
        App->>Map: Judging query (if unreachable: the saved answer, D25)
        Map-->>App: Certificates + proofs
        Note over App: Verify. Judge every HTTPS hop: certificates for this SSID,<br/>valid and covering the device. A primary domain with its pinned key<br/>starts the session. Then delegates with their keys. Unknown domain or key = CONFLICT
    else 204: no portal (open network, or already logged in)
        App->>Portal: TLS handshake with the domain registered for the SSID
        Portal-->>App: Server certificate
        Note over App: Compare its key with the registration.<br/>Registered without a portal domain: compare advertised security only.<br/>Nothing registered: UNVERIFIED
    else Discovery failed (error, loop, more than 10 redirects)
        Note over App: Judge the hops seen so far: a wrong key is still CONFLICT,<br/>otherwise UNREACHABLE
    end
    end

    App->>App: Verdict to status card, History and check log
    App->>U: VERIFIED / UNRECOGNIZED / UNVERIFIED / UNREACHABLE,<br/>or a full-screen warning on CONFLICT
    Note over U,Portal: The user logs in through Android's sign-in page, not through GECKO. openNDS then lets the device through
```

On the evil twin (Router B, attacker portal `.20`), the chain ends at a domain
or key that the SSID's registration doesn't list. Due to SSID exclusivity,
the user sees the warning.

## Flow 3: Enterprise Network (fetch GeoCerts)

The user taps `eduroam-lab` and GECKO has no usable saved registration
there. Phase 1 joins with the public GECKO identity to fetch the
registration and the realm record. Phase 2 joins with the user's real
account, pinned to the verified login server.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant App as GECKO app
    participant Store as Local store
    participant OS as Android Wi-Fi
    participant AP as Access point
    participant SPR as SP RADIUS
    participant Home as Home RADIUS<br/>(homeuni.test)
    participant Map as Map server
    participant Realm as Realm-record server

    U->>App: Tap eduroam-lab
    App->>Store: Usable saved registration here? (24 h, area, enterprise certificate)
    Store-->>App: None
    Note over App: Onboarding allowed only on lab SSIDs (D15)

    rect rgba(70, 130, 220, 0.12)
    Note over App,Realm: Phase 1: onboarding session with the public GECKO identity. Nothing secret is sent
    App->>OS: requestNetwork: SSID + BSSID, PEAP-MSCHAPv2,<br/>gecko@gecko.sp.test / gecko, onboarding CA,<br/>server name gecko-onboard.sp.test, no INTERNET capability
    OS->>U: Approval dialog (non-root prototype, D1)
    U->>OS: Allow
    OS->>AP: Associate, EAPOL start
    AP->>SPR: RADIUS Access-Request, outer identity gecko@gecko.sp.test
    SPR-->>OS: PEAP TLS: certificate for gecko-onboard.sp.test
    Note over OS: Validates it against the onboarding CA. Required by Android's API,<br/>not a security property: an attacker may hold such a certificate (D8)
    OS->>SPR: Inner MSCHAPv2 with the public password "gecko"
    SPR-->>AP: Access-Accept, VLAN 99, Session-Timeout 60
    Note over AP: Walled garden: only the map and realm servers are reachable
    OS-->>App: onAvailable(onboarding network)

    App->>Map: POST /v1/query?c, bound to the onboarding network, no fallback
    Map-->>App: Certificates + proofs + signed map head
    Note over App: Verify the proofs. Certificates for this SSID, valid and covering the device.<br/>Compare advertised security: REGISTERED / CONFLICT / UNVERIFIED.<br/>Read the site: federation eduroam-lab, local realms (aaa)
    App->>Store: Saved accounts (identity and realm only)
    Store-->>App: alice@homeuni.test
    Note over App: Match accounts: realm listed in aaa = local (pins from the GeoCert),<br/>otherwise a visitor of the site's federation (pins from a realm record)

    par For each visitor account
        App->>Realm: GET /v1/realm/eduroam-lab/homeuni.test
        Realm-->>App: SignedRealmRecord
        Note over App: Verify: ECDSA P-256 against the pinned realm key, federation = site's,<br/>realm = account's, not expired. Gives the pins: EAP method, CA certificate,<br/>server names, outer identity
    end
    App->>OS: Release the onboarding network
    App->>Store: Save the fetched realm records (signed bytes)
    end

    Note over App: decide(): site CONFLICT or a forged record = block with a warning.<br/>No site = block. One account with pins = proceed.<br/>Several = ask (Flow 5). None = sign-in sheet (Flow 5)

    rect rgba(60, 170, 100, 0.14)
    Note over App,Home: Phase 2: real account. The password is released only with authenticated pins (D2)
    App->>Store: Load alice's password (Keystore)
    App->>OS: addNetworkSuggestions: PEAP-MSCHAPv2, identity alice@homeuni.test,<br/>outer anonymous@homeuni.test, pinned CA, server name radius.homeuni.test
    OS->>AP: Associate (Android's own network selection)
    AP->>SPR: RADIUS Access-Request, outer identity anonymous@homeuni.test
    SPR->>Home: Proxy by realm homeuni.test
    Home-->>OS: PEAP TLS: certificate for radius.homeuni.test
    Note over OS: Pinned CA and server name must match. Otherwise the handshake<br/>stops here and the inner exchange is never sent
    OS->>Home: Inner MSCHAPv2 as alice@homeuni.test, inside the TLS tunnel
    Home-->>SPR: Access-Accept
    SPR-->>AP: Access-Accept (normal VLAN)
    OS-->>App: Connected to eduroam-lab (waits up to 60 s)
    end

    App->>U: Connected with the pinned profile
    Note over App,Map: The device joined a network, so Flow 1 runs and saves the registration. Next time it will use saved registration (GeoCert is available)
    Note over OS,Home: EAP actually travels device to AP over EAPOL, then AP to RADIUS inside RADIUS. Drawn end to end for readability
```

## Flow 4: Enterprise Network (GeoCert available)

The user taps an enterprise network for which GECKO already holds a GeoCert (so we know its registered and have the expected certificate). 
There is no onboarding session and the GECKO identity is never sent. This also works where GECKO onboarding isn't supported, such as at
real eduroam.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant App as GECKO app
    participant Store as Local store
    participant OS as Android Wi-Fi
    participant AP as Access point
    participant SPR as SP RADIUS
    participant Home as Home RADIUS

    U->>App: Tap eduroam-lab (or eduroam)
    App->>Store: Usable saved registration here?
    Store-->>App: Saved certificates for the SSID + saved realm records
    Note over App: Compare advertised security with the saved certificates:<br/>REGISTERED, or CONFLICT on a mismatch. Read the site and match accounts
    alt Local account
        Note over App: Pins from the saved site certificate (aaa entry)
    else Visitor with a saved realm record
        Note over App: Verify the saved record again: signature, federation, realm,<br/>expiry. A tampered store gives nothing
    else Visitor without a saved record
        Note over App: Network supports onboarding: continue with Flow 3.<br/>Otherwise: sign-in sheet, or a block
    end
    Note over App: decide(), as in Flow 3

    rect rgba(60, 170, 100, 0.14)
    Note over App,Home: Phase 2, the same as in Flow 3
    App->>Store: Load the password
    App->>OS: addNetworkSuggestions: pinned CA + server name, real identity
    OS->>AP: Associate
    AP->>SPR: Access-Request, outer anonymous@realm
    SPR->>Home: Proxy by realm
    Home-->>OS: TLS certificate, checked against the pins
    OS->>Home: Inner exchange with the real identity
    Home-->>OS: Accept (through SPR and AP)
    OS-->>App: Connected
    end
    App->>U: Connected with the pinned profile

```

No map server and no realm server take part, and Android shows no approval
dialog.
