"""Writes synthetic GeoCertificates for the density experiment (stdlib only).

The same random businesses density_sim.py uses (10-50 m rectangles on random
floors, k certificates each, some with WiFi), as real certificates in geopki's
format, plus three large areas (city, district, neighbourhood). Every
certificate is invented: ids start with "synth-", domains end in ".example",
and lab/density/stack.sh refuses to load anything else.

WiFi certificates are made from the lab's real ones (lab/certs/), so their size
and how well they compress are real: each network copies one template's wifi
extension and gets its own SSID, operator, domains, realms and key hashes
(TEMPLATES says which, and how often). Embedded CA certificates stay as they
are, since geopki checks that they are certificates. Non-WiFi certificates have
no format yet: plausible JSON filled with random words, --other-bytes long.

    python lab/density/make_certs.py --place zurich-centre
    python lab/density/make_certs.py --place midtown --radius 100 --seed 2

Writes lab/density/out/<place>-r<radius>-s<seed>.json (git-ignored).
"""
import argparse
import base64
import copy
import json
import os
import random
import re
import string

import density_sim as sim

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
LAB_CERTS = os.path.join(HERE, "..", "certs")

# Which lab certificate a synthetic WiFi network copies, and how often. An
# assumption: password networks and captive portals are the common case,
# enterprise networks (eduroam and company networks) rarer, and only some of
# those embed their CA certificates.
TEMPLATES = [
    (None, 0.40),                   # password network (WPA2/WPA3-Personal), no portal
    ("mango-a", 0.20),              # captive portal, two pinned domains
    ("eth-visitors-2026a", 0.15),   # captive portal, two pinned domains
    ("demo-eduroam-ethz", 0.15),    # federation, realms, CA key hash
    ("eduroam-lab-sp", 0.05),       # federation without local users
    ("ethz-eduroam-2026a", 0.05),   # with two embedded CA certificates (~5 KB)
]
PASSWORD_NETWORK = {"version": 1, "operator": "", "ssid": "",
                    "auth_modes": ["wpa2-personal", "wpa3-personal"], "pmf": "optional"}

BASE64_HASH = re.compile(r"^[A-Za-z0-9+/]{43}=$")  # a base64 SHA-256 digest


def lab_wifi_extensions() -> dict:
    """The wifi extension of every lab certificate, by certificate id."""
    out = {}
    for name in sorted(os.listdir(LAB_CERTS)):
        if name.endswith(".json"):
            with open(os.path.join(LAB_CERTS, name), encoding="utf-8") as f:
                data = json.load(f)
            for cert in data if isinstance(data, list) else [data]:
                if "wifi" in cert.get("extensions", {}):
                    out[cert["certificate_id"]] = cert["extensions"]["wifi"]
    return out


def word(rng, n: int = 7) -> str:
    return "".join(rng.choice(string.ascii_lowercase) for _ in range(n))


def personalize(wifi: dict, n: int, rng) -> dict:
    """A copy of a template's wifi extension for network n: own SSID, operator,
    domains, realms, server names and key hashes. CA certificates stay."""
    wifi = copy.deepcopy(wifi)
    wifi["ssid"], wifi["operator"] = f"synth-{n}", f"Synthetic {word(rng, 5).title()} {n}"
    names = {}  # one replacement per original name, so a template's structure stays

    def rename(name: str) -> str:
        if name not in names:
            names[name] = f"{word(rng)}.synth-{n}.example"
        return names[name]

    def walk(v, key=""):
        if isinstance(v, dict):
            return {k: walk(x, k) for k, x in v.items()}
        if isinstance(v, list):
            return [walk(x, key) for x in v]
        if isinstance(v, str):
            if key == "ca_certificates":
                return v
            if BASE64_HASH.match(v):
                return base64.b64encode(rng.randbytes(32)).decode()
            if key == "realms":
                return ("*." if v.startswith("*.") else "") + f"synth-{n}.example"
            if key in ("name", "names"):
                return rename(v)
        return v

    return walk(wifi)


