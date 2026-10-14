# GeoCertificate schema (v3)

The JSON format of a GeoCertificate as stored in the geopki map server and
read by the app. The Go structs live in geopki's `pkg/crypto/certificate.go`,
the Kotlin model in `app/.../data/model/GeoCertificate.kt`. Both must follow
this document.

## Rules that keep the schema stable

1. **Unknown fields are ignored** by the client, unless named in `critical`.
2. **`critical`** lists features a certificate can't be judged without. A
   client that doesn't implement one treats the certificate as unevaluable:
   never `VERIFIED`, never `CONFLICT`. If it was the SSID's only certificate
   the result is a silent `UNVERIFIED`; if other certificates exist, a
   `CONFLICT` from them is downgraded to `UNVERIFIED`. Mark a feature critical
   when ignoring it would make an older client wrong, not just less strict.
3. **Unknown enum values never match.** An unknown auth mode or portal role is
   parsed (the certificate is not dropped) and treated as never equal to
   anything observed. Worst case a false positive, never a false `VERIFIED`.
4. **Booleans default to the safe value**, which is also Go's zero value: a
   missing field never weakens a check (`ssid_shared`, not `ssid_exclusive`).
5. **The hash algorithm is in the field name** (`*_sha256`). A new algorithm is
   a new sibling field. An entry whose only pins are in unknown algorithms has
   no pins and never verifies.
6. **New network types are new optional blocks** next to `enterprise`
   (Passpoint/OpenRoaming, SAE-PK). Additive, no version bump.

The server is strict, the client lenient: the server should reject unknown
keys, duplicate keys and keys in the wrong case on insert. Go's
`encoding/json` matches keys case-insensitively and keeps the last duplicate;
kotlinx.serialization does neither, and the certificate hash covers the raw
bytes, so both sides must read the same bytes the same way.

## Example (eduroam)

```json
{
  "schema_version": 3,
  "certificate_id": "ethz-zentrum-eduroam-2026a",
  "subject": { "id": "ethz.ch", "name": "ETH Zürich" },
  "critical": [],
  "wifi": {
    "ssid": "eduroam",
    "auth_modes": ["wpa2-enterprise", "wpa3-enterprise"],
    "pmf": "optional",
    "enterprise": {
      "federation": "eduroam",
      "aaa": [{
        "realms": ["ethz.ch", "*.ethz.ch"],
        "eap_methods": [{ "method": "peap", "phase2": ["mschapv2"] }],
        "servers": [{
          "names": ["radius.ethz.ch"],
          "ca_spki_sha256": ["<base64>"]
        }]
      }]
    }
  },
  "areas": [{ "type": "MultiPolygon", "coordinates": [[[[8.515,47.360], ...]], []] }],
  "areas_altitude": [[200, 700]],
  "not_valid_before": "2026-11-01T00:00:00Z",
  "not_valid_after": "2027-11-01T00:00:00Z"
}
```

