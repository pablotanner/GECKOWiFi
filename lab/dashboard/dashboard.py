#!/usr/bin/env python3
"""GECKO lab dashboard: what runs, who reaches whom, which addresses are
where, and the fixes for what usually breaks after a laptop restart.

    python lab\\dashboard\\dashboard.py          then open http://127.0.0.1:8765

Standard library only. Listens on 127.0.0.1 only. Checks are read-only; the
actions are a fixed list (start/stop the lab's servers, the admin fixes from
the README cheat sheet, a few router and tablet commands). See README.md.
"""
import concurrent.futures
import ipaddress
import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
WEB = HERE / "web"
LOGS = HERE / "logs"
PORT = int(os.environ.get("LAB_DASHBOARD_PORT", "8765"))
REFRESH_S = 20

# What the lab looks like when it works (docs/lab-setup.md, docs/eduroam-demo.md).
LAB_ADAPTER = "Ethernet 4"
UPLINK_ADAPTER = "WLAN"
LAPTOP_IP = "192.168.137.1"
GENUINE_IP = "192.168.137.10"
ATTACKER_IP = "192.168.137.20"
ROUTER_A = {"ssh": "mango-a", "wan": "192.168.137.50", "gateway": LAPTOP_IP, "lan": "192.168.8.1", "fas": GENUINE_IP}
ROUTER_B = {"ssh": "mango-b", "wan": "192.168.8.115", "gateway": "192.168.8.1", "lan": "192.168.247.1", "fas": ATTACKER_IP}
GEOPKI_DIR = "~/Thesis/geopki-typed"  # map version 2 (typed entries) since 2026-10-27
GEOPKI_DB = "geopki_ext"
RELAY_PORTS = (1812, 1814, 1816)
RADIUS_PORT = {"legit": 1812, "attacker-b1": 1814, "attacker-b2": 1816}
FIREWALL_RULES = ["GeoPKI 1234", "GECKO portal 80/443", "GECKO eduroam RADIUS relay", "GECKO eduroam realm server"]
APP_ID = "com.thesis.geckowifi"
# Last three octets of the BSSIDs (README: 94:.. GeckoTest, 96:.. eduroam-lab)
AP_BY_SUFFIX = {"97:c2:3a": "routerA", "97:c2:47": "routerB"}
LAB_LOCATION = "47.37643,8.54674"
# Portal experiments (docs/experiments.md): the portal pair each scenario runs, as
# lab/scenarios/scenario.ps1 prints it. None = leave that portal as it is.
SCENARIO_SETUP = {
    "S0": ("genuine", "scenarios/s0-relay", "B relays A's whole portal unchanged"),
    "S1": ("genuine", "scenarios/s1-clone", "Copied portal: same domain, attacker's key"),
    "S2": ("genuine", "scenarios/s2-lookalike", "Lookalike domain on the same SSID"),
    "S3": ("scenarios/genuine-bounce", "scenarios/s3-redirect", "Genuine portal first, then a redirect to the attacker"),
    "S3m": ("scenarios/genuine-bounce", "scenarios/s3m-meta", "As S3, with a delayed meta refresh"),
    "S3h": ("scenarios/genuine-bounce", "scenarios/s3h-header", "As S3, with a Refresh header"),
    "S3j": ("scenarios/genuine-bounce", "scenarios/s3j-js", "As S3, with a JavaScript redirect"),
    "S4a": ("scenarios/genuine-pay-redirect", "scenarios/s4a-payment", "Payment page with the attacker's key"),
    "S5a": ("genuine", "scenarios/s5a-timer", "B switches the client to the attacker after 10 s"),
    "S5b": ("genuine", "scenarios/s5b-ua", "B shows the genuine chain only to the probe"),
    "S5c": ("genuine", "scenarios/s5c-after-accept", "B switches only after the user taps Accept"),
    "S6": ("genuine", "scenarios/s6-http-only", "Login page over plain HTTP only"),
    "S7": ("genuine", "scenarios/s1-clone", "B blocks the map server"),
    "N6": (None, None, "B broadcasts an unregistered SSID (GeckoOther)"),
}
SCENARIOS = list(SCENARIO_SETUP)

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
NEW_GROUP = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
BREAKAWAY = 0x01000000  # CREATE_BREAKAWAY_FROM_JOB: survive the terminal that started the dashboard


# --------------------------------------------------------------------------- helpers

def run(args, timeout=10, input=None, cwd=None):
    """(returncode, combined output); returncode None on timeout or a missing program."""
    try:
        p = subprocess.run(args, capture_output=True, timeout=timeout, input=input.encode() if input else None,
                           cwd=cwd, creationflags=NO_WINDOW)
        out = (p.stdout + p.stderr).decode("utf-8", errors="replace").lstrip("\ufeff")
        return p.returncode, out.replace("\r\n", "\n")
    except subprocess.TimeoutExpired:
        return None, f"timed out after {timeout} s"
    except OSError as e:
        return None, str(e)


def wsl_path(p):
    p = str(Path(p).resolve())
    return "/mnt/" + p[0].lower() + p[2:].replace("\\", "/")


def tcp_open(host, port, timeout=2.0):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def http_status(url, timeout=3):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return None


def ping(host):
    rc, out = run(["ping", "-n", "1", "-w", "1500", host], timeout=5)
    m = re.search(r"(?:time|Zeit)[=<](\d+)", out)
    return {"ok": "TTL=" in out.upper(), "ms": int(m.group(1)) if m else None}


def parse_time(s):
    if not s:
        return None
    try:
        return datetime.fromisoformat(re.sub(r"\.\d+", "", s).replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def ago(seconds):
    if seconds is None:
        return "?"
    seconds = int(seconds)
    if seconds < 90:
        return f"{seconds} s"
    if seconds < 5400:
        return f"{seconds // 60} min"
    if seconds < 172800:
        return f"{seconds // 3600} h"
    return f"{seconds // 86400} days"


def tail_file(path, lines=120):
    try:
        with open(path, "rb") as f:
            f.seek(0, 2)
            f.seek(max(0, f.tell() - 120_000))
            return "\n".join(f.read().decode("utf-8", errors="replace").replace("\r\n", "\n").splitlines()[-lines:])
    except OSError:
        return None


def find_adb():
    adb = shutil.which("adb")
    if adb:
        return adb
    try:
        sdk = re.search(r"sdk\.dir=(.*)", (REPO / "local.properties").read_text()).group(1)
        cand = Path(sdk.replace("\\:", ":").replace("\\\\", "\\")) / "platform-tools" / "adb.exe"
        return str(cand) if cand.exists() else None
    except Exception:
        return None


def find_go():
    return shutil.which("go") or (r"C:\Program Files\Go\bin\go.exe" if Path(r"C:\Program Files\Go\bin\go.exe").exists() else None)


def portal_configs():
    """Every portal config: id (path below portal/config without .json) -> its listen IP and role."""
    out = {}
    base = REPO / "portal" / "config"
    for f in sorted(base.rglob("*.json")):
        try:
            d = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            continue
        cid = f.relative_to(base).with_suffix("").as_posix()
        out[cid] = {"listen_ip": d.get("listen_ip"), "role": d.get("role"), "path": f}
    return out


def config_id(command):
    m = re.search(r"-config\s+\"?([^\"\s]+)", command or "")
    if not m:
        return None
    p = m.group(1).replace("\\", "/")
    p = re.sub(r"^(\./)?config/", "", p)
    return p[:-5] if p.endswith(".json") else p


# --------------------------------------------------------------------------- probes

def probe_laptop():
    rc, out = run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(HERE / "probes" / "laptop_probe.ps1"),
                   "-LabAdapter", LAB_ADAPTER, "-UplinkAdapter", UPLINK_ADAPTER], timeout=30)
    return json.loads(out[out.index("{"):])


