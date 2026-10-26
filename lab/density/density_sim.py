"""How much does a WiFi lookup download? A model of the geopki map (stdlib only).

Places businesses at random around a lookup point, gives each a few
GeoCertificates, builds the map tree the way geopki does, makes the lookup the
way the app does, and counts the bytes the server would send:

  today   every node, every certificate hash, every certificate body
  A       typed entries: every node and (type, hash) entry, WiFi bodies only
  A+      type-first tree: only the WiFi branch (nodes, entries, bodies)

See docs/density-summary.md for what the numbers mean, and lab/density/README.md
for checking the model against a real geopki server.

    python lab/density/density_sim.py                       # all places, 25/50/100 m
    python lab/density/density_sim.py --place midtown --radius 100 --runs 10
    python lab/density/density_sim.py --place zurich-centre --k 6 --wifi-share 0.3

It is a model, not the real server: trust the ratios more than the megabytes.
"""
import argparse
import json
import math
import random
import statistics
from dataclasses import dataclass, replace

# --- geopki's grid (pkg/bitstring) ---------------------------------------------
X_BITS, Y_BITS, Z_BITS = 26, 25, 15   # longitude, latitude, altitude bits
Z_MIN = -11000                        # altitude of z = 0, in metres (1 m per step)
EARTH_RADIUS = 6371000.0

# --- what the server sends, in bytes (protobuf overhead included) --------------
# Tree nodes are sized exactly, field by field (node_bytes).
PRUNED_CHILD = 34  # a child hash for a subtree that isn't sent
HASH = 34          # a certificate hash (today)
ENTRY = 40         # a typed entry len(type) | type | hash (options A, A+)
BODY = 2000        # one certificate (lab certificates: 0.9-6.2 KB)

SERVER_F = 0.1     # geopki server: certificate cells <= 0.1 x the certificate's area
QUERY_F = 0.1      # app (GeoQueryEncoder.F_GROW): lookup cells ~0.1 x the circle's area
                   # (1.0 before 2026-10-26, as geopki's Go client: ~4x the circle covered)
RADIUS_ERROR_FACTOR = 1.0052  # app: the lookup radius is inflated by this


@dataclass
class Place:
    lat: float
    lng: float
    density: float  # business locations per km^2 of ground, all floors
    floors: int     # floors the businesses are spread over
    k: int          # certificates per business
    note: str
    ground_m: float = 10.0  # altitude of the ground floor


# Business locations per km^2: US Census ZIP Business Patterns 2021 (Manhattan),
# OpenStreetMap (Zurich; the centre doubled for unmapped upper floors, an estimate).
PLACES = {
    "zurich-suburb": Place(47.3456, 8.5302, 300, 4, 3, "Wollishofen, OSM", 410),
    "zurich-centre": Place(47.3781, 8.5402, 3000, 6, 3, "HB/Paradeplatz, OSM x2 (estimate)", 410),
    "midtown": Place(40.7529, -73.9803, 6000, 40, 4, "Midtown core ZIPs, Census 2021", 20),
    "upper-bound": Place(40.7587, -73.9787, 12000, 40, 4, "Rockefeller Center density over the whole lookup", 20),
}


# --- grid -------------------------------------------------------------------------

class Grid:
    """geopki's grid around one point, in a flat approximation (fine for < 1 km)."""

    def __init__(self, lat: float, lng: float):
        self.lat, self.lng = lat, lng
        self.m_lat = math.pi * EARTH_RADIUS / 180            # metres per degree latitude
        self.m_lng = self.m_lat * math.cos(math.radians(lat))  # ... longitude
        self.cx = 360 / 2 ** X_BITS * self.m_lng  # metres per grid step, x
        self.cy = 180 / 2 ** Y_BITS * self.m_lat  # metres per grid step, y
        self.x0, self.y0 = self.from_degrees(lng, lat)

    @staticmethod
    def from_degrees(lng: float, lat: float) -> tuple[float, float]:
        """Longitude/latitude -> grid coordinates (geopki's discretization)."""
        return (lng + 180) / 360 * 2 ** X_BITS, (lat + 90) / 180 * 2 ** Y_BITS

    def to_grid(self, xm: float, ym: float) -> tuple[float, float]:
        """Metres east/north of the reference point -> grid coordinates."""
        return self.x0 + xm / self.cx, self.y0 + ym / self.cy

    def to_degrees(self, xm: float, ym: float) -> tuple[float, float]:
        """Metres east/north of the reference point -> longitude/latitude."""
        return self.lng + xm / self.m_lng, self.lat + ym / self.m_lat


