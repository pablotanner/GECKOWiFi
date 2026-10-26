"""Measures what a real geopki server sends for a lookup and compares it with
density_sim.py's prediction for the same certificates (stdlib only).

Run against the experiment server of lab/density/stack.sh (port 1235) after
loading a make_certs.py file into it; never against the demo server.

    python lab/density/measure.py lab/density/out/zurich-centre-r100-s1.json
    python lab/density/measure.py <file> --radius 25 --radius 50 --server http://localhost:1235

Per lookup radius it prints, measured vs. predicted:
  nodes, certificate hashes in them, distinct certificates, WiFi certificates,
  the tree part in bytes, and the whole answer raw and as transferred (gzip).
"""
import argparse
import gzip
import json
import time
import urllib.request

import density_sim as sim

MAX_Z = 2 ** sim.Z_BITS - 1  # the app queries all altitudes: 0..C_Z


# --- protobuf, just enough for geopki's Request and Response ------------------------

def varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def field(number: int, wire: int, payload: bytes) -> bytes:
    key = varint(number << 3 | wire)
    return key + (payload if wire == 0 else varint(len(payload)) + payload)


def request(cells: set[str]) -> bytes:
    """geopki.Request: the lookup cells (bits top-aligned in 64) and all altitudes."""
    body = b""
    for bits in sorted(cells):
        cell = field(1, 0, varint(int(bits.ljust(64, "0"), 2))) + field(2, 0, varint(len(bits)))
        body += field(1, 2, cell)
    return body + field(2, 0, varint(0)) + field(3, 0, varint(MAX_Z))


def fields(data: bytes):
    """(field number, wire type, value, size on the wire) of one message."""
    i = 0
    while i < len(data):
        start = i
        key, i = read_varint(data, i)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, i = read_varint(data, i)
        elif wire == 2:
            length, i = read_varint(data, i)
            value, i = data[i:i + length], i + length
        else:
            raise ValueError(f"unexpected wire type {wire}")
        yield number, wire, value, i - start


def read_varint(data: bytes, i: int) -> tuple[int, int]:
    n = shift = 0
    while True:
        b = data[i]
        i += 1
        n |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            return n, i


def parse(answer: bytes) -> dict:
    """Counts and byte sizes of a geopki.Response."""
    m = {"nodes": 0, "altitude_nodes": 0, "pruned": 0, "hashes": 0, "node_bytes": 0,
         "certificates": [], "head_bytes": 0}
    for number, _, value, size in fields(answer):
        if number == 4:  # Node
            m["nodes"] += 1
            m["node_bytes"] += size
            for n, _, v, _ in fields(value):
                if n == 4 and v > 0:
                    m["altitude_nodes"] += 1
                elif n in (5, 6, 7, 8):
                    m["pruned"] += 1
                elif n == 9:
                    m["hashes"] += 1
        elif number == 5:  # certificate
            m["certificates"].append(value)
        else:  # signed heads, inclusion proof
            m["head_bytes"] += size
    return m


def query(server: str, cells: set[str], certificates: bool) -> tuple[bytes, int, float]:
    """POSTs a lookup; returns the decoded answer, the bytes transferred and the time."""
    url = f"{server}/v1/query" + ("?c" if certificates else "")
    req = urllib.request.Request(url, data=request(cells), method="POST",
                                 headers={"Accept-Encoding": "gzip",
                                          "Content-Type": "application/octet-stream"})
    start = time.perf_counter()
    with urllib.request.urlopen(req, timeout=300) as r:
        wire = r.read()
        encoding = r.headers.get("Content-Encoding", "")
    seconds = time.perf_counter() - start
    return (gzip.decompress(wire) if encoding == "gzip" else wire), len(wire), seconds


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("certs", help="file written by make_certs.py, loaded into the server")
    ap.add_argument("--server", default="http://localhost:1235")
    ap.add_argument("--radius", type=int, action="append", help="lookup radius in m (default 25, 50, 100)")
    args = ap.parse_args()
    if args.server.rstrip("/").endswith(":1234"):
        raise SystemExit("port 1234 is the demo server; measure against the experiment server (1235)")

    with open(args.certs, encoding="utf-8") as f:
        meta = json.load(f)
    grid = sim.Grid(meta["lat"], meta["lng"])
    data, certificates = sim.certificates_from_file(grid, args.certs)

    print(f"{data['place']}, {len(certificates)} certificates, server {args.server}\n")
    for radius in args.radius or [25, 50, 100]:
        cells = sim.lookup_cells(grid, radius)
        predicted = sim.predict(certificates, cells)
        raw, wire, seconds = query(args.server, cells, certificates=True)
        proof_raw, proof_wire, _ = query(args.server, cells, certificates=False)
        m = parse(raw)
        bodies = m["certificates"]
        wifi = sum(1 for b in bodies if "wifi" in json.loads(b).get("extensions", {}))
        tree_bytes = m["node_bytes"]  # nodes incl. their hashes, as sent
        rows = [
            ("nodes", m["nodes"], predicted["nodes"]),
            ("  of which altitude nodes", m["altitude_nodes"], predicted["altitude_nodes"]),
            ("pruned child hashes", m["pruned"], predicted["pruned"]),
            ("certificate hashes in nodes", m["hashes"], predicted["hashes"]),
            ("distinct certificates", len(bodies), predicted["certificates"]),
            ("  of which WiFi", wifi, predicted["wifi"]),
            ("tree part, bytes", tree_bytes,
             predicted["tree_bytes"] + predicted["hashes"] * sim.HASH),
            ("signed heads + proof, bytes", m["head_bytes"], None),
            ("certificate bodies, bytes", sum(len(b) for b in bodies), None),
        ]
        print(f"r = {radius} m: {len(cells)} lookup cells, answer in {seconds:.2f} s")
        print(f"  {'':32} {'measured':>12} {'predicted':>12}")
        for label, measured, pred in rows:
            print(f"  {label:32} {measured:12,} {'' if pred is None else f'{pred:12,}'}")
        print(f"  {'whole answer, raw':32} {len(raw):12,}")
        print(f"  {'whole answer, transferred':32} {wire:12,}  ({wire / len(raw):.0%} of raw)")
        print(f"  {'without bodies (hashes only)':32} {len(proof_raw):12,}  transferred {proof_wire:,}\n")


if __name__ == "__main__":
    main()