def probe_netstat():
    rc, out = run(["netstat", "-ano"], timeout=10)
    tcp, udp = {}, {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 4 and parts[0] == "TCP" and parts[2] in ("0.0.0.0:0", "[::]:0"):
            tcp[parts[1]] = int(parts[-1])
        elif len(parts) >= 4 and parts[0] == "UDP":
            udp[parts[1]] = int(parts[-1])
    return {"tcp": tcp, "udp": udp}


def probe_portproxy():
    rc, out = run(["netsh", "interface", "portproxy", "show", "v4tov4"], timeout=8)
    rules = []
    for line in out.splitlines():
        p = line.split()
        if len(p) == 4 and p[1].isdigit() and p[3].isdigit():
            rules.append({"listen": p[0], "listen_port": int(p[1]), "connect": p[2], "connect_port": int(p[3])})
    return rules


def probe_firewall():
    found = {}
    for name in FIREWALL_RULES:
        rc, out = run(["netsh", "advfirewall", "firewall", "show", "rule", f"name={name}"], timeout=8)
        found[name] = name in out and "----" in out
    return found


def probe_wsl():
    rc, out = run(["wsl", "python3", wsl_path(HERE / "probes" / "wsl_probe.py"), GEOPKI_DIR], timeout=15)
    if rc is None:
        return {"_error": "WSL did not answer: " + out}
    try:
        return json.loads(out[out.index("{"):])
    except ValueError:
        return {"_error": "WSL probe failed: " + out[-400:]}


ROUTER_SCRIPT = (HERE / "probes" / "router_probe.sh").read_text(encoding="utf-8").replace("\r\n", "\n")


def probe_router(host):
    last = ""
    for attempt in range(2):  # the link to A has been flaky: one retry
        rc, out = run(["ssh", "-o", "ConnectTimeout=5", "-o", "BatchMode=yes", "-o", "ServerAliveInterval=3",
                       host, "sh -s"], timeout=25, input=ROUTER_SCRIPT)
        if rc == 0 and "now=" in out:
            return parse_router(out)
        last = out.strip().splitlines()[-1] if out.strip() else f"ssh exited {rc}"
        time.sleep(1)
    return {"_error": f"ssh {host}: {last}"}


def parse_router(out):
    d = {"uci": {}, "ifaces": {}, "stations": {}, "nds_clients": [], "dns_overrides": [], "leases": [], "link_down": []}
    for line in out.splitlines():
        if line.startswith("uci:wireless."):
            m = re.match(r"uci:wireless\.([^.]+)\.([^=]+)='?(.*?)'?$", line)
            if m:
                d["uci"].setdefault(m.group(1), {})[m.group(2)] = m.group(3)
            continue
        k, _, v = line.partition("=")
        if k == "iface":
            name, _, ssid = v.partition(",")
            d["ifaces"][name] = ssid
        elif k == "stations":
            name, _, n = v.partition(",")
            d["stations"][name] = int(n or 0)
        elif k == "nds_client":
            mac, ip, state = (v.split(",") + ["", "", ""])[:3]
            d["nds_clients"].append({"mac": mac, "ip": ip, "state": state})
        elif k == "dns_override":
            d["dns_overrides"].append(v)
        elif k == "lease":
            mac, ip, name = (v.split(",") + ["", "", ""])[:3]
            d["leases"].append({"mac": mac, "ip": ip, "name": name})
        elif k == "link_down":
            port, _, t = v.partition(",")
            d["link_down"].append((int(port), int(t)))
        elif k:
            d[k] = v
    for k in ("now", "uptime", "map_rc", "realm_rc", "garden_allowed", "garden_rejected"):
        if str(d.get(k, "")).isdigit():
            d[k] = int(d[k])
    return d


def probe_tablet():
    adb = find_adb()
    if not adb:
        return {"_error": "adb not found"}
    rc, out = run([adb, "devices"], timeout=8)
    rows = [l.split() for l in out.splitlines()[1:] if l.strip() and not l.startswith("*")]
    ready = [r[0] for r in rows if len(r) > 1 and r[1] == "device"]
    if not ready:
        return {"adb": adb, "devices": rows, "serial": None}
    s = ready[0]

    def shell(cmd, timeout=10):
        return run([adb, "-s", s, "shell", cmd], timeout=timeout)[1]

    d = {"adb": adb, "devices": rows, "serial": s}
    w = shell("dumpsys wifi | grep -m1 mWifiInfo")
    m = re.search(r'SSID: "?([^",]*)"?, BSSID: ([0-9a-fA-F:]+)', w)
    d["ssid"], d["bssid"] = (m.group(1), m.group(2).lower()) if m else (None, None)
    m = re.search(r"IP: /?([\d.]+)", w)
    d["ip"] = m.group(1) if m else None
    m = re.search(r"Supplicant state: (\w+)", w)
    d["supplicant"] = m.group(1) if m else None
    d["wifi_on"] = "enabled" in shell("cmd wifi status | head -1").lower() or bool(d["ssid"])
    p = shell(f"dumpsys package {APP_ID} | grep -E 'versionName|lastUpdateTime'")
    m = re.search(r"versionName=(\S+)", p)
    d["app_version"] = m.group(1) if m else None
    m = re.search(r"lastUpdateTime=([\d-]+ [\d:]+)", p)
    d["app_updated"] = m.group(1) if m else None
    # Saved networks (a manually saved eduroam-lab wins over the app's profile)
    saved = []
    for line in shell("cmd wifi list-networks").splitlines()[1:]:
        m = re.match(r"\s*(\d+)\s+(.*?)\s{2,}(\S+)\s*$", line)
        if m:
            saved.append({"id": m.group(1), "ssid": m.group(2).strip(), "security": m.group(3)})
    d["saved"] = saved
    # The shell has no network while the tablet only sits on an app-only network.
    pg = shell("ping -c1 -W2 1.1.1.1")
    d["shell_internet"] = " 0% packet loss" in pg
    d["shell_no_route"] = "unreachable" in pg.lower()
    lc = run([adb, "-s", s, "logcat", "-d", "-v", "time", "-s", "PortalCheckLog:I", "EnterpriseFlow:I",
              "OnboardingSession:I", "GeckoClient:W"], timeout=10)[1]
    d["last_logs"] = [l for l in lc.splitlines() if l and not l.startswith("-----")][-6:]
    return d


def probe_realm_records():
    out = []
    for spec in sorted((REPO / "lab" / "realm" / "records").glob("*/*.json")):
        try:
            s = json.loads(spec.read_text(encoding="utf-8"))
        except Exception:
            continue
        signed = REPO / "lab-secrets" / "realm" / "signed" / s["federation"] / (s["realm"] + ".bin")
        issued = signed.stat().st_mtime if signed.exists() else None
        out.append({"federation": s["federation"], "realm": s["realm"], "signed": signed.exists(),
                    "expires": issued + s.get("valid_days", 0) * 86400 if issued else None})
    return out


def collect():
    t0 = time.time()
    jobs = {
        "laptop": probe_laptop, "netstat": probe_netstat, "portproxy": probe_portproxy, "firewall": probe_firewall,
        "wsl": probe_wsl, "routerA": lambda: probe_router(ROUTER_A["ssh"]), "routerB": lambda: probe_router(ROUTER_B["ssh"]),
        "tablet": probe_tablet, "realm_records": probe_realm_records,
        "ping_a": lambda: ping(ROUTER_A["wan"]),
        "internet": lambda: tcp_open("1.1.1.1", 443, 3),
        "dns": lambda: bool(socket.getaddrinfo("example.com", 443)),
        "http_map": lambda: http_status(f"http://{LAPTOP_IP}:1234/"),
        "http_realm": lambda: http_status(f"http://{LAPTOP_IP}:1235/v1/realm/eduroam-lab/uni-wipkingen.ch"),
        "portal_tcp": lambda: {f"{ip}:{p}": tcp_open(ip, p, 1.5) for ip in (GENUINE_IP, ATTACKER_IP) for p in (80, 443)},
    }
    raw = {}
    with concurrent.futures.ThreadPoolExecutor(len(jobs)) as ex:
        futures = {ex.submit(fn): name for name, fn in jobs.items()}
        for f in concurrent.futures.as_completed(futures):
            name = futures[f]
            try:
                raw[name] = f.result()
            except Exception as e:
                raw[name] = {"_error": f"{type(e).__name__}: {e}"}
    wsl_ips = (raw.get("wsl") or {}).get("ips") or []
    raw["http_map_direct"] = http_status(f"http://{wsl_ips[0]}:1234/") if wsl_ips else None
    raw["_time"] = time.time()
    raw["_duration"] = round(time.time() - t0, 1)
    return raw


# --------------------------------------------------------------------------- evaluation

ORDER = {"fail": 0, "warn": 1, "blocked": 2, "unknown": 3, "off": 4, "ok": 5}


def act(action, label, kind="secondary", arg=None, confirm=None, admin=False, sudo=False):
    return {"action": action, "arg": arg, "label": label, "kind": kind, "confirm": confirm, "admin": admin, "sudo": sudo}


ADMIN = lambda task, label: act("admin", label, "primary", arg=task, admin=True)


class Report:
    def __init__(self, raw):
        self.raw = raw
        self.now = raw["_time"]
        self.checks = {}
        self.services = []
        self.addresses = []

    def check(self, cid, area, title, status, summary, detail=None, actions=(), needs=()):
        self.checks[cid] = {"id": cid, "area": area, "title": title, "status": status, "summary": summary,
                            "detail": detail, "actions": list(actions), "needs": list(needs)}
        return self.checks[cid]

    def ok(self, cid):
        c = self.checks.get(cid)
        return bool(c) and c["status"] == "ok"

    def status(self, cid):
        c = self.checks.get(cid)
        return c["status"] if c else "unknown"

    def addr(self, where, what, expected, actual, status=None):
        if status is None:
            status = "ok" if expected == actual else ("unknown" if actual in (None, "") else "fail")
        self.addresses.append({"where": where, "what": what, "expected": expected, "actual": actual or "none", "status": status})

    def resolve_dependencies(self):
        """A check whose prerequisite failed says so instead of counting as its own problem."""
        for c in self.checks.values():
            if c["status"] not in ("fail", "warn", "unknown"):
                continue
            broken = [self.checks[n] for n in c["needs"] if n in self.checks and self.checks[n]["status"] in ("fail", "blocked")]
            if broken:
                c["status"] = "blocked"
                c["summary"] = f"Waiting on: {broken[0]['title']}. " + c["summary"]


def evaluate(raw, state):
    r = Report(raw)
    lap = raw.get("laptop") or {}
    lab = lap.get("lab") or {}
    up = lap.get("uplink") or {}
    ns = raw.get("netstat") or {"tcp": {}, "udp": {}}
    w = raw.get("wsl") or {}
    wsl_ok = "_error" not in w and bool(w)
    wsl_ip = (w.get("ips") or [None])[0]
    A = raw.get("routerA") or {}
    B = raw.get("routerB") or {}
    T = raw.get("tablet") or {}
    procs = lap.get("processes") or []

    def procs_where(pred):
        return [p for p in procs if pred(p.get("name") or "", p.get("command") or "")]

    # ---------------- laptop
    lab_ips = [a.split("/")[0] for a in lab.get("ipv4") or []]
    if not lab:
        r.check("lab_link", "Laptop", f"Cable to Router A ({LAB_ADAPTER})", "fail",
                f"Adapter '{LAB_ADAPTER}' not found: the USB Ethernet adapter is unplugged or was renamed.")
    elif lab.get("status") != "Up":
        r.check("lab_link", "Laptop", f"Cable to Router A ({LAB_ADAPTER})", "fail",
                f"{LAB_ADAPTER} is {lab.get('status')}: no link to Router A right now. The USB adapter drops the link now and then; "
                "if it stays down, check the cable and that Router A is powered.")
    else:
        r.check("lab_link", "Laptop", f"Cable to Router A ({LAB_ADAPTER})", "ok", "Link up.")

    # Known and not fixable here: the USB adapter drops the link for a second or two now and then.
    # Detect it (Router A's switch log, and the adapter seen down by a poll) so a failed SSH or check makes sense.
    drops = [t for port, t in A.get("link_down", []) if port == 0 and A.get("uptime", 0) - t < 900]
    seen_down = state.adapter_down_polls(r.now, 900)
    if drops or seen_down:
        last = f", last {ago(A['uptime'] - drops[-1])} ago" if drops else ""
        r.check("lab_link_stable", "Laptop", "Cable link stays up", "warn",
                f"The laptop's link to Router A dropped {len(drops) or seen_down} times in the last 15 min{last}. "
                "Known USB adapter flakiness: it comes back on its own within seconds.",
                detail="While it is down, SSH fails (\"Connection aborted\"), and requests from the routers and the tablet to the laptop time out. "
                       "Retry instead of debugging.")
    elif "_error" not in A and A:
        r.check("lab_link_stable", "Laptop", "Cable link stays up", "ok", "No link drops in the last 15 min.")

    if not lab_ips:
        r.check("lab_ip", "Laptop", f"Laptop address {LAPTOP_IP}", "fail",
                f"{LAB_ADAPTER} has no IPv4 address. Internet sharing dropped it.",
                actions=[ADMIN("ips", "Re-add lab addresses"), ADMIN("ics", "Reset internet sharing")], needs=["lab_link"])
    elif LAPTOP_IP not in lab_ips:
        apipa = any(a.startswith("169.254.") for a in lab_ips)
        r.check("lab_ip", "Laptop", f"Laptop address {LAPTOP_IP}", "fail",
                f"{LAPTOP_IP} is missing on {LAB_ADAPTER}" + (" (it fell back to 169.254.x.x)" if apipa else "")
                + ". Routers, map server, relay and realm server all depend on it.",
                actions=[ADMIN("ips", "Re-add lab addresses"), ADMIN("ics", "Reset internet sharing")], needs=["lab_link"])
    else:
        r.check("lab_ip", "Laptop", f"Laptop address {LAPTOP_IP}", "ok", f"Present on {LAB_ADAPTER}.")
    missing_portal = [ip for ip in (GENUINE_IP, ATTACKER_IP) if ip not in lab_ips]
    if missing_portal:
        r.check("portal_ips", "Laptop", "Portal addresses .10 and .20", "warn",
                f"Missing on {LAB_ADAPTER}: {', '.join(missing_portal)}. The portal server can't bind (\"bind: The requested address is not valid\"). "
                "Internet sharing removes them when it is reset.",
                actions=[ADMIN("ips", "Re-add lab addresses")], needs=["lab_link"])
    else:
        r.check("portal_ips", "Laptop", "Portal addresses .10 and .20", "ok", "Both present.")

    if up.get("status") != "Up":
        r.check("uplink", "Laptop", f"Laptop internet ({UPLINK_ADAPTER})", "fail", f"{UPLINK_ADAPTER} is {up.get('status') or 'missing'}.")
    elif not raw.get("internet"):
        r.check("uplink", "Laptop", f"Laptop internet ({UPLINK_ADAPTER})", "fail",
                f"Connected to {up.get('ssid')}, but 1.1.1.1 is unreachable (login page, or a network that blocks it).")
    else:
        r.check("uplink", "Laptop", f"Laptop internet ({UPLINK_ADAPTER})", "ok", f"Online via {up.get('ssid')}.")

    if (lap.get("services") or {}).get("SharedAccess") != "Running":
        r.check("ics", "Laptop", "Internet sharing (ICS) service", "fail",
                f"The ICS service is {(lap.get('services') or {}).get('SharedAccess') or 'unknown'}.",
                actions=[ADMIN("ics", "Reset internet sharing")])
    else:
        r.check("ics", "Laptop", "Internet sharing (ICS) service", "ok", "Running. Whether it forwards shows in 'Router A has internet'.")

    fw = raw.get("firewall") or {}
    missing_fw = [n for n, ok in fw.items() if not ok]
    r.check("firewall", "Laptop", "Firewall rules", "warn" if missing_fw else "ok",
            ("Missing: " + ", ".join(missing_fw) + ". Routers can't reach these ports.") if missing_fw else "All lab rules present.",
            actions=[ADMIN("firewall", "Add firewall rules")] if missing_fw else [])

    # ---------------- WSL
    if not wsl_ok:
        r.check("wsl", "WSL", "WSL responds", "fail",
                (w.get("_error") or "No answer.") + " After sleep WSL sometimes hangs (Wsl/Service/WSAETIMEDOUT).",
                actions=[act("wsl.shutdown", "Restart WSL", "destructive",
                             confirm="This stops everything in WSL (map server, RADIUS). Start them again afterwards.")])
    else:
        r.check("wsl", "WSL", "WSL responds", "ok", f"Address {wsl_ip}, up {ago(w.get('uptime_s'))}.")
    wsl_boot = r.now - w["uptime_s"] if wsl_ok else None

    rules = [p for p in raw.get("portproxy") or [] if p["listen_port"] == 1234]
    if not rules:
        r.check("portproxy", "Laptop", "Port forward :1234 to WSL", "fail",
                "No portproxy for port 1234: routers and tablet can't reach the map server.",
                actions=[ADMIN("portproxy", "Point port forward at WSL")], needs=["wsl"])
    elif wsl_ip and rules[0]["connect"] != wsl_ip:
        r.check("portproxy", "Laptop", "Port forward :1234 to WSL", "fail",
                f"Forwards to {rules[0]['connect']}, but WSL is now at {wsl_ip} (WSL gets a new address on every restart).",
                actions=[ADMIN("portproxy", "Point port forward at WSL")], needs=["wsl"])
    else:
        r.check("portproxy", "Laptop", "Port forward :1234 to WSL", "ok" if wsl_ip else "unknown",
                f"0.0.0.0:1234 -> {rules[0]['connect']}:1234.", needs=["wsl"])

    docker = procs_where(lambda n, c: n == "Docker Desktop.exe")
    if wsl_ok and not w.get("trillian"):
        r.check("trillian", "WSL", "Trillian log (Docker)", "fail",
                "127.0.0.1:8090 is closed. " + ("Docker Desktop is running, its containers may still be starting." if docker
                                                 else "Docker Desktop isn't running; its Trillian containers restart on their own once it is."),
                actions=[] if docker else [act("docker.start", "Start Docker Desktop", "primary")], needs=["wsl"])
    else:
        r.check("trillian", "WSL", "Trillian log (Docker)", "ok" if wsl_ok else "unknown", "Listening on 127.0.0.1:8090.", needs=["wsl"])

    if wsl_ok and not w.get("postgres"):
        r.check("postgres", "WSL", "PostgreSQL", "fail", "127.0.0.1:5432 is closed.",
                actions=[act("postgres.start", "Start PostgreSQL", "primary", sudo=True)], needs=["wsl"])
    else:
        r.check("postgres", "WSL", "PostgreSQL", "ok" if wsl_ok else "unknown", "Listening on 5432.", needs=["wsl"])

    servers = w.get("map_servers") or []
    map_actions_start = [act("map.start", "Start map server", "primary")]
    if not wsl_ok:
        r.check("map_server", "WSL", "Map server (geopki)", "unknown", "WSL didn't answer.", needs=["wsl"])
    elif not servers:
        r.check("map_server", "WSL", "Map server (geopki)", "fail", "Not running.", actions=map_actions_start,
                needs=["wsl", "trillian", "postgres"])
    else:
        s = servers[0]
        problems = []
        if len(servers) > 1:
            problems.append(f"{len(servers)} instances are running")
        if s.get("cwd") and not s["cwd"].rstrip("/").endswith(GEOPKI_DIR.split("/")[-1]):
            problems.append(f"it runs from {s['cwd']}, an older server (geopki-ext: map version 1, geopki: flat schema v3), not {GEOPKI_DIR}")
        if w.get("envrc_clog_id") and s.get("clog_id") != w.get("envrc_clog_id"):
            problems.append(f"tree --clog-id={s.get('clog_id')}, but .envrc has {w['envrc_clog_id']} (a command from shell history?)")
        if s.get("database") and s["database"] != GEOPKI_DB:
            problems.append(f"database {s['database']} instead of {GEOPKI_DB}")
        if w.get("map_http_local") not in (200, 404):
            problems.append(f"it doesn't answer on 127.0.0.1:1234 yet (still compiling?)")
        if problems:
            r.check("map_server", "WSL", "Map server (geopki)", "fail", "Running, but " + "; ".join(problems) + ".",
                    actions=[act("map.restart", "Restart with .envrc", "primary")], needs=["wsl"])
        else:
            r.check("map_server", "WSL", "Map server (geopki)", "ok",
                    f"pid {s['pid']}, up {ago(s['age_s'])}, tree {s['clog_id']}, database {s.get('database') or '?'}.", needs=["wsl"])

    hm = raw.get("http_map")
    if hm in (200, 404):
        r.check("map_reach", "Laptop", f"Map server at {LAPTOP_IP}:1234", "ok", f"Answers ({hm}).")
    else:
        direct = raw.get("http_map_direct")
        hint = (" WSL answers directly, so the port forward is the problem." if direct in (200, 404) else "")
        r.check("map_reach", "Laptop", f"Map server at {LAPTOP_IP}:1234", "fail", "No answer." + hint,
                actions=[ADMIN("portproxy", "Point port forward at WSL")] if hint else [],
                needs=["lab_ip", "map_server", "portproxy"])

    # RADIUS (WSL)
    rad = w.get("radius") or {}
    gw = w.get("gateway")
    bad_range = [i for i, d in rad.items() if d.get("installed") and d.get("gateway_in_range") is False]
    unknown = [i for i, d in rad.items() if d.get("pids") and d.get("unknown_client") and d.get("log_age_s", 1e9) < 900]
    if bad_range or unknown:
        r.check("radius_clients", "WSL", "FreeRADIUS accepts the relay", "fail",
                f"Requests arrive from the WSL gateway {gw}, but " +
                (f"{', '.join(bad_range)} only accept {rad[bad_range[0]]['client_range']}" if bad_range else
                 f"{', '.join(unknown)} logged 'unknown client'") + ": every login times out.",
                detail=(rad.get((bad_range or unknown)[0]) or {}).get("last_unknown_client"),
                actions=[act("radius.fixclients", "Accept the WSL range", "primary", sudo=True)], needs=["wsl"])
    else:
        r.check("radius_clients", "WSL", "FreeRADIUS accepts the relay", "ok" if wsl_ok else "unknown",
                f"Gateway {gw} is inside every instance's client range.", needs=["wsl"])

    # B's mode decides which attacker instances are needed
    b_uci = B.get("uci") or {}
    b_mode = "b1" if b_uci.get("eduroam_b1", {}).get("disabled") == "0" else \
             "legit" if b_uci.get("eduroam_legit", {}).get("disabled") == "0" else "off"
    for inst, port in RADIUS_PORT.items():
        d = rad.get(inst) or {}
        name = f"FreeRADIUS {inst} (:{port})"
        needed = inst == "legit" or (inst == "attacker-b1" and b_mode == "b1")
        start = act("radius.start", "Start", "primary" if needed else "secondary", arg=inst, sudo=True)
        if not wsl_ok:
            st, summ, acts = "unknown", "WSL didn't answer.", []
        elif not d.get("installed"):
            st, summ, acts = "fail" if needed else "off", "Instance not set up (setup-instances.sh).", []
        elif d.get("pids"):
            st = "ok"
            summ = (f"pid {d['pids'][0]}, up {ago(d.get('age_s'))}. Recent log: {d.get('requests', 0)} requests, "
                    f"{d.get('accepts', 0)} accepted, {d.get('rejects', 0)} rejected.")
            acts = [act("radius.stop", "Stop", "destructive", arg=inst, sudo=True)]
        else:
            st = "fail" if needed else "off"
            summ = "Not running." + (" Router B has the B1 access point on: never without its RADIUS instance." if inst == "attacker-b1" and needed else "")
            acts = [start]
        r.check(f"radius_{inst}", "WSL", name, st, summ, actions=acts, needs=["wsl"])
        r.services.append({"id": f"radius_{inst}", "name": name, "where": "WSL", "check": f"radius_{inst}", "log": f"radius:{inst}"})

    # ---------------- Windows services
    relay = procs_where(lambda n, c: n.startswith("python") and "udp-relay.py" in c)
    relay_ports = {port: ns["udp"].get(f"{LAPTOP_IP}:{port}") for port in RELAY_PORTS}
    relay_target = state.relay_target(relay[0]["pid"]) if relay else None
    if not relay:
        r.check("relay", "Laptop", "RADIUS relay (UDP 1812/1814/1816)", "fail", "Not running: Router A's logins get no answer.",
                actions=[act("relay.start", "Start relay", "primary")], needs=["lab_ip"])
    else:
        rp = relay[0]
        started = parse_time(rp.get("started"))
        problems = []
        if len(relay) > 1:
            problems.append(f"{len(relay)} relays are running")
        if wsl_boot and started and started < wsl_boot - 5:
            problems.append(f"it started before WSL did (WSL restarted {ago(r.now - wsl_boot)} ago), so it forwards to the old WSL address")
        if relay_target and wsl_ip and relay_target != wsl_ip:
            problems.append(f"it forwards to {relay_target}, WSL is at {wsl_ip}")
        missing = [str(p) for p, pid in relay_ports.items() if pid != rp["pid"]]
        if missing:
            problems.append(f"it doesn't listen on {LAPTOP_IP}:{', '.join(missing)}")
        if problems:
            r.check("relay", "Laptop", "RADIUS relay (UDP 1812/1814/1816)", "fail", "Running, but " + "; ".join(problems) + ".",
                    actions=[act("relay.restart", "Restart relay", "primary")], needs=["lab_ip"])
        else:
            r.check("relay", "Laptop", "RADIUS relay (UDP 1812/1814/1816)", "ok",
                    f"pid {rp['pid']}, started {ago(r.now - started) if started else '?'} ago"
                    + (f", forwards to {relay_target}." if relay_target else ", started after WSL, so it forwards to the current address."),
                    actions=[act("relay.restart", "Restart", "secondary"), act("relay.stop", "Stop", "destructive")])
    r.services.append({"id": "relay", "name": "RADIUS relay", "where": "Windows", "check": "relay", "log": "relay"})

    realm = procs_where(lambda n, c: (n == "realm.exe" and " serve" in c) or (n == "go.exe" and "run . serve" in c))
    hr = raw.get("http_realm")
    if not realm:
        r.check("realm", "Laptop", "Realm server (:1235)", "fail", "Not running: visitors' realm records can't be fetched.",
                actions=[act("realm.start", "Start realm server", "primary")], needs=["lab_ip"])
    elif hr == 200:
        r.check("realm", "Laptop", "Realm server (:1235)", "ok", "Serves eduroam-lab/uni-wipkingen.ch.",
                actions=[act("realm.stop", "Stop", "destructive")])
    else:
        r.check("realm", "Laptop", "Realm server (:1235)", "fail" if hr is None else "warn",
                "Running, but " + ("doesn't answer on 192.168.137.1:1235 (still compiling, or started before .1 existed)" if hr is None
                                   else f"eduroam-lab/uni-wipkingen.ch returns {hr}") + ".",
                actions=[act("realm.restart", "Restart", "primary")], needs=["lab_ip"])
    r.services.append({"id": "realm", "name": "Realm server", "where": "Windows", "check": "realm", "log": "realm"})
    for rec in raw.get("realm_records") or []:
        left = (rec["expires"] - r.now) if rec.get("expires") else None
        if not rec["signed"]:
            r.check(f"realm_rec_{rec['federation']}", "Laptop", f"Realm record {rec['federation']}/{rec['realm']}", "warn", "Not signed yet (go run . sign).")
        elif left is not None and left < 30 * 86400:
            r.check(f"realm_rec_{rec['federation']}", "Laptop", f"Realm record {rec['federation']}/{rec['realm']}",
                    "fail" if left < 0 else "warn", ("Expired" if left < 0 else f"Expires in {ago(left)}") + ": re-sign it (docs/eduroam-demo.md, step 5).")

    # portals: one slot per address. The genuine slot (.10) is Router A's GeckoTest portal,
    # genuine.json or one of its scenario variants; the attacker slot (.20) is Router B's.
    configs = portal_configs()
    running_by_ip = {}
    for p in procs_where(lambda n, c: n == "portal.exe" and " serve" in c):
        cid = config_id(p["command"])
        running_by_ip.setdefault((configs.get(cid) or {}).get("listen_ip"), []).append(dict(p, config=cid))
    ptcp = raw.get("portal_tcp") or {}
    portals = []
    for slot, ip, router in (("genuine", GENUINE_IP, "Router A"), ("attacker", ATTACKER_IP, "Router B")):
        running = running_by_ip.get(ip, [])
        names = sorted({p["config"] for p in running})
        listening = ptcp.get(f"{ip}:443")
        if len(running) > 1:
            st, summ = "fail", f"{len(running)} portals for {ip} ({', '.join(names)}): only one can bind."
        elif running and not listening:
            st, summ = "fail", f"{names[0]} runs, but nothing listens on {ip}:443."
        elif running:
            st, summ = "ok", f"Serving {names[0].removeprefix('scenarios/')} (pid {running[0]['pid']})."
        elif slot == "genuine":
            st, summ = "warn", "Not running: Router A's GeckoTest portal can't log anyone in."
        else:
            st, summ = "off", "Not running: Router B's evil twin has no portal."
        if running:
            acts = [act("portal.stop", "Stop", "destructive", arg=n) for n in names]
        else:
            acts = [act("portal.start", f"Start {slot}", "primary" if slot == "genuine" else "secondary", arg=slot)]
        r.check(f"portal_{slot}", "Laptop", f"{slot.capitalize()} portal ({ip})", st, summ, actions=acts, needs=["portal_ips"])
        portals.append({"slot": slot, "ip": ip, "router": router, "check": f"portal_{slot}", "running": names,
                        "configs": [c for c, v in configs.items() if v["listen_ip"] == ip],
                        "log": f"portal:{names[0] if names else slot}"})
    gen = (portals[0]["running"] or [None])[0]
    atk = (portals[1]["running"] or [None])[0]
    b_ssid = ((B.get("uci") or {}).get("wifi2g") or {}).get("ssid")
    matches = [n for n, (g, a, _) in SCENARIO_SETUP.items() if g and g == gen and a == atk
               and (n == "S7") == (B.get("s7_block") == "1")]
    if b_ssid and b_ssid != "GeckoTest":
        matches = ["N6"]
    scenario = {"list": [{"id": n, "desc": d, "genuine": g, "attacker": a} for n, (g, a, d) in SCENARIO_SETUP.items()],
                "matches": matches, "baseline": gen == "genuine" and atk == "attacker" and B.get("s7_block") != "1",
                "b_fas": f"{B.get('fas_fqdn')} at {B.get('fas_ip')}" if B and "_error" not in B else None,
                "b_dns": B.get("dns_overrides") if B and "_error" not in B else None, "b_ssid": b_ssid}

    # ---------------- routers
    pa = raw.get("ping_a") or {}
    if "_error" in A:
        st = "warn" if pa.get("ok") else "fail"
        r.check("router_a_reach", "Router A", "Laptop reaches Router A", st,
                ("Ping works, but SSH fails: " if pa.get("ok") else "No ping, no SSH: ") + A["_error"]
                + (" Reset internet sharing usually fixes this." if not pa.get("ok") else ""),
                actions=[ADMIN("ics", "Reset internet sharing")], needs=["lab_link", "lab_ip"])
    else:
        r.check("router_a_reach", "Router A", "Laptop reaches Router A", "ok",
                f"Ping {pa.get('ms', '?')} ms, SSH works. Router up {ago(A.get('uptime'))}.")

    def router_checks(key, R, cfg, label):
        reach = f"router_{key}_reach"
        if "_error" in R or not R:
            return
        r.check(f"router_{key}_internet", label, f"{label} has internet", "ok" if R.get("internet") == "1" and R.get("dns") == "1" else "fail",
                "ping 1.1.1.1 and DNS work." if R.get("internet") == "1" and R.get("dns") == "1" else
                ("No internet: internet sharing stopped forwarding. Turn sharing off and on (the button does that and re-adds the addresses)."
                 if R.get("internet") != "1" else "Ping works, DNS doesn't."),
                actions=[ADMIN("ics", "Reset internet sharing")] if R.get("internet") != "1" else [], needs=[reach, "uplink"])
        mrc = R.get("map_rc")
        r.check(f"router_{key}_map", label, f"{label} reaches the map server", "ok" if mrc in (0, 8) else "fail",
                "http://192.168.137.1:1234 answers." if mrc in (0, 8) else f"No answer (wget {mrc}).", needs=[reach, "map_reach"])
        rrc = R.get("realm_rc")
        r.check(f"router_{key}_realm", label, f"{label} reaches the realm server", "ok" if rrc in (0, 8) else "fail",
                "http://192.168.137.1:1235 answers." if rrc in (0, 8) else f"No answer (wget {rrc}).", needs=[reach, "realm"])
        wan = (R.get("wan") or "").split("/")[0]
        r.addr(label, "WAN address", cfg["wan"], wan)
        r.addr(label, "Default gateway", cfg["gateway"], R.get("gateway"))
        r.addr(label, "Portal (FAS) address", cfg["fas"], R.get("fas_ip"))
        for sec, u in (R.get("uci") or {}).items():
            if u.get("auth_server"):
                r.addr(label, f"RADIUS server of {sec}", LAPTOP_IP, u["auth_server"],
                       "ok" if u["auth_server"] == LAPTOP_IP else "fail")
        nds = R.get("opennds") == "1"
        r.check(f"router_{key}_opennds", label, f"{label} openNDS (portal gate)", "ok" if nds else "fail",
                (f"Running, sends clients to {R.get('fas_fqdn')} at {R.get('fas_ip')}. "
                 + (f"Clients: " + ", ".join(f"{c['ip']} {c['state']}" for c in R.get('nds_clients')) if R.get("nds_clients") else "No clients."))
                if nds else "Not running: GeckoTest has no captive portal.",
                actions=[act("router.opennds", "Restart openNDS", "secondary", arg=key)], needs=[reach])

    router_checks("a", A, ROUTER_A, "Router A")
    if "_error" not in A and A:
        eu = (A.get("uci") or {}).get("eduroam_legit") or {}
        on = "eduroam-lab" in (A.get("ifaces") or {}).values()
        r.check("router_a_eduroam", "Router A", "Router A broadcasts eduroam-lab", "ok" if on else "warn",
                f"On, RADIUS {eu.get('auth_server')}:{eu.get('auth_port')}." if on else "eduroam-lab isn't up on Router A.",
                needs=["router_a_reach"])
        err = A.get("radius_error")
        if err:
            try:
                age = A["now"] - time.mktime(time.strptime(err[:24], "%a %b %d %H:%M:%S %Y"))
            except (ValueError, KeyError):
                age = None
            legit = rad.get("legit") or {}
            fixed_since = legit.get("age_s") is not None and age is not None and legit["age_s"] < age and r.ok("relay") and r.ok("radius_clients")
            if age is not None and age < 900 and not fixed_since:
                r.check("router_a_radius", "Router A", "Router A gets RADIUS answers", "fail",
                        f"hostapd: 'No response from Authentication server' {ago(age)} ago. Logins on eduroam-lab time out.",
                        detail=err, needs=["router_a_reach", "relay", "radius_legit", "radius_clients"])
        b_drops = [t for port, t in A.get("link_down", []) if port == 1 and A.get("uptime", 0) - t < 900]
        if b_drops:
            r.check("ab_link_stable", "Router B", "Cable A to B stays up", "warn",
                    f"Router A saw the cable to Router B drop {len(b_drops)} times in the last 15 min.")

    if "_error" in B:
        r.check("router_b_reach", "Router B", "Router B reachable (SSH through A)", "fail", B["_error"], needs=["router_a_reach"])
    elif B:
        r.check("router_b_reach", "Router B", "Router B reachable (SSH through A)", "ok", f"Router up {ago(B.get('uptime'))}.")
        router_checks("b", B, ROUTER_B, "Router B")
        if b_mode == "b1" and not r.ok("relay"):
            r.check("router_b_guard", "Router B", "Attacker AP has its backend", "fail",
                    "B1 is broadcasting eduroam-lab while the relay is down.",
                    actions=[act("routerb.mode", "Turn B1 off", "destructive", arg="off")])

    # ---------------- tablet
    if "_error" in T:
        r.check("tablet", "Tablet", "Tablet connected (adb)", "unknown", T["_error"])
    elif not T.get("serial"):
        unauth = [d for d in T.get("devices", []) if len(d) > 1 and d[1] == "unauthorized"]
        r.check("tablet", "Tablet", "Tablet connected (adb)", "warn" if unauth else "off",
                "Plugged in, but USB debugging isn't allowed: confirm the prompt on the tablet." if unauth else "No tablet on USB.")
    else:
        r.check("tablet", "Tablet", "Tablet connected (adb)", "ok",
                f"{T['serial']}, app {T.get('app_version') or 'not installed'}" + (f", installed {T['app_updated']}." if T.get("app_updated") else "."),
                actions=[act("tablet.install", "Install app build", "secondary")])
        ap = AP_BY_SUFFIX.get((T.get("bssid") or "")[-8:])
        if T.get("ssid") and T.get("supplicant") == "COMPLETED":
            where = {"routerA": "Router A", "routerB": "Router B"}.get(ap, "not a lab router")
            r.check("tablet_wifi", "Tablet", "Tablet Wi-Fi", "ok",
                    f"{T['ssid']} on {where} ({T['bssid']}), IP {T.get('ip') or '?'}."
                    + (" Only the app uses this network (the shell has no route), as expected for GECKO's own joins." if T.get("shell_no_route") else ""))
        else:
            r.check("tablet_wifi", "Tablet", "Tablet Wi-Fi", "off", "Not connected to any network." if T.get("wifi_on") else "Wi-Fi is off.")
        manual = [s for s in T.get("saved") or [] if s["ssid"] == "eduroam-lab"]
        if manual:
            r.check("tablet_saved", "Tablet", "No manually saved eduroam-lab", "warn",
                    "A manually saved eduroam-lab wins over the app's pinned profile.",
                    actions=[act("tablet.forget", "Forget it", "destructive", arg=manual[0]["id"])])
        T["ap"] = ap

    r.resolve_dependencies()
    return r, {"b_mode": b_mode, "portals": portals, "scenario": scenario, "wsl_ip": wsl_ip, "lab_ips": lab_ips}


def build_status(raw, state):
    r, extra = evaluate(raw, state)
    lap = raw.get("laptop") or {}
    lab = lap.get("lab") or {}
    up = lap.get("uplink") or {}
    w = raw.get("wsl") or {}
    A, B, T = raw.get("routerA") or {}, raw.get("routerB") or {}, raw.get("tablet") or {}

    # Addresses: laptop and WSL
    lab_ips = extra["lab_ips"]
    for ip, what in ((LAPTOP_IP, "ICS address"), (GENUINE_IP, "Genuine portal"), (ATTACKER_IP, "Attacker portal")):
        r.addr(f"Laptop {LAB_ADAPTER}", what, ip, ip if ip in lab_ips else None, "ok" if ip in lab_ips else "fail")
    others = [a for a in lab_ips if a not in (LAPTOP_IP, GENUINE_IP, ATTACKER_IP)]
    if others:
        r.addr(f"Laptop {LAB_ADAPTER}", "Other addresses", "none", ", ".join(others), "warn")
    r.addr(f"Laptop {LAB_ADAPTER}", "Network profile", "any", lab.get("category") or "unknown", "ok")
    r.addr(f"Laptop {UPLINK_ADAPTER}", "Address / network", "any", f"{', '.join(up.get('ipv4') or []) or 'none'} on {up.get('ssid') or '?'}",
           "ok" if up.get("status") == "Up" else "fail")
    wsl_ip = extra["wsl_ip"]
    r.addr("WSL", "Address", "changes on every restart", wsl_ip, "ok" if wsl_ip else "unknown")
    pp = [p for p in raw.get("portproxy") or [] if p["listen_port"] == 1234]
    r.addr("Laptop port forward", "0.0.0.0:1234 forwards to", wsl_ip or "WSL address", pp[0]["connect"] if pp else None)
    r.addr("WSL", "Gateway (source of relayed RADIUS)", "inside 172.16.0.0/12", w.get("gateway"),
           "ok" if w.get("gateway") and ipaddress.ip_address(w["gateway"]) in ipaddress.ip_network("172.16.0.0/12") else "unknown")
    if w.get("map_servers"):
        s = w["map_servers"][0]
        r.addr("Map server", "Trillian tree (--clog-id)", w.get("envrc_clog_id") or "?", s.get("clog_id"))
        r.addr("Map server", "Database", GEOPKI_DB, s.get("database"))
        r.addr("Map server", "Directory", GEOPKI_DIR.replace("~", "/home/…"), s.get("cwd"),
               "ok" if (s.get("cwd") or "").endswith(GEOPKI_DIR.split("/")[-1]) else "fail")
    if T.get("serial") and T.get("ip"):
        net = {"192.168.8.": "Router A LAN", "192.168.247.": "Router B LAN", "10.98.0.": "eduroam users (A)", "10.99.0.": "onboarding walled garden"}
        where = next((v for k, v in net.items() if T["ip"].startswith(k)), "not a lab network")
        r.addr("Tablet", "Wi-Fi address", "a lab subnet", f"{T['ip']} ({where})", "ok" if where != "not a lab network" else "warn")
    r.addresses.sort(key=lambda a: 0)  # keep insertion order

    checks = list(r.checks.values())
    problems = sorted([c for c in checks if c["status"] in ("fail", "warn")], key=lambda c: (ORDER[c["status"]], AREA_ORDER.get(c["area"], 9)))

    def link(lid, frm, to, via, ids):
        cs = [r.checks[i] for i in ids if i in r.checks]
        if not cs:
            return {"id": lid, "from": frm, "to": to, "via": via, "status": "unknown", "summary": "Not checked."}
        worst = min(cs, key=lambda c: ORDER[c["status"]])
        return {"id": lid, "from": frm, "to": to, "via": via, "status": worst["status"],
                # all fine: the most specific check (the last) says what was tested
                "summary": worst["summary"] if worst["status"] != "ok" else cs[-1]["summary"]}

    links = [
        link("l_up", "Laptop", "Internet", UPLINK_ADAPTER, ["uplink"]),
        link("l_a", "Laptop", "Router A", f"cable, {LAB_ADAPTER}", ["lab_link", "lab_ip", "router_a_reach", "lab_link_stable"]),
        link("l_a_inet", "Router A", "Internet", "laptop internet sharing", ["router_a_reach", "router_a_internet"]),
        link("l_a_map", "Router A", "Map server", f"{LAPTOP_IP}:1234", ["router_a_reach", "router_a_map"]),
        link("l_a_realm", "Router A", "Realm server", f"{LAPTOP_IP}:1235", ["router_a_reach", "router_a_realm"]),
        link("l_a_radius", "Router A", "FreeRADIUS", "relay, then WSL", ["router_a_reach", "relay", "radius_clients", "radius_legit", "router_a_radius"]),
        link("l_b", "Router A", "Router B", "cable, A's LAN", ["router_b_reach", "ab_link_stable"]),
        link("l_b_inet", "Router B", "Internet", "through A", ["router_b_reach", "router_b_internet"]),
        link("l_b_map", "Router B", "Map server", f"{LAPTOP_IP}:1234", ["router_b_reach", "router_b_map"]),
        link("l_wsl", "Laptop", "WSL", "wsl.exe", ["wsl"]),
        link("l_pp", "Laptop :1234", "Map server", "port forward", ["portproxy", "map_reach"]),
        link("l_trillian", "Map server", "Trillian", "Docker, :8090", ["trillian"]),
        link("l_pg", "Map server", "PostgreSQL", ":5432", ["postgres"]),
        link("l_tab", "Tablet", {"routerA": "Router A", "routerB": "Router B"}.get(T.get("ap"), "access point"),
             T.get("ssid") or "Wi-Fi", ["tablet_wifi"]),
    ]

    def node(ids):
        cs = [r.checks[i] for i in ids if i in r.checks]
        return min((c["status"] for c in cs), key=lambda s: ORDER[s]) if cs else "unknown"

    nodes = {
        "internet": {"status": node(["uplink"]), "sub": up.get("ssid") or ""},
        "laptop": {"status": node(["lab_link", "lab_ip", "portal_ips", "relay", "realm", "portproxy", "firewall", "ics"]), "sub": LAPTOP_IP},
        "wsl": {"status": node(["wsl", "map_server", "trillian", "postgres", "radius_legit", "radius_clients"]), "sub": wsl_ip or ""},
        "routerA": {"status": node(["router_a_reach", "router_a_internet", "router_a_map", "router_a_opennds", "router_a_eduroam", "router_a_radius"]),
                    "sub": ROUTER_A["wan"]},
        "routerB": {"status": node(["router_b_reach", "router_b_internet", "router_b_map", "router_b_opennds"]), "sub": ROUTER_B["wan"]},
        "tablet": {"status": node(["tablet", "tablet_wifi"]), "sub": T.get("ip") or "", "ap": T.get("ap")},
    }
    edges = {"internet-laptop": link("", "", "", "", ["uplink"])["status"],
             "laptop-wsl": link("", "", "", "", ["wsl"])["status"],
             "laptop-routerA": link("", "", "", "", ["lab_link", "lab_ip", "router_a_reach"])["status"],
             "routerA-routerB": link("", "", "", "", ["router_b_reach"])["status"],
             "tablet": link("", "", "", "", ["tablet_wifi"])["status"]}

    services = [
        {"name": "Docker Desktop / Trillian", "where": "Windows + WSL", "check": "trillian", "log": None},
        {"name": "PostgreSQL", "where": "WSL", "check": "postgres", "log": None},
        {"name": "Map server (geopki)", "where": "WSL", "check": "map_server", "log": "map",
         "extra": [act("map.restart", "Restart", "secondary"), act("map.stop", "Stop", "destructive")]},
        {"name": "Port forward :1234", "where": "Windows", "check": "portproxy", "log": None},
    ] + r.services
    for s in services:
        c = r.checks.get(s["check"]) or {"status": "unknown", "summary": "", "actions": []}
        s["status"], s["summary"], s["actions"] = c["status"], c["summary"], list(c["actions"])
        if s.get("extra") and c["status"] in ("ok", "warn", "fail") and (w.get("map_servers") if s["check"] == "map_server" else True):
            s["actions"] += [a for a in s["extra"] if a["action"] not in {x["action"] for x in s["actions"]}]

    routers = []
    for key, R, cfg, label in (("a", A, ROUTER_A, "Router A"), ("b", B, ROUTER_B, "Router B")):
        if not R or "_error" in R:
            routers.append({"key": key, "name": label, "error": (R or {}).get("_error") or "not checked"})
            continue
        aps = []
        for sec, u in (R.get("uci") or {}).items():
            if u.get("ssid"):
                aps.append({"section": sec, "ssid": u["ssid"], "enabled": u.get("disabled", "0") != "1",
                            "security": u.get("encryption"), "radius_port": u.get("auth_port"), "network": u.get("network")})
        routers.append({
            "key": key, "name": label, "uptime": ago(R.get("uptime")), "aps": aps,
            "nds_clients": R.get("nds_clients"), "dns_overrides": R.get("dns_overrides"), "leases": R.get("leases"),
            "s7_block": R.get("s7_block") == "1", "garden": [R.get("garden_allowed"), R.get("garden_rejected")],
            "fas": f"{R.get('fas_fqdn')} at {R.get('fas_ip')}", "eap_last": R.get("eap_last"),
            "link_drops": {p: sum(1 for port, t in R.get("link_down", []) if port == p and R.get("uptime", 0) - t < 900) for p in (0, 1)},
        })

    tablet = None
    if T.get("serial"):
        tablet = {k: T.get(k) for k in ("serial", "ssid", "bssid", "ip", "supplicant", "app_version", "app_updated", "shell_internet", "ap", "last_logs")}
        tablet["saved"] = [s for s in T.get("saved") or [] if s["ssid"] in ("eduroam-lab", "GeckoTest", "eduroam")]

    counts = {s: sum(1 for c in checks if c["status"] == s) for s in ORDER}
    return {
        "time": raw["_time"], "duration": raw["_duration"], "counts": counts, "problems": problems,
        "checks": sorted(checks, key=lambda c: (AREA_ORDER.get(c["area"], 9), ORDER[c["status"]])),
        "links": links, "nodes": nodes, "edges": edges, "services": services, "addresses": r.addresses,
        "routers": routers, "tablet": tablet, "b_mode": extra["b_mode"], "portals": extra["portals"], "scenario": extra["scenario"],
        "errors": {k: v["_error"] for k, v in raw.items() if isinstance(v, dict) and "_error" in v},
    }


AREA_ORDER = {"Laptop": 0, "WSL": 1, "Router A": 2, "Router B": 3, "Tablet": 4}


# --------------------------------------------------------------------------- actions

class NeedSudo(Exception):
    pass


class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.status = None
        self.raw = None
        self.collecting = False
        self.events = deque(maxlen=80)
        self.jobs = deque(maxlen=30)
        self.sudo = None
        self.prev = {}
        self.adapter_history = deque(maxlen=200)
        self.wake = threading.Event()
        LOGS.mkdir(exist_ok=True)
        self.state_file = LOGS / "state.json"
        try:
            self.persist = json.loads(self.state_file.read_text())
        except Exception:
            self.persist = {}

    def remember(self, key, value):
        self.persist[key] = value
        try:
            self.state_file.write_text(json.dumps(self.persist))
        except OSError:
            pass

    def relay_target(self, pid):
        """The address the relay forwards to, known only if the dashboard started it."""
        rec = self.persist.get("relay")
        if not rec or rec.get("pid") != pid:
            return None
        log = tail_file(LOGS / "relay.log", 400) or ""
        targets = re.findall(r"relaying udp .*? -> ([\d.]+)", log)
        return targets[-1] if targets else None

    def adapter_down_polls(self, now, window):
        return sum(1 for t, up in self.adapter_history if now - t < window and not up)

    def refresh(self):
        with self.lock:
            if self.collecting:
                return
            self.collecting = True
        try:
            raw = collect()
            lab = (raw.get("laptop") or {}).get("lab") or {}
            self.adapter_history.append((raw["_time"], lab.get("status") == "Up"))
            status = build_status(raw, self)
            for c in status["checks"]:
                before = self.prev.get(c["id"])
                if before and before != c["status"]:
                    self.events.appendleft({"time": raw["_time"], "title": c["title"], "from": before, "to": c["status"], "summary": c["summary"]})
                self.prev[c["id"]] = c["status"]
            status["events"] = list(self.events)
            self.raw, self.status = raw, status
        except Exception:
            traceback.print_exc()
        finally:
            self.collecting = False

    def loop(self):
        while True:
            self.refresh()
            self.wake.wait(REFRESH_S)
            self.wake.clear()


STATE = State()


def spawn(name, args, cwd):
    """A detached Windows process with its output in logs/<name>.log."""
    log_path = LOGS / f"{name.replace(':', '-').replace('/', '-')}.log"
    log = open(log_path, "ab")
    log.write(f"\n--- {datetime.now():%Y-%m-%d %H:%M:%S} started by the dashboard: {' '.join(map(str, args))}\n".encode())
    log.flush()
    kw = dict(cwd=str(cwd), stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    try:
        p = subprocess.Popen(args, creationflags=NO_WINDOW | NEW_GROUP | BREAKAWAY, **kw)
    except OSError:
        p = subprocess.Popen(args, creationflags=NO_WINDOW | NEW_GROUP, **kw)
    return p, log_path


def kill(pids):
    out = []
    for pid in pids:
        rc, o = run(["taskkill", "/PID", str(pid), "/T", "/F"], timeout=10)
        out.append(o.strip())
    return "\n".join(out) or "nothing to stop"


def lab_procs(pred):
    lap = (STATE.raw or {}).get("laptop") or {}
    return [p for p in lap.get("processes") or [] if pred(p.get("name") or "", p.get("command") or "")]


def wait_for(pred, seconds, step=1.0):
    end = time.time() + seconds
    while time.time() < end:
        if pred():
            return True
        time.sleep(step)
    return False


def wsl_action(cmd, arg=None, sudo=False, timeout=120):
    if sudo and not STATE.sudo:
        raise NeedSudo()
    args = ["wsl", "bash", wsl_path(HERE / "wsl" / "actions.sh"), cmd] + ([arg] if arg else [])
    rc, out = run(args, timeout=timeout, input=(STATE.sudo + "\n") if sudo else None)
    if "SUDO_FAILED" in out:
        STATE.sudo = None
        raise RuntimeError("Wrong sudo password for WSL. Nothing was changed.")
    if rc != 0:
        raise RuntimeError(out.strip() or f"exited {rc}")
    return out.strip()


def start_relay():
    if stop_relay(quiet=True):
        time.sleep(1)  # let Windows release the ports
    p, log = spawn("relay", [sys.executable, "-u", str(REPO / "lab" / "eduroam" / "udp-relay.py")], REPO)
    STATE.remember("relay", {"pid": p.pid})
    time.sleep(3)
    if p.poll() is not None:
        raise RuntimeError("relay exited:\n" + (tail_file(log, 15) or ""))
    return f"relay started (pid {p.pid})\n" + (tail_file(log, 3) or "")


def stop_relay(quiet=False):
    pids = {p["pid"] for p in lab_procs(lambda n, c: n.startswith("python") and "udp-relay.py" in c)}
    # Whatever holds the relay ports right now, also a relay the last check missed:
    # a second relay can't bind them (WinError 10048).
    udp = probe_netstat()["udp"]
    for port in RELAY_PORTS:
        owner = udp.get(f"{LAPTOP_IP}:{port}")
        if owner and owner not in pids:
            rc, out = run(["tasklist", "/FI", f"PID eq {owner}", "/FO", "CSV", "/NH"], timeout=8)
            name = out.split(",")[0].strip('"') if out.startswith('"') else "?"
            if not name.lower().startswith("python"):
                raise RuntimeError(f"{LAPTOP_IP}:{port} is held by {name} (pid {owner}), not by a relay: stop that first.")
            pids.add(owner)
    return kill(sorted(pids)) if pids else ("" if quiet else "relay was not running")


def start_realm():
    go = find_go()
    if not go:
        raise RuntimeError("go not found on Windows")
    stop_realm(quiet=True)
    p, log = spawn("realm", [go, "run", ".", "serve", "-addr", f"{LAPTOP_IP}:1235", "-dir", r"..\..\lab-secrets\realm\signed"],
                   REPO / "lab" / "realm")
    if not wait_for(lambda: tcp_open(LAPTOP_IP, 1235, 1) or p.poll() is not None, 60):
        raise RuntimeError("realm server not listening after 60 s:\n" + (tail_file(log, 15) or ""))
    if p.poll() is not None:
        raise RuntimeError("realm server exited:\n" + (tail_file(log, 15) or ""))
    return f"realm server listening on {LAPTOP_IP}:1235"


def stop_realm(quiet=False):
    pids = [p["pid"] for p in lab_procs(lambda n, c: (n == "realm.exe" and " serve" in c) or (n == "go.exe" and "run . serve" in c))]
    return kill(pids) if pids else ("" if quiet else "realm server was not running")


def start_portal(cid):
    configs = portal_configs()
    if cid not in configs:
        raise RuntimeError(f"unknown portal config {cid}")
    cfg = configs[cid]
    ip = cfg["listen_ip"]
    lab = ((STATE.raw or {}).get("laptop") or {}).get("lab") or {}
    if ip and not any(a.startswith(ip + "/") for a in lab.get("ipv4") or []):
        raise RuntimeError(f"{ip} isn't on {LAB_ADAPTER}: re-add the lab addresses first (admin fix 'ips').")
    exe = REPO / "portal" / "portal.exe"
    if not exe.exists():
        rc, out = run([find_go() or "go", "build", "-o", "portal.exe", "."], timeout=180, cwd=str(REPO / "portal"))
        if rc != 0:
            raise RuntimeError("go build failed:\n" + out)
    same_ip = [p["pid"] for p in lab_procs(lambda n, c: n == "portal.exe" and " serve" in c)
               if (portal_configs().get(config_id(p["command"])) or {}).get("listen_ip") == ip]
    note = ""
    if same_ip:
        note = f"stopped the portal already on {ip}: " + kill(same_ip) + "\n"
        time.sleep(1)
    p, log = spawn(f"portal-{cid}", [str(exe), "serve", "-config", str(cfg["path"].relative_to(REPO / "portal"))], REPO / "portal")
    if not wait_for(lambda: tcp_open(ip, 443, 1) or p.poll() is not None, 15):
        raise RuntimeError(note + "portal not listening after 15 s:\n" + (tail_file(log, 15) or ""))
    if p.poll() is not None:
        raise RuntimeError(note + "portal exited:\n" + (tail_file(log, 15) or ""))
    return note + f"portal {cid} listening on {ip}:80/443"


def stop_portal(cid):
    pids = [p["pid"] for p in lab_procs(lambda n, c: n == "portal.exe" and config_id(c) == cid)]
    return kill(pids) if pids else f"portal {cid} was not running"


def run_admin(tasks):
    transcript = LOGS / f"admin-{datetime.now():%Y%m%d-%H%M%S}.log"
    script = HERE / "admin" / "admin-fix.ps1"
    arglist = ",".join(f"'{a}'" for a in ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script),
                                            "-Task", tasks, "-Transcript", str(transcript)])
    rc, out = run(["powershell", "-NoProfile", "-Command",
                   f"Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList @({arglist})"], timeout=180)
    if rc != 0:
        raise RuntimeError("The administrator prompt was declined or failed: " + out.strip()[-300:])
    text = tail_file(transcript, 80) or "(no transcript)"
    text = "\n".join(l for l in text.splitlines() if not l.startswith(("*****", "Windows PowerShell", "Start time", "End time",
                                                                          "Username", "RunAs", "Configuration", "Machine", "Host Application",
                                                                          "Process ID", "PSVersion", "PSEdition", "PSCompatible", "BuildVersion",
                                                                          "CLRVersion", "WSManStack", "PSRemoting", "SerializationVersion",
                                                                          "Transcript started", "OS:", "PSCompatibleVersions")))
    return text.strip()


