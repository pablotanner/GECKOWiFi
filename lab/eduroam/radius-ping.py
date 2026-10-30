"""Minimal RADIUS reachability check (stdlib only), for the relay path.

Sends one Access-Request (PAP, with Message-Authenticator) and checks that an
answer with a valid Response Authenticator comes back, i.e. the packet
reached a FreeRADIUS instance that knows the shared secret. Any answer
(usually Access-Reject: the demo users only exist inside the EAP tunnel)
proves the path; silence means the relay, the firewall or the secret is wrong.

    python lab/eduroam/radius-ping.py 192.168.137.1 1812 <secret>
"""
import hashlib
import hmac
import os
import socket
import struct
import sys


def attr(t: int, value: bytes) -> bytes:
    return struct.pack("BB", t, len(value) + 2) + value


def main(host: str, port: int, secret: bytes) -> int:
    ident, req_auth = os.urandom(1)[0], os.urandom(16)
    # PAP password hiding (RFC 2865, 5.2), one 16-byte block is enough for "ping".
    pw = b"ping".ljust(16, b"\0")
    hidden = bytes(a ^ b for a, b in zip(pw, hashlib.md5(secret + req_auth).digest()))
    attrs = attr(1, b"radius-ping@gecko.ch") + attr(2, hidden) + attr(80, b"\0" * 16)
    packet = bytearray(struct.pack("!BBH", 1, ident, 20 + len(attrs)) + req_auth + attrs)
    packet[-16:] = hmac.new(secret, bytes(packet), hashlib.md5).digest()

    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.settimeout(5)
        s.sendto(bytes(packet), (host, port))
        try:
            reply, _ = s.recvfrom(4096)
        except socket.timeout:
            print(f"no answer from {host}:{port} (relay down, firewall, or wrong secret)")
            return 1
    code, rid, length = struct.unpack("!BBH", reply[:4])
    expected = hashlib.md5(reply[:4] + req_auth + reply[20:length] + secret).digest()
    if rid != ident or reply[4:20] != expected:
        print(f"answer from {host}:{port} doesn't authenticate with this secret")
        return 1
    print(f"{host}:{port} answered {({2: 'Access-Accept', 3: 'Access-Reject', 11: 'Access-Challenge'}).get(code, code)}: path and secret OK")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1], int(sys.argv[2]), sys.argv[3].encode()))
