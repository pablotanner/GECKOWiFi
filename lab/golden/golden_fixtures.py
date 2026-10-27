"""Certificates and query points for the golden vectors (see regen.sh).

The six certificates and 18 points of geopki's former regen-golden.sh, with
types: WiFi, shop, lease, and one certificate with both WiFi and payment, so the
vectors cover type filtering (map version 2). Plus one certificate that expired
long ago: the release removes it, which exercises the expiry of entries. Ids
start with "synth-" so lab/density/stack.sh accepts them.

    python3 lab/golden/golden_fixtures.py <out dir>   -> certs.json, points.json
"""
import json
import math
import sys


def square(lon, lat, side_m):
    """Closed CCW square ring centred on (lon, lat), [lon, lat] order."""
    h = side_m / 2
    dlat = h / 111_320
    dlon = h / (111_320 * math.cos(math.radians(lat)))
    w, e, s, n = lon - dlon, lon + dlon, lat - dlat, lat + dlat
    return [[w, s], [e, s], [e, n], [w, n], [w, s]]


def area(lon, lat, side_m):
    return {"type": "MultiPolygon", "coordinates": [[square(lon, lat, side_m)], []]}


def wifi(ssid):
    return {"version": 1, "operator": f"Golden {ssid}", "ssid": ssid, "auth_modes": ["wpa2-personal"]}


def cert(cid, domain, areas, alts, extensions, valid_until="2030-01-01T00:00:00Z"):
    c = {"certificate_id": f"synth-golden-{cid}", "domain": domain,
         "areas": areas, "areas_altitude": alts, "not_valid_after": valid_until,
         "extensions": extensions}
    if "wifi" in extensions:
        c["critical"] = ["wifi"]
    return c


ETH_A = (8.54900, 47.37860)
ETH_B = (8.54915, 47.37870)   # overlaps ETH_A
HB = (8.54020, 47.37790)
STACK = (8.54460, 47.41160)   # same footprint, two floors
UETLI = (8.49180, 47.34970)
BUERK = (8.54130, 47.36670)

certs = [
    # overlap: one query returns several entries, one WiFi, one shop
    cert("eth-a", "portal.eth-guest.test", [area(*ETH_A, 30)], [[450, 500]], {"wifi": wifi("eth-guest")}),
    cert("eth-b", "cafe-overlap.test", [area(*ETH_B, 30)], [[450, 500]], {"shop": {"name": "Cafe Overlap"}}),
    # larger single area, two types: two entries for one certificate
    cert("hb", "portal.sbb-wifi.test", [area(*HB, 120)], [[390, 440]],
         {"wifi": wifi("SBB-FREE"), "payment": {"terminal": "ticket machine"}}),
    # altitude separation: exercises the z subtrees
    cert("stack-low", "portal.floor1.test", [area(*STACK, 30)], [[430, 445]], {"wifi": wifi("floor1")}),
    cert("stack-high", "floor9.test", [area(*STACK, 30)], [[500, 515]], {"lease": {"unit": "9.01"}}),
    # one cert, two disjoint areas with different altitudes
    cert("multi", "portal.multi-site.test", [area(*UETLI, 40), area(*BUERK, 40)],
         [[860, 880], [400, 420]], {"wifi": wifi("multi-site")}),
    # expired: removed again by the release (its entries must go too)
    cert("expired", "portal.expired.test", [area(*ETH_A, 30)], [[450, 500]], {"wifi": wifi("expired")},
         valid_until="2020-01-01T00:00:00Z"),
]


def pt(name, lonlat, alt, r):
    return {"name": name, "lon": lonlat[0], "lat": lonlat[1], "alt": alt, "radius": r}


points = [
    pt("eth-overlap", ((ETH_A[0] + ETH_B[0]) / 2, (ETH_A[1] + ETH_B[1]) / 2), 475, 20),
    pt("hb", HB, 415, 50),
    pt("stack-low", STACK, 437, 5),
    pt("stack-high", STACK, 507, 5),
    pt("stack-both", STACK, 470, 60),
    pt("multi-uetli", UETLI, 870, 20),
    pt("multi-buerk", BUERK, 410, 20),
    pt("empty", (9.2, 46.1), 1500, 50),
    pt("radius-0", ETH_A, 470, 0),
    pt("radius-max", ETH_A, 470, 253),
    pt("antimeridian-east", (180, 0), 0, 20),
    pt("antimeridian-west", (-180, 0), 0, 20),
    pt("south-pole", (0, -90), 2800, 20),
    pt("alt-min", ETH_A, -11000, 10),
    pt("alt-max", ETH_A, 32767 - 11000, 10),
    pt("err-radius", ETH_A, 470, 254),
    pt("err-lon", (180.0001, 0), 0, 10),
    pt("err-alt", ETH_A, -11001, 10),
]

out = sys.argv[1]
json.dump({"place": "golden", "lat": ETH_A[1], "lng": ETH_A[0], "radius": 0, "seed": 0, "certificates": certs},
          open(f"{out}/certs.json", "w"), indent=1)
json.dump(points, open(f"{out}/points.json", "w"), indent=1)
print(f"{len(certs)} certs, {len(points)} points -> {out}")