ADMIN_TASKS = {"ics", "ips", "portproxy", "firewall"}


def ssh(host, cmd, timeout=40):
    rc, out = run(["ssh", "-o", "ConnectTimeout=6", "-o", "BatchMode=yes", host, cmd], timeout=timeout)
    if rc != 0:
        raise RuntimeError(out.strip() or f"ssh exited {rc}")
    return out.strip()


def routerb_mode(mode):
    rad = ((STATE.raw or {}).get("wsl") or {}).get("radius") or {}
    status = STATE.status or {}
    relay_ok = any(c["id"] == "relay" and c["status"] == "ok" for c in status.get("checks", []))
    if mode == "b1" and not ((rad.get("attacker-b1") or {}).get("pids") and relay_ok):
        raise RuntimeError("Refused: start FreeRADIUS attacker-b1 and the relay first (README: never enable an attacker section without them).")
    vals = {"off": ("1", "1"), "legit": ("0", "1"), "b1": ("1", "0")}[mode]
    return ssh(ROUTER_B["ssh"], f"uci set wireless.eduroam_legit.disabled={vals[0]}; uci set wireless.eduroam_b1.disabled={vals[1]}; "
                                "uci commit wireless; wifi reload; sleep 3; iw dev | grep -E 'Interface|ssid'")


