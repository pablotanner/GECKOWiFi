# Certificate density: how much does a WiFi lookup download?

When the app checks a WiFi network, it asks the map server for every
GeoCertificate around the phone: **all** of them, not only WiFi ones. Once
shops, payment terminals, leases and so on also have certificates, is that
too much to download? And should the map server filter by type?

This folder holds the answer, the simulation behind it, and an experiment
that checks the simulation against the real geopki server.

## The answer in short

- **Small lookups are fine.** Within 25–50 m of the phone, a compressed
  lookup stays under 0.6 MB even in Midtown Manhattan, downloading everything.
- **With the app's finer cells and compression (both done), large lookups
  are fine in European cities too.** At 100 m, downloading everything costs
  0.7 MB in Zürich's centre and 1.9 MB in Midtown (measured and simulated;
  without either change it was 3.7 and 9.1 MB on the real geopki server).
- **Cap the lookup radius at 100 m.** Beyond that, even compressed answers
  grow too fast.
- **Filtering by type is what keeps the densest districts and future growth
  in check:** at Rockefeller-Center density, everything is 3.7–7.5 MB even
  compressed, depending on how many certificate types there are; with type
  filtering it's 0.3–0.8 MB.
- **Filtering needs a change to the map tree.** The server can't just leave
  certificates out, or the phone can no longer tell "filtered" from
  "withheld by an attacker".
- **Two designs work: A (typed entries) and A+ (type-first tree).** A keeps
  every lookup under 0.8 MB, A+ under 0.35 MB, and A+ doesn't grow when new
  certificate types appear.

## 1. How big is a lookup?

- **Radius:** follows the phone's location error.
- **Grid cells, not a circle:** the server answers for the grid cells
  covering the circle. The app's cells (`GeoQueryEncoder.F_GROW = 0.1`) cover
  about 1.7 times the circle's area (1.5–2× depending on where you stand),
  so a 100 m lookup covers **about 0.05 km²**, roughly 230 × 230 m. geopki's
  Go client uses cells the size of the circle (`F_GROW = 1`), which cover
  about 4× (3–6×), 0.13 km². The extra
  area lies outside the circle, where by its own estimate the phone isn't,
  and the app discards its certificates anyway (`coversLocation`). Finer
  cells do tell the map server the circle's position more precisely.
- **All floors:** phone altitude is too unreliable to narrow down, so the
  lookup includes every floor of every building in those cells.

| Where | Typical location error |
|---|---|
| Outdoors, open sky | under 5 m |
| City street | 7–13 m |
| Indoors | 10–50 m, sometimes 100 m or more |

**Indoors is the worst case**, so that's what we size for. Android's
accuracy value is a 68 % radius; multiplying by 1.6 covers about 95 % of
fixes. A typical indoor fix of 30–60 m therefore needs 50–100 m, and **100 m
covers nearly all indoor fixes**. Worse fixes are outliers, and the
downloaded area grows with the radius squared, so we don't query wider.

## 2. How many certificates are there?

```
certificates   ≈  business locations  ×  k       (k = 2–8 certificates each)
WiFi networks  ≈  business locations  ×  50 %   (those with their own network)
```

