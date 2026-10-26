# How many GeoCertificates does a lookup download?

## The question

When the app checks a WiFi network, it asks the map server for every
GeoCertificate around the phone. Today that means **all** certificates,
not only WiFi ones. Once shops, payment terminals, leases and so on also
have certificates, is that too much to download? And should the map server
filter by type?

## The answer in short

- **Small lookups are fine.** Within 25–50 m of the phone, a lookup is well
  under 1 MB even in a city centre.
- **Large lookups are not.** Indoors, location gets imprecise and the lookup
  area grows. At 100 m in a dense business district, downloading everything
  is about 10 MB.
- **We should cap the lookup radius at 100 m** and filter by type.
- **Filtering needs a change to the map tree.** The server can't just leave
  certificates out, because the phone could no longer tell "filtered" from
  "withheld by an attacker".
- **Two designs work: A (typed entries) and A+ (type-first tree).** Both
  make 100 m lookups feasible in European cities. A+ also stays small in the
  densest districts, and it doesn't get bigger when new certificate types
  appear.

## 1. How big is a lookup?

A lookup covers more than you might expect:

- **Radius:** the phone's location error, which is larger indoors.
- **Grid cells, not a circle:** the map works in grid cells, and the cells
  that cover a circle are 4–6 times its area.
- **All floors:** phone altitude is too unreliable to narrow down, so the
  lookup includes every floor of every building.

Location error, from published measurements:

| Where | Typical error |
|---|---|
| Outdoors, open sky | under 5 m |
| City street | 7–13 m |
| Indoors | 10–50 m, sometimes 100 m or more |

**Indoors is the worst case.** That's what we size for.

## 2. How many certificates are there?

We count **business locations** and assume each holds a few certificates:

```
certificates  ≈  business locations  ×  k        (k = 2–8 certificates each)
WiFi certs    ≈  business locations  ×  50 %    (those with their own network)
```

Business locations come from official statistics that count **physical
sites**: US Census ZIP Business Patterns, and for Switzerland, BFS STATENT
workplaces. Company registers are not used: they list legal addresses, and
one address can host hundreds of letterbox companies.

| Place | Business locations per km² (all floors) | Source |
|---|---|---|
| Zürich suburb | ~300 | OpenStreetMap |
| Zürich centre | ~3,000 | OpenStreetMap, doubled for unmapped upper floors (estimate) |
| Manhattan average | 1,670 | US Census 2021 |
| Midtown Manhattan | 6,000 | US Census 2021, measured |
| Densest few blocks (Rockefeller Center) | ~14,500 | US Census 2021 |

**Not counted:** homes and leases, small businesses without employees,
public infrastructure. For WiFi this matters little, because home networks
aren't registered. For the *total* number of certificates, it could matter
a lot.

**Weakest assumptions:** k and the 50 % WiFi share. Nobody can measure them
before GECKO is deployed, so we show results for a range.

## 3. Why the server can't simply filter

The phone proves that it received **every** certificate in the area. It
rebuilds part of the map's hash tree, and the root must match the root the
server signed. But a certificate's hash says nothing about its type. If the
server sent only WiFi certificates, the phone would see hashes without
certificates and couldn't tell a skipped shop certificate from a WiFi
certificate an attacker removed.

**So the type has to be built into the tree.** There are two ways.

## 4. Option A: typed entries

Same tree, but each node lists `(type, hash)` instead of just `hash`.

```
today:      cell C  →  h1, h2, h3, h4
option A:   cell C  →  wifi:h1, shop:h2, payment:h3, lease:h4
```

The phone still receives every node and every entry, so the completeness
proof works as before. But it downloads full certificates **only for WiFi
entries**.

| Good | Bad |
|---|---|
| Smallest change: same tree shape, same queries | The tree nodes are still sent, so the saving is limited (2–7×) |
| New types (e.g. "charging station") need no change | Gets bigger as more non-WiFi certificates are added |
| One tree, one signature, same history log | Changes the node hash: server, clients and test vectors, plus a one-time rebuild of the map |

## 5. Option A+: type first, then place

The type becomes the first branch of the tree, with a normal map under each
type:

```
root
 ├─ wifi     → map → cell C → h1
 ├─ shop     → map → cell C → h2
 ├─ payment  → map → cell C → h3
 └─ lease    → map → cell C → h4
```

A WiFi lookup walks only the `wifi` branch. Every other type costs one hash
in total, however many certificates it has.