def scenario_script(name, *args):
    rc, out = run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(REPO / "lab" / "scenarios" / name), *args],
                  timeout=120, cwd=str(REPO / "lab" / "scenarios"))
    if rc != 0:
        raise RuntimeError(out.strip() or f"{name} exited {rc}")
    # The script prints the portal commands for the laptop; the dashboard starts those itself.
    keep = [l for l in out.splitlines() if "portal.exe serve" not in l and "On the laptop" not in l]
    return "\n".join(keep).strip()


def setup_scenario(name):
    """Router B configured by scenario.ps1, then the scenario's genuine and attacker portal."""
    genuine, attacker, desc = SCENARIO_SETUP[name]
    out = [f"{name}: {desc}", scenario_script("scenario.ps1", name)]
    for cid in (genuine, attacker):
        if cid:
            out.append(start_portal(cid))
    return "\n".join(out)


def reset_scenario():
    """Router B back to the evil-twin baseline (reset.ps1), with the baseline portals genuine + attacker."""
    return "\n".join([scenario_script("reset.ps1"), start_portal("genuine"), start_portal("attacker")])


def adb(*args, timeout=30):
    T = (STATE.raw or {}).get("tablet") or {}
    if not T.get("serial"):
        raise RuntimeError("no tablet on adb")
    rc, out = run([T["adb"], "-s", T["serial"], *args], timeout=timeout)
    if rc != 0:
        raise RuntimeError(out.strip() or f"adb exited {rc}")
    return out.strip() or "done"