- **Business locations** come from statistics that count **physical sites**:
  US Census ZIP Business Patterns ("a single physical location at which
  business is conducted") and, for Switzerland, BFS STATENT workplaces.
  Company registers can't be used: they list legal addresses, and one
  address can host hundreds of letterbox companies.
- **k** is the number of certificates per business (WiFi, payment, brand,
  lease, …, the use cases of the GECKO paper). It can't be measured before
  deployment, so we vary it from 2 to 8.
- **Not counted:** homes and leases, businesses without employees, public
  infrastructure. For WiFi this matters little (home networks aren't
  registered); for the *total* it could matter a lot.

| Place | Business locations per km² (all floors) | Source |
|---|---|---|
| Zürich suburb | ~300 | OpenStreetMap |
| Zürich centre | ~3,000 | OpenStreetMap, doubled for unmapped upper floors (estimate) |
| Manhattan average | 1,670 | US Census 2021 |
| Midtown Manhattan | 6,000 | US Census 2021 |
| Rockefeller Center (0.05 km²) | ~14,500 | US Census 2021 |

What matters is the density averaged over a lookup (about 0.05 km² at 100 m),
not the densest single spot. Rockefeller Center is about the size of one
lookup, so the simulation's "upper bound" (12,000/km² over the whole lookup)
corresponds to a lookup right there: a real but extreme case.

## 3. Why the server can't simply filter

The phone proves it received **every** certificate in the area: it rebuilds
part of the map's hash tree, and the root must match the one the server
signed. A certificate's hash says nothing about its type. If the server sent
only WiFi certificates, the phone couldn't tell a skipped shop certificate
from a WiFi certificate an attacker removed. **So the type has to be in the
tree.**

**Option A, typed entries.** Same tree, but nodes list `(type, hash)`:

```
today:      cell C  →  h1, h2, h3, h4
option A:   cell C  →  wifi:h1, shop:h2, payment:h3, lease:h4
```

The phone still gets every node and entry (so the proof works as before),
but full certificates **only for WiFi**.

**Option A+, type first.** The type is the first branch under the root:

```
root
 ├─ wifi     → map → cell C → h1
 ├─ shop     → map → cell C → h2
 └─ payment  → map → cell C → h3
```

A WiFi lookup walks only the `wifi` branch; every other type costs one hash.

| | Option A | Option A+ |
|---|---|---|
| Saves at 100 m (simulated, raw / compressed) | 3–5× / 4–10× | 6–16× / 8–23× |
| Grows when other types are added | yes | **no** |
| Change to geopki | node hash and leaf contents | also how certificates are placed in the tree, queries, checks |
| Trees / signatures | one / one | one / one |
| New types later | no change needed | no change needed (branch = hash of the type name) |
| Migration | rebuild the map once | rebuild the map once |

Security is the same for both: a network attacker can't hide or relabel
anything. The map server could file a WiFi certificate under the wrong
type; requires auditors or something like that.

## 4. Results

`density_sim.py --runs 5` and `--runs 5 --gzip` (medians over 5 grid
positions): the app's lookup cells, all floors, half of the businesses with
WiFi, k = 3 (Zürich) or 4 (Manhattan), WiFi certificates of 875 B on average
(the lab certificates as templates), others of 2.1 KB. "Today" = downloading
everything; "compressed" = as the geopki server now sends it (gzip, measured
ratios, section 5):

| Place | Radius | Today: raw / compressed | Option A: raw / compressed | Option A+: raw / compressed |
|---|---|---|---|---|
| Zürich suburb | 25 m | 0.03 / 0.01 MB | 0.01 / < 0.01 MB | < 0.01 / < 0.01 MB |
| | 100 m | 0.20 / 0.08 MB | 0.07 / 0.02 MB | 0.03 / 0.01 MB |
| Zürich centre | 25 m | 0.18 / 0.09 MB | 0.04 / 0.01 MB | 0.02 / 0.01 MB |
| | 50 m | 0.50 / 0.23 MB | 0.14 / 0.04 MB | 0.07 / 0.02 MB |
| | 100 m | 1.63 / **0.72** MB | 0.57 / **0.16** MB | 0.29 / **0.09** MB |
| Midtown (6,000/km²) | 25 m | 0.46 / 0.22 MB | 0.09 / 0.03 MB | 0.05 / 0.02 MB |
| | 50 m | 1.25 / 0.58 MB | 0.30 / 0.09 MB | 0.14 / 0.05 MB |
| | 100 m | 4.16 / **1.91** MB | 1.15 / **0.32** MB | 0.53 / **0.17** MB |
| Upper bound (12,000/km²) | 100 m | 8.01 / **3.73** MB | 2.02 / **0.58** MB | 0.96 / **0.33** MB |
| Upper bound, k = 8 | 100 m | 15.8 / **7.54** MB | 2.92 / **0.79** MB | 0.96 / **0.33** MB |

About 1 MB is comfortable and about 3 MB the limit (1 MB ≈ 1 s on a normal
connection, ≈ 8 s on a slow captive network). So, compressed:

- **Today (no type filter):** fine in European cities up to 100 m (0.7 MB),
  borderline in Midtown (1.9 MB), too big at the upper bound (3.7–7.5 MB).
- **Option A:** at most 0.8 MB everywhere, but grows with k.
- **Option A+:** at most 0.33 MB everywhere, unchanged by k.
- **Repeat visits** (certificates cached by hash) only download the tree
  part: compressed, about a quarter less for A and half for A+.

With geopki's default cells (`--query-f 1`) all of these are about 2.2×
larger. An earlier version of this table assumed 2 KB per WiFi certificate;
real ones are 875 B on average, which makes A and A+ about twice as small.

## 5. Checked against the real geopki server

The experiment loaded the simulation's businesses, as real certificates,
into a throwaway geopki server and compared its answers with the
simulation's prediction for the same certificates (2026-10-26, with
geopki's default lookup cells; the cell size doesn't change what is being
checked, the server answers any set of cells the same way):