def cell_bits(x: int, y: int, x_prec: int, y_prec: int) -> str:
    """A cell as geopki's interleaved bit string: x bit, y bit, x bit, ..."""
    xb = format(x, f"0{X_BITS}b")[:x_prec]
    yb = format(y, f"0{Y_BITS}b")[:y_prec]
    return "".join(xb[i] + (yb[i] if i < y_prec else "") for i in range(x_prec))


def cell_size(bits: str) -> tuple[int, int]:
    """Width and height of a cell in grid steps."""
    x_prec, y_prec = (len(bits) + 1) // 2, len(bits) // 2
    return 2 ** (X_BITS - x_prec), 2 ** (Y_BITS - y_prec)


def cover(grid: Grid, intersects, start_x: float, start_y: float, area_m2: float, f: float) -> set[str]:
    """geopki's PolygonsTo2DBitStrings (also the app's GeoQueryEncoder): take the
    finest cell at a point of the shape, grow it to ~f x the shape's area, keep
    every neighbouring cell of that size touching the shape, merge siblings."""
    x_prec, y_prec = X_BITS, Y_BITS
    grow = math.log2(area_m2 * f / (grid.cx * grid.cy))
    if grow > 0:
        x_prec -= (int(grow) + 1) // 2
        y_prec -= int(grow) // 2
    w, h = 2 ** (X_BITS - x_prec), 2 ** (Y_BITS - y_prec)
    x, y = int(start_x) // w * w, int(start_y) // h * h

    seen, hit, todo = set(), set(), [(x, y)]
    while todo:
        c = todo.pop()
        if c in seen:
            continue
        seen.add(c)
        if not intersects(c[0], c[1], w, h):
            continue
        hit.add(c)
        todo += [(c[0] + dx * w, c[1] + dy * h) for dx in (-1, 0, 1) for dy in (-1, 0, 1)]
    if not hit:
        raise ValueError("the start cell doesn't touch the shape")

    cells = {cell_bits(cx, cy, x_prec, y_prec) for cx, cy in hit}
    merged = True
    while merged:  # two halves of a cell -> the cell
        merged = False
        for c in list(cells):
            sibling = c[:-1] + ("1" if c.endswith("0") else "0")
            if c and c in cells and sibling in cells:
                cells -= {c, sibling}
                cells.add(c[:-1])
                merged = True
    return {c for c in cells if not any(c[:i] in cells for i in range(len(c)))}


def rectangle(x0, y0, x1, y1):
    return lambda cx, cy, w, h: cx < x1 and cx + w > x0 and cy < y1 and cy + h > y0


def circle(px, py, rx, ry):
    def touches(cx, cy, w, h):
        nx, ny = min(max(px, cx), cx + w), min(max(py, cy), cy + h)
        return ((nx - px) / rx) ** 2 + ((ny - py) / ry) ** 2 <= 1
    return touches


def altitude_bits(low_m: float, high_m: float) -> str:
    """Smallest altitude cell holding [low, high], both ends included: the common
    prefix of both ends (geopki's SmallestEnclosingZBitString)."""
    a = format(int(low_m - Z_MIN), f"0{Z_BITS}b")
    b = format(int(high_m - Z_MIN), f"0{Z_BITS}b")
    n = 0
    while n < Z_BITS and a[n] == b[n]:
        n += 1
    return a[:n]


def rectangle_cells(grid: Grid, x0: float, y0: float, x1: float, y1: float,
                    low_m: float, high_m: float) -> list[tuple[str, str]]:
    """The (xy, altitude) cells the geopki server gives a rectangle on [low, high)
    altitude; x0..y1 in grid coordinates, the first corner where covering starts."""
    area_m2 = (x1 - x0) * grid.cx * (y1 - y0) * grid.cy
    xy = cover(grid, rectangle(x0, y0, x1, y1), x0, y0, area_m2, SERVER_F)
    z = altitude_bits(low_m, high_m)
    return [(c, z) for c in xy]


def lookup_cells(grid: Grid, radius: int, query_f: float = QUERY_F) -> set[str]:
    """The cells the app sends for a lookup of this radius around the grid's point."""
    r = math.ceil(radius * RADIUS_ERROR_FACTOR)
    px, py = grid.x0, grid.y0
    return cover(grid, circle(px, py, r / grid.cx, r / grid.cy), px + r / grid.cx, py,
                 math.pi * r * r, query_f)


# --- the experiment -----------------------------------------------------------------

OTHER_TYPES = ["brand", "payment", "lease", "service", "space", "other", "t7", "t8", "t9"]