def gradle_install():
    rc, out = run([str(REPO / "gradlew.bat"), "installDeviceDebug", "--console=plain"], timeout=900, cwd=str(REPO))
    lines = out.strip().splitlines()
    if rc != 0:
        raise RuntimeError("\n".join(lines[-40:]))
    return "\n".join(lines[-12:])


def start_lab(job):
    """After a restart: start what's missing, in dependency order, with one admin prompt."""
    def step(msg):
        job["output"] += msg.rstrip() + "\n"

    def checks():
        return {c["id"]: c for c in (STATE.status or {}).get("checks", [])}

    STATE.refresh()
    c = checks()
    lap = (STATE.raw or {}).get("laptop") or {}
    admin = []
    if c.get("lab_ip", {}).get("status") in ("fail",) or c.get("portal_ips", {}).get("status") == "warn":
        admin.append("ips")
    if c.get("portproxy", {}).get("status") in ("fail", "blocked") and c.get("wsl", {}).get("status") == "ok":
        admin.append("portproxy")
    if c.get("firewall", {}).get("status") == "warn":
        admin.append("firewall")
    if c.get("trillian", {}).get("status") == "fail":
        if not lab_procs(lambda n, cmd: n == "Docker Desktop.exe"):
            step(ACTIONS["docker.start"][0](None))
        step("waiting for Trillian (Docker) on 127.0.0.1:8090 ...")
        ok = wait_for(lambda: run(["wsl", "bash", "-c", "(exec 3<>/dev/tcp/127.0.0.1/8090) 2>/dev/null"], timeout=6)[0] == 0, 180, 3)
        step("Trillian is up" if ok else "Trillian still down after 3 min: check Docker Desktop")
    if c.get("postgres", {}).get("status") == "fail":
        step(wsl_action("postgres-start", sudo=True))
    if c.get("map_server", {}).get("status") in ("fail", "blocked"):
        if (STATE.raw or {}).get("wsl", {}).get("map_servers"):
            step(wsl_action("map-stop"))
        step(wsl_action("map-start", timeout=150))
    if admin:
        step(f"administrator fixes: {', '.join(admin)} (confirm the Windows prompt)")
        step(run_admin(",".join(admin)))
    if c.get("radius_clients", {}).get("status") == "fail":
        step(wsl_action("radius-fix-clients", sudo=True))
    if c.get("radius_legit", {}).get("status") in ("fail", "blocked"):
        step(wsl_action("radius-start", "legit", sudo=True))
    if c.get("relay", {}).get("status") in ("fail", "blocked"):
        step(start_relay())
    if c.get("realm", {}).get("status") in ("fail", "warn", "blocked"):
        step(start_realm())
    if c.get("portal_genuine", {}).get("status") in ("warn", "fail", "blocked"):
        try:
            step(start_portal("genuine"))
        except RuntimeError as e:
            step(f"genuine portal: {e}")
    step("done; checking again")
    return ""