| 100 m lookup | Zürich centre: measured / predicted | Midtown: measured / predicted |
|---|---|---|
| Certificates returned | 1,341 / 1,341 | 3,535 / 3,535 |
| of which WiFi | 231 / 231 | 450 / 450 |
| Certificate hashes in nodes | 10,380 / 10,446 | 26,416 / 26,624 |
| Nodes | 35,039 / 35,405 | 57,264 / 57,887 |
| Tree part | 1,055,910 B / 1,065,481 B | 2,004,322 B / 2,023,494 B |
| Whole answer (today) | 3.74 MB | 9.09 MB |

- The simulation predicts the returned certificates **exactly**, and nodes
  and the tree part **within 1–2 % (25–100 m), on the high side**, so its
  results are slightly conservative.
- The signed heads and inclusion proof add only 279 bytes.

### Compression (2026-10-27)

Measured with geopki's compression (gzip level 1, in the geocert-extensions
PR), the app's lookup cells, and realistic certificates: WiFi ones made from
the lab certificates with their own names, domains and key hashes, others
filled with random words.

| Lookup | Whole answer: raw → gzip | Tree part (nodes, hashes) | Certificate bodies |
|---|---|---|---|
| Zürich centre, 25 m | 211 KB → 101 KB (48 %) | 25 % | 53 % |
| Zürich centre, 50 m | 502 KB → 224 KB (45 %) | 22 % | 52 % |
| Zürich centre, 100 m | 1.77 MB → 0.76 MB (43 %) | 21 % | 51 % |
| Midtown, 25 m | 495 KB → 236 KB (48 %) | 27 % | 51 % |
| Midtown, 50 m | 1.24 MB → 0.58 MB (47 %) | 24 % | 52 % |
| Midtown, 100 m | 4.41 MB → 2.00 MB (45 %) | 24 % | 51 % |

- Clients that don't ask for compression get the same bytes as before.
- The simulation uses 24 % for the tree, 52 % for non-WiFi bodies and 50 %
  for WiFi bodies (`GZIP_*`). WiFi bodies alone measured 35 % together and
  65 % each on its own; synthetic ones are more alike than real ones, so 50 %
  is a middle estimate. With these ratios it reproduces the six measured
  answers within 1 % raw and within 3 % compressed, from the same
  certificates.

What this doesn't check: the business counts, k and the WiFi share (inputs,
not model), and the size and compression of certificate types that don't
exist yet (non-WiFi: 2.1 KB, an assumption).

## 6. Decisions

1. **Check radius:** 1.6x reported accuracy, at least the lab setting
   (default 50 m), at most 100 m (done: `checkRadius` in `VerificationViewModel`).