def businesses(place: Place, region_m: float, wifi_share: float, rng):
    """Businesses at random: a 10-50 m rectangle each, on a random floor (3 m),
    uniformly over a square of +-region_m around the lookup point. Each holds k
    certificates, one of them WiFi with probability wifi_share.
    Returns (x, y, w, h, floor_m, types): centre and size in metres."""
    out = []
    for _ in range(int(place.density * (2 * region_m) ** 2 / 1e6)):
        w, h = rng.uniform(10, 50), rng.uniform(10, 50)
        x, y = rng.uniform(-region_m, region_m), rng.uniform(-region_m, region_m)
        floor_m = place.ground_m + 3 * rng.randrange(place.floors)
        types = (["wifi"] if rng.random() < wifi_share else [])
        types += OTHER_TYPES[: place.k - len(types)]
        out.append((x, y, w, h, floor_m, types))
    return out


def place_certificates(grid: Grid, place: Place, region_m: float, wifi_share: float, rng):
    """The businesses' certificates as (id, type, cells)."""
    certificates = []
    for x, y, w, h, floor_m, types in businesses(place, region_m, wifi_share, rng):
        x0, y0 = grid.to_grid(x - w / 2, y - h / 2)
        x1, y1 = grid.to_grid(x + w / 2, y + h / 2)
        cells = rectangle_cells(grid, x0, y0, x1, y1, floor_m, floor_m + 3)
        certificates += [(len(certificates) + i, t, cells) for i, t in enumerate(types)]
    return certificates