def needs_sudo_for_start_lab():
    c = {x["id"]: x for x in (STATE.status or {}).get("checks", [])}
    return any(c.get(i, {}).get("status") in ("fail", "blocked") for i in ("postgres", "radius_clients", "radius_legit"))


# name -> (function(arg) -> output, needs sudo, valid args or None, label)
ACTIONS = {
    "map.start": (lambda a: wsl_action("map-start", timeout=150), False, None, "Start map server"),
    "map.stop": (lambda a: wsl_action("map-stop"), False, None, "Stop map server"),
    "map.restart": (lambda a: wsl_action("map-stop") + "\n" + wsl_action("map-start", timeout=150), False, None, "Restart map server"),
    "radius.start": (lambda a: wsl_action("radius-start", a, sudo=True), True, list(RADIUS_PORT), "Start FreeRADIUS"),
    "radius.stop": (lambda a: wsl_action("radius-stop", a, sudo=True), True, list(RADIUS_PORT), "Stop FreeRADIUS"),
    "radius.fixclients": (lambda a: wsl_action("radius-fix-clients", sudo=True), True, None, "Accept the WSL range in FreeRADIUS"),
    "radius.test": (lambda a: wsl_action("radius-test", timeout=180), False, None, "RADIUS self-test (eapol_test)"),
    "postgres.start": (lambda a: wsl_action("postgres-start", sudo=True), True, None, "Start PostgreSQL"),
    "relay.start": (lambda a: start_relay(), False, None, "Start relay"),
    "relay.restart": (lambda a: start_relay(), False, None, "Restart relay"),
    "relay.stop": (lambda a: stop_relay(), False, None, "Stop relay"),
    "realm.start": (lambda a: start_realm(), False, None, "Start realm server"),
    "realm.restart": (lambda a: start_realm(), False, None, "Restart realm server"),
    "realm.stop": (lambda a: stop_realm(), False, None, "Stop realm server"),
    "portal.start": (lambda a: start_portal(a), False, "portal", "Start portal"),
    "portal.stop": (lambda a: stop_portal(a), False, "portal", "Stop portal"),
    "docker.start": (lambda a: (subprocess.Popen([r"C:\Program Files\Docker\Docker\Docker Desktop.exe"], creationflags=NEW_GROUP | BREAKAWAY)
                                and "Docker Desktop starting (Trillian follows in about a minute)"), False, None, "Start Docker Desktop"),
    "wsl.shutdown": (lambda a: run(["wsl", "--shutdown"], timeout=60)[1] or "WSL stopped; it starts again on the next check", False, None, "Restart WSL"),
    "admin": (lambda a: run_admin(a), False, "admin", "Administrator fix"),
    "router.opennds": (lambda a: ssh({"a": ROUTER_A, "b": ROUTER_B}[a]["ssh"], "/etc/init.d/opennds restart; sleep 2; ndsctl status | head -12"),
                       False, ["a", "b"], "Restart openNDS"),
    "router.deauth": (lambda a: ssh(ROUTER_A["ssh"], f"ndsctl deauth {a}"), False, "mac", "Log the client out (openNDS)"),
    "router.auth": (lambda a: ssh(ROUTER_A["ssh"], f"ndsctl auth {a}"), False, "mac", "Let the client through (openNDS)"),
    "routerb.mode": (lambda a: routerb_mode(a), False, ["off", "legit", "b1"], "Router B eduroam-lab mode"),
    "scenario.setup": (lambda a: setup_scenario(a), False, SCENARIOS, "Set up scenario"),
    "scenario.reset": (lambda a: reset_scenario(), False, None, "Back to the baseline"),
    "routers.snapshot": (lambda a: run([r"C:\Program Files\Git\bin\bash.exe", "lab/eduroam/pull-router-configs.sh"], timeout=300, cwd=str(REPO))[1],
                         False, None, "Snapshot router configs"),
    "tablet.install": (lambda a: gradle_install(), False, None, "Install the app (installDeviceDebug)"),
    "tablet.logclear": (lambda a: adb("logcat", "-c"), False, None, "Clear the tablet's log"),
    "tablet.location": (lambda a: adb("shell", f"cmd location providers set-test-provider-location gps --location {LAB_LOCATION} --accuracy 15"),
                        False, None, "Fake the lab location"),
    "tablet.pullchecks": (lambda a: adb("pull", "/sdcard/Android/data/com.thesis.geckowifi/files/portal-checks/", str(LOGS / "portal-checks")),
                          False, None, "Pull the per-check JSON logs"),
    "tablet.forget": (lambda a: adb("shell", f"cmd wifi forget-network {a}"), False, "digits", "Forget a saved network"),
    "lab.start": (None, None, None, "Start the lab"),
}


