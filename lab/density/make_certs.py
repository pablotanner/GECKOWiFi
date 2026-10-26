"""Writes synthetic GeoCertificates for the density experiment (stdlib only).

The same random businesses density_sim.py uses (10-50 m rectangles on random
floors, k certificates each, some with WiFi), as real certificates in geopki's
format, plus three large areas (city, district, neighbourhood). Every
certificate is invented: ids start with "synth-", domains end in ".example",
and lab/density/stack.sh refuses to load anything else.

    python lab/density/make_certs.py --place zurich-centre
    python lab/density/make_certs.py --place midtown --radius 100 --seed 2

Writes lab/density/out/<place>-r<radius>-s<seed>.json (git-ignored).
"""
import argparse
import json
import os
import random

import density_sim as sim

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")


def rectangle_area(grid: sim.Grid, x: float, y: float, w: float, h: float) -> dict:
    """A w x h m rectangle centred x/y m from the grid's point, as a geopki area:
    [exterior rings, interior rings], counter-clockwise from the south-west corner."""
    (lng0, lat0), (lng1, lat1) = grid.to_degrees(x - w / 2, y - h / 2), grid.to_degrees(x + w / 2, y + h / 2)
    ring = [[lng0, lat0], [lng1, lat0], [lng1, lat1], [lng0, lat1], [lng0, lat0]]
    return {"type": "MultiPolygon", "coordinates": [[[[round(a, 7), round(b, 7)] for a, b in ring]], []]}


def certificate(cert_id: str, n: int, area: dict, low: float, high: float, kind: str, body_bytes: int) -> dict:
    cert = {
        "certificate_id": cert_id,
        "domain": f"synth-{n}.example",
        "areas": [area],
        "areas_altitude": [[low, high]],
        "not_valid_after": "2027-12-31T00:00:00Z",
    }
    if kind == "wifi":
        cert["critical"] = ["wifi"]
        cert["extensions"] = {"wifi": {"version": 1, "operator": f"Synthetic {n}",
                                       "ssid": f"synth-{n}", "auth_modes": ["wpa2-personal"]}}
    else:
        cert["extensions"] = {kind: {}}
    # pad to a realistic size; the padding sits in an extension of its own that
    # no validator knows, so the server stores it unchecked
    size = len(json.dumps(cert, separators=(",", ":")))
    if body_bytes > size:
        pad = body_bytes - size - len(',"synthetic":{"padding":""}')
        cert["extensions"]["synthetic"] = {"padding": "x" * max(pad, 0)}
    return cert


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--place", choices=sorted(sim.PLACES), default="zurich-centre")
    ap.add_argument("--radius", type=int, default=100, help="largest lookup to measure, sets the area filled (100)")
    ap.add_argument("--k", type=int, help="certificates per business (default: per place)")
    ap.add_argument("--wifi-share", type=float, default=0.5)
    ap.add_argument("--body-bytes", type=int, default=sim.BODY, help=f"pad certificates to this size ({sim.BODY})")
    ap.add_argument("--seed", type=int, default=1)
    args = ap.parse_args()

    place = sim.PLACES[args.place] if not args.k else sim.replace(sim.PLACES[args.place], k=args.k)
    grid = sim.Grid(place.lat, place.lng)
    rng = random.Random(args.seed)
    region = max(3 * args.radius, 150)

    certs = []
    for n, (x, y, w, h, floor_m, types) in enumerate(sim.businesses(place, region, args.wifi_share, rng)):
        area = rectangle_area(grid, x, y, w, h)
        for kind in types:
            certs.append(certificate(f"synth-{args.place}-{n}-{kind}", n, area, floor_m, floor_m + 3,
                                     kind, args.body_bytes))
    # large areas on high tree nodes, all floors
    for i, side in enumerate((10_000, 2_000, 800)):
        n = 100_000 + i
        certs.append(certificate(f"synth-{args.place}-area-{side}", n, rectangle_area(grid, 0, 0, side, side),
                                 place.ground_m - 20, place.ground_m + 300, "space", args.body_bytes))

    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"{args.place}-r{args.radius}-s{args.seed}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"place": args.place, "lat": place.lat, "lng": place.lng, "radius": args.radius,
                   "seed": args.seed, "certificates": certs}, f, separators=(",", ":"))
    wifi = sum(1 for c in certs if "wifi" in c["extensions"])
    print(f"{path}: {len(certs)} certificates ({wifi} WiFi), {os.path.getsize(path) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
