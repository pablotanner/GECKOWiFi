"""Captures the LiveFixtureTest fixtures from a running throwaway geopki server.

Usage: python3 capture_fixtures.py <server url> <golden.json> <fixture dir>

Asks the golden query "hb" again, now for WiFi certificates only (`?c`), and
saves the request, the answer, its first certificate, the public key and the
standalone signed map and consistency heads.
"""
import base64
import json
import sys
import urllib.request

url, golden_path, out = sys.argv[1:4]
golden = json.load(open(golden_path))


def save(name, data):
    with open(f"{out}/{name}", "wb") as f:
        f.write(data)


def get(path, body=None):
    req = urllib.request.Request(url + path, data=body, method="POST" if body else "GET")
    with urllib.request.urlopen(req) as r:
        return r.read()


def varint(b, i):
    n = shift = 0
    while True:
        n |= (b[i] & 0x7F) << shift
        i += 1
        if b[i - 1] < 0x80:
            return n, i
        shift += 7


def certificates(response):
    """The bytes of field 5 (Certificates) of a Response."""
    i, found = 0, []
    while i < len(response):
        key, i = varint(response, i)
        field, wire = key >> 3, key & 7
        if wire == 0:
            _, i = varint(response, i)
        elif wire == 2:
            n, i = varint(response, i)
            if field == 5:
                found.append(response[i:i + n])
            i += n
        else:
            raise ValueError(f"unexpected wire type {wire}")
    return found


hb = next(q for q in golden["queries"] if q["name"] == "hb")
# the golden request plus Types = ["wifi"] (field 4, appended: still a valid message)
request = base64.b64decode(hb["requestB64"]) + b"\x22\x04wifi"
response = get("/v1/query?c", request)
certs = certificates(response)
if not certs:
    sys.exit("the hb answer has no WiFi certificate")

save("public-key.der", base64.b64decode(golden["publicKeyB64"]))
save("request.bin", request)
save("response.bin", response)
save("cert0.json", certs[0])
save("smh-standalone.bin", get("/v1/get-smh"))
save("sch-standalone.bin", get("/v1/get-sch"))
print(f"fixtures: {len(response)} byte answer, {len(certs)} certificate(s)")