| Good | Bad |
|---|---|
| Downloads only WiFi data: 3–12× smaller | Bigger change: how certificates are placed in the tree, queries, checks |
| **Doesn't grow when other types are added** | A certificate with several types is stored in several branches |
| Still one tree, one signature, one history log | A client that wants *every* type downloads more than today |
| New types: the branch comes from a hash of the type name, so no registry | Needs a one-time rebuild of the map, like A |

**Security is the same for both:** a network attacker can't hide or
relabel anything. The map server could file a WiFi certificate under the
wrong type, but auditors (or phones spot-checking a few random
certificates) can detect it. That's the same kind of trust we already place
in the server not to leave certificates out.

## 6. Results

Simulated download per WiFi lookup, all floors, 4 certificates per
business (3 in Zürich), half with WiFi. "Today" means downloading
everything:

| Place | Radius | Today | Option A | Option A+ |
|---|---|---|---|---|
| Zürich suburb | 25 m | 0.02 MB | 0.01 MB | 0.01 MB |
| | 100 m | 0.4 MB | 0.2 MB | 0.1 MB |
| Zürich centre | 25 m | 0.5 MB | 0.15 MB | 0.09 MB |
| | 50 m | 1.1 MB | 0.4 MB | 0.25 MB |
| | 100 m | 4.3 MB | **1.8 MB** | **1.0 MB** |
| Midtown (6,000/km²) | 25 m | 0.9 MB | 0.24 MB | 0.15 MB |
| | 50 m | 2.3 MB | 0.8 MB | 0.5 MB |
| | 100 m | 9.4 MB | **3.3 MB** | **1.9 MB** |
| Densest blocks (12,000/km²) | 100 m | 18 MB | **6.1 MB** | **3.4 MB** |

We call about 1 MB comfortable and about 3 MB the limit (1 MB takes about
1 s on a normal connection and about 8 s on a slow captive network).

- **Option A:** feasible in European cities; borderline in Midtown at
  100 m; too big in the densest blocks.
- **Option A+:** feasible everywhere, at worst just over the limit for the
  rare 100 m lookup in the densest blocks.
- **With more certificates per business** (k = 8), A grows (8.3 MB in the
  densest blocks) while A+ stays at 3.4 MB.
- **On repeat visits** (certificates cached by hash), only the tree part is
  downloaded: about a quarter less for A, about half for A+.

What doesn't help much: **filtering by altitude.** Even with a perfect
±2-floor window, the tree part only halves, because every certificate
brings a chain of altitude nodes with it.

## 7. Decisions and recommendations

1. **Radius for a check:** 1.6 × the reported accuracy, at least 25 m, at
   most 100 m. Android's accuracy only covers the true position 68 % of the
   time, and 1.6× raises that to about 95 %.
2. **If the location is worse than 100 m:** wait a few seconds for a better
   fix, use the saved answer if there is one, otherwise say "location too
   imprecise". Never query wider.
3. **Keep all floors** in the lookup. Phone altitude isn't reliable, and a
   window that's too narrow silently misses certificates.
4. **The network list's lookup (D26)** should stop using 253 m. Use the same
   radius as a check, refresh only after moving, and ask the server
   "has the map changed?" before downloading again.
5. **Filter by type:** option A as the minimum, **option A+ preferred** if
   the larger tree change is acceptable. *(To confirm with the advisor.)*
6. **Cache certificates by their hash,** so repeat visits only download the
   tree part.
7. **Migration:** both options change the tree format. The map is rebuilt
   from the stored certificates; in a real deployment the old and new tree
   run side by side for a while.

## 8. The simulation

A Python model of geopki's map (`proofsim.py`, `worst.py`):

1. Place businesses at random: one random 10–50 m rectangle each, on a
   random floor, as many per km² as the statistics say.
2. Give each business k certificates; half of the businesses get a WiFi
   certificate.
3. Turn each certificate into grid cells the way geopki's server does, and
   build the tree.
4. Make a lookup the way the app does and count what the server would send:
   tree nodes, hashes or entries, and 2 KB per certificate.

**Limits:**
- It's a model, not the real server. Trust the ratios more than the exact
  megabytes.
- Businesses are random rectangles, not real buildings.
- Every business sits on one floor.
- All places use Midtown's latitude.

**TODO:** measure on the real geopki server in the lab with
mock certificates, and replace Zürich's "doubled" estimate with real
STATENT workplace numbers.

