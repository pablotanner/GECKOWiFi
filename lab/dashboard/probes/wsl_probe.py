#!/usr/bin/env python3
"""Runs inside WSL and prints one JSON object: addresses, the map server,
FreeRADIUS instances and what they depend on. Read-only, no sudo.

Called by lab/dashboard/dashboard.py as `wsl python3 <this file> <geopki dir>`.
"""
import ipaddress
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

GEOPKI = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/Thesis/geopki-ext")
RADIUS = os.path.expanduser("~/gecko-radius")
INSTANCES = ["legit", "attacker-b1", "attacker-b2"]
TICK = os.sysconf("SC_CLK_TCK")


def run(*args):
    try:
        return subprocess.run(args, capture_output=True, text=True, timeout=4).stdout
    except Exception:
        return ""


def tcp(port, host="127.0.0.1"):
    try:
        with socket.create_connection((host, port), timeout=1.5):
            return True
    except OSError:
        return False


def http_status(url):
    try:
        with urllib.request.urlopen(url, timeout=3) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return None


def uptime():
    with open("/proc/uptime") as f:
        return float(f.read().split()[0])


def processes():
    up = uptime()
    for pid in filter(str.isdigit, os.listdir("/proc")):
        try:
            with open(f"/proc/{pid}/cmdline", "rb") as f:
                argv = [a.decode(errors="replace") for a in f.read().split(b"\0") if a]
            with open(f"/proc/{pid}/stat") as f:
                start = int(f.read().rsplit(")", 1)[1].split()[19])
        except OSError:
            continue
        if argv:
            yield {"pid": int(pid), "argv": argv, "age_s": round(up - start / TICK)}


def environ_value(pid, key):
    try:
        with open(f"/proc/{pid}/environ", "rb") as f:
            for entry in f.read().split(b"\0"):
                k, _, v = entry.decode(errors="replace").partition("=")
                if k == key:
                    return v
    except OSError:
        pass
    return None


def envrc_value(key):
    try:
        with open(os.path.join(GEOPKI, ".envrc")) as f:
            m = re.search(rf"^\s*(?:export\s+)?{key}=['\"]?([^'\"\s]+)", f.read(), re.M)
            return m.group(1) if m else None
    except OSError:
        return None


def flag(argv, name):
    for i, a in enumerate(argv):
        if a.startswith(f"--{name}="):
            return a.split("=", 1)[1]
        if a == f"--{name}" and i + 1 < len(argv):
            return argv[i + 1]
    return None


def database_name(url):
    # postgresql://user:pass@host:port/name -> name (never the credentials)
    return url.rsplit("/", 1)[-1].split("?")[0] if url else None


def newest_log(instance):
    d = os.path.join(RADIUS, "logs")
    try:
        logs = [os.path.join(d, f) for f in os.listdir(d) if f.startswith(instance + "-") and f.endswith(".log")]
    except OSError:
        return None
    return max(logs, key=os.path.getmtime) if logs else None


def tail(path, lines=400):
    try:
        with open(path, "rb") as f:
            f.seek(0, 2)
            f.seek(max(0, f.tell() - 200_000))
            return f.read().decode(errors="replace").splitlines()[-lines:]
    except OSError:
        return []


def radius_client_range(instance):
    try:
        with open(os.path.join(RADIUS, instance, "clients.conf")) as f:
            m = re.search(r"client\s+wsl-relay\s*\{[^}]*?ipaddr\s*=\s*(\S+)", f.read(), re.S)
            return m.group(1) if m else None
    except OSError:
        return None


def main():
    route = run("ip", "route", "show", "default").split()
    gateway = route[2] if len(route) > 2 and route[0] == "default" else None
    procs = list(processes())

    # go run leaves a parent `go run ./cmd/geopki-server ...` and the built binary; report the binary.
    servers = [p for p in procs if os.path.basename(p["argv"][0]) == "geopki-server"]
    map_servers = []
    for p in servers:
        cwd = None
        try:
            cwd = os.readlink(f"/proc/{p['pid']}/cwd")
        except OSError:
            pass
        map_servers.append({
            "pid": p["pid"], "age_s": p["age_s"], "cwd": cwd,
            "port": flag(p["argv"], "port"), "clog_id": flag(p["argv"], "clog-id"),
            "database": database_name(environ_value(p["pid"], "DATABASE_URL")),
        })

    radius = {}
    for inst in INSTANCES:
        running = [p for p in procs if os.path.basename(p["argv"][0]) == "freeradius"
                   and any(a.rstrip("/").endswith("/gecko-radius/" + inst) for a in p["argv"])]
        rng = radius_client_range(inst)
        in_range = None
        if rng and gateway:
            try:
                in_range = ipaddress.ip_address(gateway) in ipaddress.ip_network(rng, strict=False)
            except ValueError:
                in_range = False
        log = newest_log(inst)
        lines = tail(log) if log else []
        last_unknown = next((l for l in reversed(lines) if "unknown client" in l), None)
        radius[inst] = {
            "installed": os.path.isdir(os.path.join(RADIUS, inst)),
            "pids": [p["pid"] for p in running],
            "age_s": running[0]["age_s"] if running else None,
            "client_range": rng, "gateway_in_range": in_range,
            "log": log, "log_age_s": round(time.time() - os.path.getmtime(log)) if log else None,
            "requests": sum("Received Access-Request" in l for l in lines),
            "accepts": sum("Sent Access-Accept" in l for l in lines),
            "rejects": sum("Sent Access-Reject" in l for l in lines),
            "unknown_client": sum("unknown client" in l for l in lines),
            "last_unknown_client": last_unknown,
        }

    print(json.dumps({
        "ips": run("hostname", "-I").split(),
        "gateway": gateway,
        "uptime_s": round(uptime()),
        "map_servers": map_servers,
        "map_http_local": http_status("http://127.0.0.1:1234/"),
        "envrc_clog_id": envrc_value("CLOG_ID"),
        "geopki_dir": GEOPKI,
        "geopki_dir_exists": os.path.isdir(GEOPKI),
        "trillian": tcp(8090),
        "postgres": tcp(5432),
        "radius": radius,
    }))


if __name__ == "__main__":
    main()