Lab examples: [`lab/certs/mango-a.json`](../lab/certs/mango-a.json) (captive
portal), [`lab/certs/demo-eduroam.json`](../lab/certs/demo-eduroam.json) (stand-in
hashes for the demo entries), [`lab/certs/ethz-eduroam.json`](../lab/certs/ethz-eduroam.json)
(ETH's real eduroam, from its CAT profile, with `ca_certificates`).

## Fields

### Top level

| Field | Required | Meaning |
|---|---|---|
| `schema_version` | yes | `3` |
| `certificate_id` | yes | Unique per issuance |
| `subject.id` | yes | Registrant the authority bound this claim to (e.g. a verified domain). Certificates with the same id belong to one operator |
| `subject.name` | no | Display only, never matched |
| `critical` | no | See rule 2. No critical features are defined yet |
| `wifi` | yes | The network, below |
| `portal` | no | Captive-portal domains, unchanged from v2 (below) |
| `areas`, `areas_altitude` | yes | Unchanged from upstream geopki, see *Areas* |
| `not_valid_before` | no | RFC 3339, UTC with `Z`. Absent = valid from issuance |
| `not_valid_after` | yes | RFC 3339, UTC with `Z` |

### `wifi`

| Field | Meaning |
|---|---|
| `ssid` | Required. Compared case-insensitively by the client; the server's overlap check must use the same rule |
| `ssid_shared` | Default `false`: the SSID is exclusive here, a non-matching network on it is a `CONFLICT`. `true` for a generic SSID shared by several operators in one area |
| `auth_modes` | Every mode the network advertises: `open`, `owe`, `wpa2-personal`, `wpa3-personal`, `wpa2-enterprise`, `wpa3-enterprise`, `wpa3-enterprise-192`. A transition-mode network lists all of its modes |
| `pmf` | `optional` / `required`; absent = unspecified. Parsed, not yet compared |
| `enterprise` | Required for the enterprise modes, absent otherwise |

### `wifi.enterprise`

| Field | Meaning |
|---|---|
| `federation` | `eduroam`, `govroam`, ...; absent = a closed network where every user sees `aaa` |
| `aaa` | List of realm groups. May be empty only in a federation (a site without local users: everyone is a visitor) |
| `aaa[].realms` | NAI realms of local users. `ethz.ch` matches exactly, `*.ethz.ch` subrealms only; case-insensitive. Required in a federation |
| `aaa[].eap_methods` | `{method, phase2[]}`: `eap-tls`, `peap`, `ttls`; `mschapv2`, `pap`, `gtc`, ... Used for provisioning |
| `aaa[].servers[].names` | SAN dNSName (CN if none) of the RADIUS server certificate |
| `aaa[].servers[].pinned_spki_sha256` | SHA-256 of the leaf's SPKI, base64 |
| `aaa[].servers[].ca_spki_sha256` | SHA-256 of a CA's SPKI, base64. Matched against the chain the server sends, or against `ca_certificates` |
| `aaa[].servers[].ca_certificates` | Optional: the CA certificates themselves, base64 DER (as in an eduroam CAT profile). Only those whose key is in `ca_spki_sha256` are used. They let the client check a chain whose root the server doesn't send, and let Android's own supplicant pin the CA when the app provisions the network, without root |

### `portal` (unchanged from v2)

`domains[]` of `{name, role, pinned_spki_sha256[], certificate_sha256[]}`,
`role` one of `primary` (entry point), `delegate` (only after the primary),
`api` (RFC 8908 API host). The pins are the trust anchor; the client does no
Web PKI validation, and a domain without pins never verifies.

### Areas

Kept as in upstream geopki, which means `coordinates` is **not** GeoJSON's
MultiPolygon despite `"type": "MultiPolygon"`: it is `[exteriorRings,
interiorRings]`. Each exterior ring is a polygon (counter-clockwise, `[lng,
lat]`, closed); interior rings are holes. The server ignores holes when
indexing; the client honours them. Altitudes are metres; the reference (WGS84
ellipsoid vs. sea level, about 48 m apart in Zürich) is not defined yet, which
is harmless while the app queries the full altitude range.

## How the client judges a network

1. Keep certificates that are inside their validity window, whose area reaches
   the device, and whose SSID matches. None left: `UNVERIFIED`.
2. Set aside certificates with an unknown critical feature (rule 2).
3. **Auth mode** (scenario A1): every mode the access point advertises must be
   in `auth_modes` of at least one certificate; otherwise the network is not
   what was registered → `CONFLICT`. Certificates without `auth_modes` (v2)
   are skipped. Works from the scan alone, no root.
4. **Captive portal**: hop chain judged as in [portal-detection.md](portal-detection.md).
5. **Enterprise**: the server seen in the EAP handshake is accepted by an
   `AuthServer` if its leaf key is pinned, or if a CA in its chain is pinned,
   that CA's signature over the chain was verified by the client, and the leaf
   carries one of `names`. Which servers apply depends on the user's realm:
   - realm listed in an `aaa` entry: local user; that entry's servers, else `CONFLICT`;
   - federation, realm not listed: visitor. The home server can't be registered
     here, so `REGISTERED` (the site and auth mode still count);
   - federation, realm unknown: a match verifies, no match is `REGISTERED`;
   - closed network: all servers, whatever the realm.
   A secured network checked from its scan alone (not joined) stops after
   step 3: `REGISTERED` if the modes match, never `VERIFIED`.
6. **SSID exclusivity**: an `UNRECOGNIZED` result on a registered SSID becomes
   `CONFLICT`, unless a certificate sets `ssid_shared`.

Pins from all valid certificates for the SSID count together, so an operator
rotates a key by registering a new certificate with `not_valid_before` ahead of
the switch and letting the old one expire.

## Server-side requirements (geopki)

Not enforced by the client, assumed by it:

- Reject on insert: unknown/duplicate/miscased keys; enterprise modes without
  an `enterprise` block; a server entry without pins; `ca_spki_sha256` without
  `names`; a federation `aaa` entry without `realms`; a portal domain without
  pins; missing areas or `areas_altitude` not matching `areas`; hashes that
  aren't 32 bytes of base64; timestamps without `Z`.
- Refuse a certificate whose `(SSID, area)` overlaps one of a **different**
  `subject.id` (exclusivity), but allow it for the same subject (rotation).
  Compare SSIDs case-insensitively, as the client does.

## Not in the schema, deliberately

- **BSSIDs**: spoofable, and they break deployments with many access points.
- **Several SSIDs per certificate**: one network per certificate keeps
  revocation and the overlap rule simple. An operator holds several.