def valid_arg(spec, arg):
    if spec is None:
        return arg in (None, "")
    if spec == "portal":
        return arg in portal_configs()
    if spec == "admin":
        return bool(arg) and all(t in ADMIN_TASKS for t in arg.split(","))
    if spec == "mac":
        return bool(re.fullmatch(r"[0-9a-f]{2}(:[0-9a-f]{2}){5}", arg or ""))
    if spec == "digits":
        return bool(re.fullmatch(r"\d+", arg or ""))
    return arg in spec


def start_job(name, arg):
    fn, needs_sudo, spec, label = ACTIONS[name]
    if name == "lab.start":
        needs_sudo = needs_sudo_for_start_lab()
    if needs_sudo and not STATE.sudo:
        raise NeedSudo()
    job = {"id": secrets.token_hex(4), "action": name, "arg": arg, "label": label + (f" ({arg})" if arg else ""),
           "status": "running", "output": "", "started": time.time(), "ended": None}
    STATE.jobs.appendleft(job)

    def work():
        try:
            out = start_lab(job) if name == "lab.start" else fn(arg)
            job["output"] += (out or "").strip()
            job["status"] = "ok"
        except NeedSudo:
            job["output"] += "needs the WSL sudo password"
            job["status"] = "failed"
        except Exception as e:
            job["output"] += str(e)
            job["status"] = "failed"
        job["ended"] = time.time()
        STATE.wake.set()

    threading.Thread(target=work, daemon=True).start()
    return job


