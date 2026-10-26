# Certificate density: how much does a WiFi lookup download?

When the app checks a WiFi network, it asks the map server for every
GeoCertificate around the phone: **all** of them, not only WiFi ones. Once
shops, payment terminals, leases and so on also have certificates, is that
too much to download? And should the map server filter by type?

This folder holds the answer, the simulation behind it, and an experiment
that checks the simulation against the real geopki server.

## The answer in short

- **Small lookups are fine.** Within 25–50 m of the phone, filtered lookups
  stay under about 0.4 MB even in Midtown Manhattan.
- **Large lookups are not.** Indoors, location gets imprecise and the lookup
  area grows. At 100 m in a dense business district, downloading everything
  is about 1.7 MB (Zürich centre) to 4.2 MB (Midtown) with the app's lookup
  cells, and twice that with geopki's default cells (3.7 and 9.1 MB, measured
  on the real geopki server).
- **Cap the lookup radius at 100 m and filter by type.**
- **Filtering needs a change to the map tree.** The server can't just leave
  certificates out, or the phone can no longer tell "filtered" from
  "withheld by an attacker".
- **Two designs work: A (typed entries) and A+ (type-first tree).** Both
  make 100 m lookups feasible in European cities. A+ also stays small in the
  densest districts, and doesn't grow when new certificate types appear.

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
| Saves (simulated) | 2–7× | 3–12× |
| Grows when other types are added | yes | **no** |
| Change to geopki | node hash and leaf contents | also how certificates are placed in the tree, queries, checks |
| Trees / signatures | one / one | one / one |
| New types later | no change needed | no change needed (branch = hash of the type name) |
| Migration | rebuild the map once | rebuild the map once |

Security is the same for both: a network attacker can't hide or relabel
anything. The map server could file a WiFi certificate under the wrong
type; requires auditors or something like that.

## 4. Results

`density_sim.py --runs 5` (medians over 5 grid positions), the app's lookup
cells, all floors, half of the businesses with WiFi, k = 3 (Zürich) or 4
(Manhattan). "Today" = downloading everything:

| Place | Radius | Today | Option A | Option A+ |
|---|---|---|---|---|
| Zürich suburb | 25 m | 0.03 MB | 0.01 MB | < 0.01 MB |
| | 100 m | 0.21 MB | 0.09 MB | 0.04 MB |
| Zürich centre | 25 m | 0.18 MB | 0.05 MB | 0.03 MB |
| | 50 m | 0.51 MB | 0.17 MB | 0.11 MB |
| | 100 m | 1.69 MB | **0.69 MB** | **0.40 MB** |
| Midtown (6,000/km²) | 25 m | 0.46 MB | 0.12 MB | 0.08 MB |
| | 50 m | 1.27 MB | 0.37 MB | 0.21 MB |
| | 100 m | 4.24 MB | **1.38 MB** | **0.76 MB** |
| Upper bound (12,000/km²) | 100 m | 8.16 MB | **2.48 MB** | **1.43 MB** |
| Upper bound, k = 8 | 100 m | 15.6 MB | **3.39 MB** | **1.43 MB** |

- **Option A:** feasible everywhere except the upper bound with many
  certificate types (k = 8: 3.4 MB).
- **Option A+:** feasible everywhere (at most 1.4 MB), and unchanged by k.

With geopki's default cells (`--query-f 1`) all of these are about 2.2×
larger.

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
- The server **doesn't compress** these answers (transferred = raw).
- The signed heads and inclusion proof add only 279 bytes.

What this doesn't check: the business counts, k and the WiFi share (inputs,
not model), and the certificate size (2 KB, padded; see *Open points*).

## 6. Decisions and recommendations

1. **Check radius:** 1.6 × reported accuracy, at least 25 m, at most 100 m.
2. **Location worse than 100 m:** wait for a better fix, use the saved answer,
   or say "location too imprecise". Never query wider.
3. **Keep all floors** in the lookup.
4. **The network list's lookup (D26)** should stop using 253 m: same radius as
   a check, refresh only after moving, and ask the server whether the map
   changed before downloading again.
5. **Filter by type:** option A as the minimum, **option A+ preferred** if the
   larger tree change is acceptable. 
6. **Cache certificates by their hash.**
7. **Compress the Answer:** Server compresses the body and marks it `Content-Encoding: gzip` and the client decompresses before reading. (MEASURED: at gzip level 1 it can fit around 1.6x more answers through same link, so worth it for bandwidth-bound server)
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
`--query-f 1` uses geopki's default lookup cells instead of the app's. The
byte sizes and densities are constants at the top of the file.

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
- its own geopki server on port 1235 with its own keys; the demo server on
  1234 keeps running;
- one extra Trillian tree, deleted by `down`;
- state in `~/Thesis/density-exp`, removed by `down`.

`load` refuses certificates whose ID doesn't start with `synth-`, and
`measure.py` refuses port 1234.

## Limits and open points

- **Certificate size:** `make_certs.py` pads every certificate to 2 KB, the
  simulation's assumption, so the experiment checks the tree, not the size.
  WiFi certificates could use the lab certificates (0.9–6.2 KB) as templates
  instead.
- **Businesses** are random rectangles on one floor each, not real buildings.
- **Zürich's density** is OpenStreetMap doubled; STATENT (per hectare) would
  replace the estimate.
- **WiFi share:** WiGLE (crowdsourced networks per area) and scans with the
  app could replace the 50 %.