def large_areas(grid: Grid, first_id: int):
    """Six certificates for large areas (city, district, ...) on high nodes."""
    x, y = int(grid.x0), int(grid.y0)
    return [(first_id + i, "space", [(cell_bits(x, y, (n + 1) // 2, n // 2), "")])
            for i, n in enumerate((10, 14, 18, 22, 26, 30))]


def certificates_from_file(grid: Grid, path: str):
    """The certificates of a make_certs.py file as (id, type, cells), covered the
    way the geopki server covers them. Every area must be a rectangle."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    certificates = []
    for i, cert in enumerate(data["certificates"]):
        ring = cert["areas"][0]["coordinates"][0][0]
        (x0, y0), (x1, y1) = (Grid.from_degrees(*ring[0]), Grid.from_degrees(*ring[2]))
        low, high = cert["areas_altitude"][0]
        kind = "wifi" if "wifi" in cert.get("extensions", {}) else next(iter(cert["extensions"]))
        certificates.append((i, kind, rectangle_cells(grid, x0, y0, x1, y1, low, high)))
    return data, certificates


def build_tree(certificates) -> dict:
    """Tree nodes -> certificates on them. Every certificate cell plus its
    ancestors: first up the altitude chain, then up the xy cells to the root
    (geopki's ParentPair)."""
    nodes = {("", ""): []}
    for cid, kind, cells in certificates:
        for xy, z in cells:
            nodes.setdefault((xy, z), []).append((cid, kind))
            while z:
                z = z[:-1]
                nodes.setdefault((xy, z), [])
            while xy:
                xy = xy[:-1]
                nodes.setdefault((xy, ""), [])
    return nodes


def answer(nodes: dict, lookup: set[str]) -> set:
    """The nodes geopki sends (BuildNodeQuery): the path down to each lookup cell,
    and everything inside the lookup cells at all altitudes."""
    return {n for n in nodes if any(n[0].startswith(q) or q.startswith(n[0]) for q in lookup)}


def children(node) -> list:
    """A node's possible children: in its altitude subtree, and in xy for the
    root of an altitude subtree."""
    xy, z = node
    children = [(xy, z + "0"), (xy, z + "1")] if len(z) < Z_BITS else []
    if z == "" and len(xy) < X_BITS + Y_BITS:
        children += [(xy + "0", ""), (xy + "1", "")]
    return children


def varint_len(n: int) -> int:
    return max(1, (n.bit_length() + 6) // 7)


def node_bytes(xy: str, z: str, pruned: int, hashes: int) -> int:
    """A geopki.Node as the server encodes it: bit strings top-aligned (xy in 64
    bits, z in 16), zero fields left out (proto3), each hash a 34-byte field,
    plus the field key and length in the Response."""
    size = 0
    for bits, width in ((xy, 64), (z, 16)):
        value = int(bits.ljust(width, "0"), 2) if bits else 0
        size += (1 + varint_len(value) if value else 0) + (1 + varint_len(len(bits)) if bits else 0)
    size += pruned * PRUNED_CHILD + hashes * HASH
    return 1 + varint_len(size) + size


def predict(certificates, lookup: set[str]) -> dict:
    """What the server sends for the lookup: counts, and the tree part in bytes
    (nodes and their child hashes, without the certificate hashes in them)."""
    nodes = build_tree(certificates)
    sent = answer(nodes, lookup)
    on_nodes = [c for n in sent for c in nodes[n]]
    pruned = tree_bytes = 0
    for node in sent:
        p = sum(1 for c in children(node) if c in nodes and c not in sent)  # sent as a hash
        pruned += p
        tree_bytes += node_bytes(*node, p, len(nodes[node])) - len(nodes[node]) * HASH
    return {
        "nodes": len(sent),
        "altitude_nodes": sum(1 for _, z in sent if z),
        "pruned": pruned,
        "hashes": len(on_nodes),
        "certificates": len({cid for cid, _ in on_nodes}),
        "wifi": len({cid for cid, kind in on_nodes if kind == "wifi"}),
        "tree_bytes": tree_bytes,
    }


def run(place: Place, radius: int, wifi_share: float, seed: int, shift: bool,
        query_f: float = QUERY_F) -> dict:
    rng = random.Random(seed)
    grid = Grid(place.lat, place.lng)
    # move the lookup point by up to 500 m, so that it lands at a different spot
    # of the grid each run (the cell overhead depends on where you stand)
    if shift:
        grid.x0 += rng.uniform(-500, 500) / grid.cx
        grid.y0 += rng.uniform(-500, 500) / grid.cy

    lookup = lookup_cells(grid, radius, query_f)
    lookup_m2 = sum(w * grid.cx * h * grid.cy for w, h in map(cell_size, lookup))

    # businesses far enough out that everything reaching the lookup cells is placed
    region = max(3 * radius, 150)
    certificates = place_certificates(grid, place, region, wifi_share, rng)
    certificates += large_areas(grid, len(certificates))

    everything = predict(certificates, lookup)                                  # today, A
    wifi_branch = predict([c for c in certificates if c[1] == "wifi"], lookup)  # A+
    bodies = everything["wifi"] * BODY

    return {
        "lookup_km2": lookup_m2 / 1e6,
        "x_circle": lookup_m2 / (math.pi * radius ** 2),
        "certificates": everything["certificates"],
        "wifi": everything["wifi"],
        "nodes": everything["nodes"],
        "altitude_nodes": everything["altitude_nodes"],
        "today": everything["tree_bytes"] + everything["hashes"] * HASH + everything["certificates"] * BODY,
        "A": everything["tree_bytes"] + everything["hashes"] * ENTRY + bodies,
        "A_repeat": everything["tree_bytes"] + everything["hashes"] * ENTRY,
        "A+": wifi_branch["tree_bytes"] + wifi_branch["hashes"] * ENTRY + bodies,
        "A+_repeat": wifi_branch["tree_bytes"] + wifi_branch["hashes"] * ENTRY,
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--place", choices=sorted(PLACES), action="append",
                    help="place to simulate (repeatable; default: all)")
    ap.add_argument("--radius", type=int, action="append", help="lookup radius in m (default 25, 50, 100)")
    ap.add_argument("--k", type=int, help="certificates per business (default: per place)")
    ap.add_argument("--wifi-share", type=float, default=0.5, help="share of businesses with WiFi (0.5)")
    ap.add_argument("--runs", type=int, default=1, help="lookups at different grid positions (1)")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--query-f", type=float, default=QUERY_F,
                    help=f"lookup cell size relative to the circle ({QUERY_F}; 1.0 = geopki's Go client)")
    args = ap.parse_args()

    print(f"{'place':14} {'r':>4} {'area km2':>9} {'xcircle':>7} {'certs':>6} {'wifi':>5} {'nodes':>6} "
          f"{'alt%':>5} | {'today':>7} {'A':>7} {'A rep':>7} {'A+':>7} {'A+ rep':>7}  MB")
    for name in args.place or list(PLACES):
        place = PLACES[name] if not args.k else replace(PLACES[name], k=args.k)
        for radius in args.radius or [25, 50, 100]:
            results = [run(place, radius, args.wifi_share, args.seed + i, shift=args.runs > 1,
                           query_f=args.query_f)
                       for i in range(args.runs)]
            m = {key: statistics.median(r[key] for r in results) for key in results[0]}
            print(f"{name:14} {radius:4d} {m['lookup_km2']:9.4f} {m['x_circle']:7.1f} {m['certificates']:6.0f} "
                  f"{m['wifi']:5.0f} {m['nodes']:6.0f} {100 * m['altitude_nodes'] / m['nodes']:5.0f} | "
                  + " ".join(f"{m[key] / 1e6:7.2f}" for key in ("today", "A", "A_repeat", "A+", "A+_repeat")),
                  flush=True)
    if args.runs > 1:
        print(f"(medians over {args.runs} lookups at different grid positions)")


if __name__ == "__main__":
    main()