2. **Location worse than 100 m** (accuracy worse than 62 m): the check is
   refused as "location too imprecise" (done). Still open: wait a few seconds
   for a better fix, or use the saved answer, before refusing. Never query wider.
3. **Keep all floors** in the lookup.
4. **Filter by type:** option A as the minimum, **option A+ preferred** if the
   larger tree change is acceptable. 
5. **Cache certificates by their hash.**
6. **DONE Compress the Answer:** Server compresses the body and marks it `Content-Encoding: gzip` and the client decompresses before reading. (MEASURED: at gzip level 1 it can fit around 1.6x more answers through same link, so worth it for bandwidth-bound server). In the geocert-extensions PR, for `/v1/query` and `/v1/certificates`; answers shrink to 43–48 % (section 5), and the app's HTTP client decompresses them transparently.
7. **DONE** Finer lookup cells: about 2.2x
   less of everything, at the cost of the map server learning the circle's
   position more precisely. 

## 7. The scripts

All Python is standard library only. Run from the repository root.

| File | What it's for |
|---|---|
| `density_sim.py` | **The simulation.** Seconds per run; models today, A and A+ |
| `make_certs.py` | Writes the simulation's businesses as real synthetic certificates (`out/`, git-ignored) |
| `stack.sh` | Starts and removes a throwaway geopki server in WSL for the experiment |
| `measure.py` | Queries that server and prints measured vs. predicted |

### The simulation

```
python lab/density/density_sim.py                                     # all places, 25/50/100 m
python lab/density/density_sim.py --place midtown --radius 100 --runs 10
python lab/density/density_sim.py --place zurich-centre --k 6 --wifi-share 0.3
```

It places businesses at random (10–50 m rectangles on random floors, as many
per km² as the place's density), gives each k certificates, turns them into
grid cells the way the geopki server does, builds the tree, makes the lookup
the way the app does, and counts what the server would send. `--runs`
repeats the lookup at different grid positions and prints medians;
`--query-f 1` uses geopki's default lookup cells instead of the app's;
`--gzip` gives sizes as sent compressed. The byte sizes, compression ratios
and densities are constants at the top of the file.

### The experiment (only needed when the model changes)

```
python lab/density/make_certs.py --place zurich-centre               # Windows
wsl bash lab/density/stack.sh up                                     # start the throwaway server
wsl bash lab/density/stack.sh load lab/density/out/zurich-centre-r100-s1.json   # ~2-5 min
python lab/density/measure.py lab/density/out/zurich-centre-r100-s1.json        # Windows
wsl bash lab/density/stack.sh down                                   # remove everything
```

Run the `wsl` commands from the repository root (WSL starts in the same
folder).

`stack.sh` keeps the experiment away from the demo setup:
- its own PostgreSQL in a Docker container (`127.0.0.1:5433`); the WSL
  database `geopki_ext` isn't touched;
- its own geopki server on port 1236 with its own keys; the demo server on
  1234 and the realm server on 1235 keep running;
- one extra Trillian tree, deleted by `down`;
- state in `~/Thesis/density-exp`, removed by `down`.

`load` refuses certificates whose ID doesn't start with `synth-`, and
`measure.py` refuses ports 1234 and 1235.

## Limits and open points

- **Certificate size:** WiFi certificates are made from the lab certificates
  (875 B on average; which template how often is an assumption, `TEMPLATES`
  in `make_certs.py`). Non-WiFi certificates have no format yet: 2.1 KB of
  random words is an assumption, and only matters for "today".
- **WiFi body compression** (50 %) is an estimate between the measured 35 %
  and 65 %.
- **Businesses** are random rectangles on one floor each, not real buildings.
- **Zürich's density** is OpenStreetMap doubled; STATENT (per hectare) would
  replace the estimate.
- **WiFi share:** WiGLE (crowdsourced networks per area) and scans with the
  app could replace the 50 %.