def other_extension(kind: str, n: int, size: int, rng) -> dict:
    """Plausible content for a certificate type that has no format yet."""
    body = {"name": f"{word(rng, 6).title()} {word(rng, 8).title()}", "website": f"https://synth-{n}.example",
            "address": f"{word(rng, 9).title()}strasse {rng.randint(1, 120)}", "description": ""}
    fill = size - len(json.dumps({kind: body}, separators=(",", ":"))) - 320  # ~ the core fields (id, domain, area, ...)
    body["description"] = " ".join(word(rng, rng.randint(3, 9)) for _ in range(max(fill, 0) // 7))[:max(fill, 0)]
    return body


def certificate(cert_id: str, n: int, area: dict, low: float, high: float, extensions: dict) -> dict:
    cert = {
        "certificate_id": cert_id,
        "domain": f"synth-{n}.example",
        "areas": [area],
        "areas_altitude": [[low, high]],
        "not_valid_after": "2027-12-31T00:00:00Z",
    }
    if "wifi" in extensions:
        cert["critical"] = ["wifi"]
    cert["extensions"] = extensions
    return cert


def rectangle_area(grid: sim.Grid, x: float, y: float, w: float, h: float) -> dict:
    """A w x h m rectangle centred x/y m from the grid's point, as a geopki area:
    [exterior rings, interior rings], counter-clockwise from the south-west corner."""
    (lng0, lat0), (lng1, lat1) = grid.to_degrees(x - w / 2, y - h / 2), grid.to_degrees(x + w / 2, y + h / 2)
    ring = [[lng0, lat0], [lng1, lat0], [lng1, lat1], [lng0, lat1], [lng0, lat0]]
    return {"type": "MultiPolygon", "coordinates": [[[[round(a, 7), round(b, 7)] for a, b in ring]], []]}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--place", choices=sorted(sim.PLACES), default="zurich-centre")
    ap.add_argument("--radius", type=int, default=100, help="largest lookup to measure, sets the area filled (100)")
    ap.add_argument("--k", type=int, help="certificates per business (default: per place)")
    ap.add_argument("--wifi-share", type=float, default=0.5)
    ap.add_argument("--other-bytes", type=int, default=sim.OTHER_BODY,
                    help=f"size of a non-WiFi certificate ({sim.OTHER_BODY}; they have no format yet)")
    ap.add_argument("--seed", type=int, default=1)
    args = ap.parse_args()

    place = sim.PLACES[args.place] if not args.k else sim.replace(sim.PLACES[args.place], k=args.k)
    grid = sim.Grid(place.lat, place.lng)
    rng = random.Random(args.seed)          # placement: the same businesses as density_sim.py
    content = random.Random(args.seed + 1)  # what the certificates say
    region = max(3 * args.radius, 150)
    lab = lab_wifi_extensions()
    names, weights = zip(*TEMPLATES)

    certs = []
    for n, (x, y, w, h, floor_m, types) in enumerate(sim.businesses(place, region, args.wifi_share, rng)):
        area = rectangle_area(grid, x, y, w, h)
        for kind in types:
            if kind == "wifi":
                template = content.choices(names, weights)[0]
                extensions = {"wifi": personalize(lab[template] if template else PASSWORD_NETWORK, n, content)}
            else:
                extensions = {kind: other_extension(kind, n, args.other_bytes, content)}
            certs.append(certificate(f"synth-{args.place}-{n}-{kind}", n, area, floor_m, floor_m + 3, extensions))
    # large areas on high tree nodes, all floors
    for i, side in enumerate((10_000, 2_000, 800)):
        n = 100_000 + i
        certs.append(certificate(f"synth-{args.place}-area-{side}", n, rectangle_area(grid, 0, 0, side, side),
                                 place.ground_m - 20, place.ground_m + 300,
                                 {"space": other_extension("space", n, args.other_bytes, content)}))

    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"{args.place}-r{args.radius}-s{args.seed}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"place": args.place, "lat": place.lat, "lng": place.lng, "radius": args.radius,
                   "seed": args.seed, "certificates": certs}, f, separators=(",", ":"))
    sizes = {"wifi": [], "other": []}
    for c in certs:
        sizes["wifi" if "wifi" in c["extensions"] else "other"].append(len(json.dumps(c, separators=(",", ":"))))
    print(f"{path}: {len(certs)} certificates, {os.path.getsize(path) / 1e6:.1f} MB")
    for k, v in sizes.items():
        print(f"  {k}: {len(v)}, average {sum(v) / len(v):,.0f} B, {min(v):,}-{max(v):,} B")


if __name__ == "__main__":
    main()