def read_log(name):
    if name == "map":
        return wsl_action("map-log")
    if name.startswith("radius:"):
        inst = name.split(":", 1)[1]
        if inst not in RADIUS_PORT:
            raise ValueError("unknown instance")
        return wsl_action("radius-log", inst)
    if name in ("relay", "realm") or (name.startswith("portal:") and name[7:] in portal_configs()):
        path = LOGS / f"{name.replace(':', '-').replace('/', '-')}.log"
        text = tail_file(path)
        return text if text is not None else ("No log yet: this one was started outside the dashboard, "
                                              "so its output is in the window that started it.")
    raise ValueError("unknown log")


# --------------------------------------------------------------------------- HTTP

TYPES = {".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8", ".js": "text/javascript; charset=utf-8",
         ".svg": "image/svg+xml"}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def send(self, code, body, ctype="application/json; charset=utf-8"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)

    def local_only(self):
        host = (self.headers.get("Host") or "").split(":")[0]
        return host in ("127.0.0.1", "localhost")

    def do_GET(self):
        if not self.local_only():
            return self.send(403, {"error": "local only"})
        path = self.path.split("?")[0]
        if path == "/api/status":
            return self.send(200, {"status": STATE.status, "collecting": STATE.collecting, "jobs": list(STATE.jobs),
                                   "sudo_cached": bool(STATE.sudo)})
        if path == "/api/log":
            name = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get("name", [""])[0]
            try:
                return self.send(200, {"name": name, "text": read_log(name)})
            except Exception as e:
                return self.send(200, {"name": name, "text": str(e)})
        f = (WEB / ("index.html" if path == "/" else path.lstrip("/"))).resolve()
        if WEB in f.parents and f.is_file() and f.suffix in TYPES:
            return self.send(200, f.read_bytes(), TYPES[f.suffix])
        self.send(404, {"error": "not found"})

    def do_POST(self):
        # Custom header + Host check: a web page elsewhere can't trigger actions here.
        if not self.local_only() or self.headers.get("X-Lab-Dashboard") != "1":
            return self.send(403, {"error": "local only"})
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
        except ValueError:
            return self.send(400, {"error": "bad json"})
        path = self.path.split("?")[0]
        if path == "/api/refresh":
            STATE.wake.set()
            return self.send(200, {"ok": True})
        if path == "/api/sudo":
            STATE.sudo = body.get("password") or None
            return self.send(200, {"ok": True})
        if path == "/api/action":
            name, arg = body.get("action"), body.get("arg")
            if name not in ACTIONS or not valid_arg(ACTIONS[name][2], arg):
                return self.send(400, {"error": f"unknown action {name} {arg}"})
            try:
                return self.send(200, {"job": start_job(name, arg)})
            except NeedSudo:
                return self.send(409, {"need_sudo": True})
        self.send(404, {"error": "not found"})



def main():
    threading.Thread(target=STATE.loop, daemon=True).start()
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"GECKO lab dashboard on http://127.0.0.1:{PORT}  (Ctrl-C stops it; started services keep running)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
