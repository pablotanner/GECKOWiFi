"""UDP relay from the laptop's ICS address to FreeRADIUS in WSL2.

Windows 10 has no WSL mirrored networking and `netsh portproxy` is TCP only,
so RADIUS (UDP) from the routers needs this (docs/eduroam-decisions.md, D4).
Stdlib only. Each client (address, port) gets its own upstream socket, so
replies find their way back. Packets from addresses not in --allow are
dropped: FreeRADIUS sees every packet as coming from the WSL gateway, so this
relay is where the source check happens.

    python lab/eduroam/udp-relay.py                  # defaults below
    python lab/eduroam/udp-relay.py --ports 1812 1814 1816 --allow 192.168.137.50

The WSL address is looked up at start (`wsl hostname -I`); restart the relay
after WSL restarts.
"""
import argparse
import asyncio
import subprocess
import sys
import time

LISTEN = "192.168.137.1"
# Router A's WAN. Router B sits behind A, so B's RADIUS traffic arrives NATed
# from A's WAN address too.
ALLOW = ["192.168.137.50", "127.0.0.1"]
PORTS = [1812, 1814, 1816]  # legit, attacker B1, attacker B2 (auth only)
IDLE_SECONDS = 120


def wsl_ip() -> str:
    out = subprocess.run(["wsl", "hostname", "-I"], capture_output=True, text=True, check=True).stdout
    return out.split()[0]


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S"), msg, flush=True)


class Upstream(asyncio.DatagramProtocol):
    """Socket towards WSL for one client; replies are sent back to that client."""

    def __init__(self, downstream: asyncio.DatagramTransport, client):
        self.downstream, self.client = downstream, client
        self.transport = None
        self.last = time.monotonic()

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        self.last = time.monotonic()
        self.downstream.sendto(data, self.client)


class Listener(asyncio.DatagramProtocol):
    def __init__(self, target, allow):
        self.target, self.allow = target, set(allow)
        self.upstreams = {}
        self.transport = None

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, client):
        if client[0] not in self.allow:
            log(f"drop {len(data)} B from {client[0]}:{client[1]} (not allowed) -> :{self.target[1]}")
            return
        up = self.upstreams.get(client)
        if up is None or up.transport is None or up.transport.is_closing():
            asyncio.ensure_future(self._open(client, data))
            return
        up.last = time.monotonic()
        up.transport.sendto(data)

    async def _open(self, client, first):
        loop = asyncio.get_running_loop()
        _, up = await loop.create_datagram_endpoint(lambda: Upstream(self.transport, client), remote_addr=self.target)
        self.upstreams[client] = up
        log(f"{client[0]}:{client[1]} -> {self.target[0]}:{self.target[1]}")
        up.transport.sendto(first)

    def reap(self):
        now = time.monotonic()
        for client, up in list(self.upstreams.items()):
            if now - up.last > IDLE_SECONDS:
                up.transport.close()
                del self.upstreams[client]


async def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--listen", default=LISTEN)
    p.add_argument("--ports", type=int, nargs="+", default=PORTS)
    p.add_argument("--allow", nargs="+", default=ALLOW)
    p.add_argument("--target", help="WSL address (default: wsl hostname -I)")
    args = p.parse_args()

    target_ip = args.target or wsl_ip()
    loop = asyncio.get_running_loop()
    listeners = []
    for port in args.ports:
        _, listener = await loop.create_datagram_endpoint(
            lambda port=port: Listener((target_ip, port), args.allow), local_addr=(args.listen, port))
        listeners.append(listener)
    log(f"relaying udp {args.listen}:{args.ports} -> {target_ip}, allowed sources {args.allow}")
    while True:
        await asyncio.sleep(30)
        for listener in listeners:
            listener.reap()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
