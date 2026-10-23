// GECKO lab dashboard: renders /api/status and sends the actions it offers.
"use strict";

const LABEL = { ok: "OK", warn: "Warning", fail: "Problem", blocked: "Waiting", off: "Off", unknown: "Unknown" };
const POLL_MS = 3000;
let current = null;
let jobs = [];
let pending = null; // action waiting for the sudo password

const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

function status(st, text) {
  return `<span class="status st-${st}"><svg class="icon icon-sm" aria-hidden="true"><use href="#i-${st}"/></svg>${esc(text ?? LABEL[st])}</span>`;
}

function ago(t) {
  const s = Math.max(0, Math.round(Date.now() / 1000 - t));
  if (s < 90) return `${s} s ago`;
  if (s < 5400) return `${Math.round(s / 60)} min ago`;
  return `${Math.round(s / 3600)} h ago`;
}

function clock(t) {
  return new Date(t * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

// ------------------------------------------------------------------ buttons

function busy(action, arg) {
  return jobs.some((j) => j.status === "running" && j.action === action && (j.arg ?? null) === (arg ?? null));
}

function actionButton(a) {
  const cls = { primary: "btn btn-primary", destructive: "btn btn-destructive", secondary: "btn" }[a.kind] || "btn";
  const icon = a.admin ? "shield" : a.kind === "primary" ? "play" : null;
  const isBusy = busy(a.action, a.arg);
  return `<button class="${cls}" data-action="${esc(a.action)}" ${a.arg != null ? `data-arg="${esc(a.arg)}"` : ""}
    ${a.confirm ? `data-confirm="${esc(a.confirm)}"` : ""} ${a.admin ? 'data-admin="1"' : ""}
    ${isBusy ? 'aria-busy="true" disabled' : ""} title="${a.admin ? "Windows asks for administrator rights" : ""}">
    ${icon ? `<svg class="icon" aria-hidden="true"><use href="#i-${isBusy ? "refresh" : icon}"/></svg>` : isBusy ? '<svg class="icon" aria-hidden="true"><use href="#i-refresh"/></svg>' : ""}
    <span>${esc(a.label)}</span></button>`;
}

function logButton(name, label = "Log") {
  return name ? `<button class="btn" data-log="${esc(name)}"><svg class="icon" aria-hidden="true"><use href="#i-file"/></svg><span>${esc(label)}</span></button>` : "";
}

// ------------------------------------------------------------------ render

function renderVerdict(s, collecting) {
  const fails = s.problems.filter((p) => p.status === "fail").length;
  const warns = s.problems.length - fails;
  let text;
  if (!s.problems.length) text = "Everything is up";
  else if (fails) text = fails === 1 ? "1 thing is broken" : `${fails} things are broken`;
  else text = warns === 1 ? "1 thing to look at" : `${warns} things to look at`;
  if (fails && warns) text += `, ${warns} to look at`;
  $("#verdict").textContent = text;
  const errs = Object.keys(s.errors || {}).length;
  $("#checked").textContent = `Checked ${ago(s.time)} (${s.duration} s). ${collecting ? "Checking again now." : "Checks run every 20 s."}`
    + (errs ? ` ${errs} probe${errs > 1 ? "s" : ""} could not run, see All checks.` : "");
  document.title = s.problems.length ? `(${s.problems.length}) GECKO lab` : "GECKO lab";
  const startBtn = document.querySelector('.top-actions [data-action="lab.start"]');
  const running = busy("lab.start", null);
  startBtn.disabled = running;
  startBtn.setAttribute("aria-busy", String(running));
}

function renderProblems(s) {
  const el = $("#problems");
  if (!s.problems.length) {
    el.innerHTML = `<div class="empty">${status("ok", "Nothing to fix")}<span class="muted">All checks pass or are switched off on purpose.</span></div>`;
    return;
  }
  el.innerHTML = s.problems.map((p) => `
    <article class="problem problem-${p.status}">
      <div class="problem-head">${status(p.status)}<span class="problem-title">${esc(p.title)}</span><span class="problem-area">${esc(p.area)}</span></div>
      <p>${esc(p.summary)}</p>
      ${p.detail ? `<p class="detail">${esc(p.detail)}</p>` : ""}
      ${p.actions.length ? `<div class="button-row">${p.actions.map(actionButton).join("")}</div>` : ""}
    </article>`).join("");
}

const NODES = {
  internet: { x: 30, y: 16, title: "Internet" },
  laptop: { x: 30, y: 122, title: "Laptop" },
  wsl: { x: 30, y: 228, title: "WSL" },
  routerA: { x: 290, y: 122, title: "Router A" },
  routerB: { x: 530, y: 122, title: "Router B" },
  tablet: { x: 410, y: 228, title: "Tablet" },
};
const W = 160, H = 56;

function renderTopology(s) {
  const n = s.nodes, e = s.edges;
  const mid = (k, side) => {
    const b = NODES[k];
    return { top: [b.x + W / 2, b.y], bottom: [b.x + W / 2, b.y + H], left: [b.x, b.y + H / 2], right: [b.x + W, b.y + H / 2] }[side];
  };
  const line = (a, b, st) => `<line class="topo-edge st-${st}" x1="${a[0]}" y1="${a[1]}" x2="${b[0]}" y2="${b[1]}"/>`;
  const tabletTo = n.tablet.ap || null;
  let edges = line(mid("internet", "bottom"), mid("laptop", "top"), e["internet-laptop"])
    + line(mid("laptop", "bottom"), mid("wsl", "top"), e["laptop-wsl"])
    + line(mid("laptop", "right"), mid("routerA", "left"), e["laptop-routerA"])
    + line(mid("routerA", "right"), mid("routerB", "left"), e["routerA-routerB"]);
  if (tabletTo) edges += line(mid("tablet", "top"), mid(tabletTo, "bottom"), e.tablet);
  const nodes = Object.entries(NODES).map(([k, b]) => {
    const st = n[k]?.status || "unknown";
    return `<g class="topo-node st-${st}">
      <rect x="${b.x}" y="${b.y}" width="${W}" height="${H}" rx="10"/>
      <svg x="${b.x + 12}" y="${b.y + 12}" width="18" height="18" viewBox="0 0 24 24" class="topo-icon"><use href="#i-${st}"/></svg>
      <text class="topo-title" x="${b.x + 38}" y="${b.y + 26}">${esc(b.title)}</text>
      <text class="topo-sub" x="${b.x + 38}" y="${b.y + 44}">${esc((n[k]?.sub || LABEL[st]).slice(0, 18))}</text>
    </g>`;
  }).join("");
  $("#topology").innerHTML = edges + nodes;
  $("#links").innerHTML = s.links.map((l) => `
    <li class="link">
      <div class="link-head"><span class="link-route">${esc(l.from)} to ${esc(l.to)}</span>${status(l.status)}</div>
      <span class="link-via">via ${esc(l.via)}</span>
      <p>${esc(l.summary)}</p>
    </li>`).join("");
}

function renderServices(s) {
  $("#service-list").innerHTML = s.services.map((v) => `
    <div class="row">
      <div>
        <div class="row-head">${status(v.status)}<span class="row-name">${esc(v.name)}</span><span class="row-where">${esc(v.where)}</span></div>
        <p class="muted">${esc(v.summary)}</p>
      </div>
      <div class="button-row">${v.actions.map(actionButton).join("")}${logButton(v.log)}</div>
    </div>`).join("");
}

const short = (cid) => (cid || "").replace(/^scenarios\//, "");

function renderPortals(s) {
  $("#portal-slots").innerHTML = s.portals.map((p) => {
    const c = s.checks.find((x) => x.id === p.check) || { status: "unknown", summary: "" };
    const choices = p.configs.map((cid) => {
      const on = p.running.includes(cid);
      const isBusy = busy("portal.start", cid);
      return `<button class="chip" data-action="portal.start" data-arg="${esc(cid)}" aria-pressed="${on}"
        ${isBusy ? 'aria-busy="true" disabled' : ""}>${esc(short(cid))}</button>`;
    }).join("");
    return `<div class="panel">
      <div class="row-head">${status(c.status)}<span class="row-name">${esc(p.slot === "genuine" ? "Genuine" : "Attacker")} portal</span>
        <span class="row-where">${esc(p.router)}, ${esc(p.ip)}</span></div>
      <p class="muted">${esc(c.summary)}</p>
      <div class="chips" role="group" aria-label="${esc(p.slot)} portal config">${choices}</div>
      <div class="button-row">${p.running.map((cid) => actionButton({ action: "portal.stop", arg: cid, label: "Stop", kind: "destructive" })).join("")}
        ${logButton(p.log)}</div>
    </div>`;
  }).join("");
  const sc = s.scenario;
  const [gen, atk] = [s.portals[0].running[0], s.portals[1].running[0]];
  // No exact match: name the scenario the attacker config belongs to and what the genuine slot should run.
  const near = sc.list.find((x) => x.attacker && x.attacker === atk);
  $("#scenario-now").textContent = sc.matches.length ? `Running now: ${sc.matches.join(" or ")}`
    : sc.baseline ? "Running now: the baseline (genuine + attacker clone)"
    : near ? `${short(atk)} belongs to ${near.id}, which needs ${short(near.genuine)} as the genuine portal (running: ${short(gen) || "none"})`
    : "The running portals match no scenario";
  $("#scenario-list").innerHTML = sc.list.map((x) => {
    const on = sc.matches.includes(x.id);
    const pair = x.genuine ? `${short(x.genuine)} + ${short(x.attacker)}` : "portals unchanged";
    return `<li class="scenario${on ? " scenario-on" : ""}">
      <span class="scenario-id">${esc(x.id)}</span>
      <span class="scenario-text"><span>${esc(x.desc)}</span><span class="muted mono">${esc(pair)}</span></span>
      ${on ? status("ok", "Running") : actionButton({ action: "scenario.setup", arg: x.id, label: "Set up", kind: "secondary" })}
    </li>`;
  }).join("");
}

function renderAddresses(s) {
  $("#address-rows").innerHTML = s.addresses.map((a) => `
    <tr><td>${esc(a.where)}</td><td>${esc(a.what)}</td><td class="mono">${esc(a.expected)}</td>
        <td class="mono">${esc(a.actual)}</td><td>${status(a.status)}</td></tr>`).join("");
}

function renderRouters(s) {
  $("#router-panels").innerHTML = s.routers.map((r) => {
    if (r.error) return `<div class="panel"><h3>${esc(r.name)}</h3>${status("fail", "Not reachable")}<p class="detail">${esc(r.error)}</p></div>`;
    const aps = r.aps.map((a) => `<li>${status(a.enabled ? "ok" : "off", a.enabled ? "On" : "Off")}
      <span><strong>${esc(a.ssid)}</strong> <span class="muted">${esc(a.section)}, ${esc(a.security || "?")}${a.radius_port ? `, RADIUS :${esc(a.radius_port)}` : ""}</span></span></li>`).join("");
    const clients = (r.nds_clients || []).map((c) => `<li><code>${esc(c.ip)}</code> <span class="muted">${esc(c.mac)}</span> ${esc(c.state)}
      ${r.key === "a" ? actionButton({ action: c.state === "Authenticated" ? "router.deauth" : "router.auth", arg: c.mac,
        label: c.state === "Authenticated" ? "Log out" : "Let through", kind: "secondary" }) : ""}</li>`).join("") || "<li class=\"muted\">No clients</li>";
    const drops = r.link_drops || {};
    return `<div class="panel">
      <h3>${esc(r.name)} <span class="muted">up ${esc(r.uptime)}</span></h3>
      <ul class="list">${aps}</ul>
      <h3>openNDS clients</h3><ul class="list">${clients}</ul>
      <dl class="facts">
        <dt>Portal (FAS)</dt><dd>${esc(r.fas)}</dd>
        <dt>DNS overrides</dt><dd class="mono">${esc((r.dns_overrides || []).join("  ")) || "none"}</dd>
        <dt>DHCP leases</dt><dd class="mono">${esc((r.leases || []).map((l) => `${l.ip} ${l.name}`).join(", ")) || "none"}</dd>
        ${r.key === "a" ? `<dt>Walled garden</dt><dd>${esc(r.garden[0] ?? "?")} packets allowed, ${esc(r.garden[1] ?? "?")} rejected</dd>` : ""}
        ${r.key === "b" ? `<dt>S7 block rule</dt><dd>${r.s7_block ? "present" : "absent"}</dd>` : ""}
        <dt>Link drops (15 min)</dt><dd>upstream port ${esc(drops[0] ?? 0)}${r.key === "a" ? `, Router B port ${esc(drops[1] ?? 0)}` : ""}</dd>
        <dt>Last EAP event</dt><dd class="mono">${esc(r.eap_last || "none")}</dd>
      </dl>
      <div class="button-row">${actionButton({ action: "router.opennds", arg: r.key, label: "Restart openNDS", kind: "secondary" })}</div>
    </div>`;
  }).join("");
  document.querySelectorAll("#bmode .seg-btn").forEach((b) => {
    b.setAttribute("aria-pressed", String(b.dataset.arg === s.b_mode));
    const isBusy = busy("routerb.mode", b.dataset.arg);
    b.disabled = isBusy;
    b.setAttribute("aria-busy", String(isBusy));
  });
}

function renderTablet(s) {
  const t = s.tablet;
  const el = $("#tablet-panel");
  const check = s.checks.find((c) => c.id === "tablet");
  if (!t) {
    el.innerHTML = `${status(check?.status || "off")} <p class="muted">${esc(check?.summary || "No tablet on USB.")}</p>`;
    return;
  }
  const ap = { routerA: "Router A", routerB: "Router B" }[t.ap] || "not a lab router";
  el.innerHTML = `
    <dl class="facts">
      <dt>Device</dt><dd>${esc(t.serial)}</dd>
      <dt>Wi-Fi</dt><dd>${t.ssid ? `${esc(t.ssid)} on ${esc(ap)} <span class="mono">${esc(t.bssid)}</span>, ${esc(t.supplicant)}` : "not connected"}</dd>
      <dt>Address</dt><dd class="mono">${esc(t.ip || "none")}</dd>
      <dt>Shell internet</dt><dd>${t.shell_internet ? "yes" : "no (normal while only the app uses the network)"}</dd>
      <dt>App</dt><dd>${esc(t.app_version || "not installed")}${t.app_updated ? `, installed ${esc(t.app_updated)}` : ""}</dd>
      <dt>Saved networks</dt><dd>${esc((t.saved || []).map((n) => `${n.ssid} (#${n.id})`).join(", ")) || "none of the lab SSIDs"}</dd>
    </dl>
    <div class="button-row">
      ${actionButton({ action: "tablet.install", label: "Install app build", kind: "secondary" })}
      ${actionButton({ action: "tablet.location", label: "Fake the lab location", kind: "secondary" })}
      ${actionButton({ action: "tablet.logclear", label: "Clear log", kind: "secondary" })}
      ${actionButton({ action: "tablet.pullchecks", label: "Pull check logs", kind: "secondary" })}
    </div>
    <h3>Latest app log lines</h3>
    <pre class="log">${esc((t.last_logs || []).join("\n") || "none")}</pre>`;
}

function renderActivity(s) {
  $("#jobs").innerHTML = jobs.map((j) => `
    <li class="job">
      <div class="row-head">${status(j.status === "running" ? "blocked" : j.status === "ok" ? "ok" : "fail",
        j.status === "running" ? "Running" : j.status === "ok" ? "Done" : "Failed")}
        <strong>${esc(j.label)}</strong><span class="muted">${clock(j.started)}</span></div>
      ${j.output ? `<pre>${esc(j.output)}</pre>` : ""}
    </li>`).join("") || '<li class="muted">Nothing run yet.</li>';
  $("#events").innerHTML = (s.events || []).map((e) => `
    <li>${status(e.to)} <strong>${esc(e.title)}</strong> <span class="muted">${clock(e.time)}, was ${esc(LABEL[e.from])}</span></li>`).join("")
    || '<li class="muted">No changes yet.</li>';
}

function renderAll(s) {
  const areas = [...new Set(s.checks.map((c) => c.area))];
  const errs = Object.entries(s.errors || {});
  $("#all-checks").innerHTML = (errs.length ? `<div class="panel group"><h3>Probes that could not run</h3><ul class="list">${
    errs.map(([k, v]) => `<li>${status("unknown", k)}<span class="detail">${esc(v)}</span></li>`).join("")}</ul></div>` : "")
    + areas.map((a) => `<div class="panel group"><h3>${esc(a)}</h3><ul class="list">${
      s.checks.filter((c) => c.area === a).map((c) => `<li>${status(c.status)}<strong>${esc(c.title)}</strong><span class="muted">${esc(c.summary)}</span></li>`).join("")
    }</ul></div>`).join("");
}

function render() {
  if (!current) return;
  renderVerdict(current.status, current.collecting);
  renderProblems(current.status);
  renderTopology(current.status);
  renderServices(current.status);
  renderPortals(current.status);
  renderAddresses(current.status);
  renderRouters(current.status);
  renderTablet(current.status);
  renderActivity(current.status);
  renderAll(current.status);
}

// ------------------------------------------------------------------ data

async function api(path, body) {
  const opts = body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json", "X-Lab-Dashboard": "1" }, body: JSON.stringify(body) };
  const res = await fetch(path, opts);
  return { code: res.status, data: await res.json() };
}

let lastSig = "";
async function poll() {
  try {
    const { data } = await api("/api/status");
    jobs = data.jobs || [];
    if (data.status) {
      // Re-render only when something changed, so focus and open details survive.
      const sig = JSON.stringify([data.status.time, data.collecting, jobs.map((j) => [j.id, j.status, j.output.length])]);
      current = data;
      if (sig !== lastSig) {
        const focused = document.activeElement?.closest("[data-action],[data-log]");
        const key = focused ? `${focused.dataset.action || ""}|${focused.dataset.arg || ""}|${focused.dataset.log || ""}` : null;
        render();
        lastSig = sig;
        if (key) {
          const [a, g, l] = key.split("|");
          const sel = l ? `[data-log="${CSS.escape(l)}"]` : `[data-action="${CSS.escape(a)}"]${g ? `[data-arg="${CSS.escape(g)}"]` : ":not([data-arg])"}`;
          document.querySelector(sel)?.focus();
        }
      }
    }
  } catch {
    $("#checked").textContent = "The dashboard server isn't answering. Is dashboard.py still running?";
  }
  setTimeout(poll, POLL_MS);
}

function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.hidden = true; }, 5000);
}

async function runAction(action, arg) {
  const { code, data } = await api("/api/action", { action, arg: arg ?? null });
  if (code === 409 && data.need_sudo) {
    pending = { action, arg };
    $("#sudo-error").hidden = true;
    $("#sudo-input").value = "";
    $("#sudo-input").removeAttribute("aria-invalid");
    $("#sudo-dialog").showModal();
    return;
  }
  if (code !== 200) { toast(data.error || `Failed (${code})`); return; }
  jobs.unshift(data.job);
  lastSig = "";
  render();
  toast(`${data.job.label}: started. Output under Activity.`);
}

function confirmFirst(text) {
  return new Promise((resolve) => {
    const d = $("#confirm-dialog");
    $("#confirm-text").textContent = text;
    d.returnValue = "";
    d.addEventListener("close", () => resolve(d.returnValue === "ok"), { once: true });
    d.showModal();
  });
}

async function showLog(name) {
  const d = $("#log-dialog");
  d.dataset.name = name;
  $("#log-title").textContent = `Log: ${name}`;
  $("#log-text").textContent = "Loading...";
  if (!d.open) d.showModal();
  const { data } = await api(`/api/log?name=${encodeURIComponent(name)}`);
  $("#log-text").textContent = data.text || "(empty)";
  $("#log-text").scrollTop = $("#log-text").scrollHeight;
}

document.addEventListener("click", async (ev) => {
  const logBtn = ev.target.closest("[data-log]");
  if (logBtn) { showLog(logBtn.dataset.log); return; }
  const btn = ev.target.closest("[data-action]");
  if (!btn || btn.disabled) return;
  const { action, arg, confirm } = btn.dataset;
  if (confirm && !(await confirmFirst(confirm))) return;
  if (btn.dataset.admin) toast("Windows will ask for administrator rights.");
  runAction(action, arg);
});

$("#refresh").addEventListener("click", async () => {
  const b = $("#refresh");
  b.disabled = true;
  b.setAttribute("aria-busy", "true");
  await api("/api/refresh", {});
  setTimeout(() => { b.disabled = false; b.setAttribute("aria-busy", "false"); }, 4000);
});

$("#log-reload").addEventListener("click", () => showLog($("#log-dialog").dataset.name));
$("#log-close").addEventListener("click", () => $("#log-dialog").close());

$("#sudo-form").addEventListener("submit", async (ev) => {
  if (ev.submitter?.value !== "ok") return;
  ev.preventDefault();
  const pw = $("#sudo-input").value;
  if (!pw) {
    $("#sudo-input").setAttribute("aria-invalid", "true");
    $("#sudo-error").textContent = "Enter the password.";
    $("#sudo-error").hidden = false;
    return;
  }
  await api("/api/sudo", { password: pw });
  $("#sudo-dialog").close();
  if (pending) { const p = pending; pending = null; runAction(p.action, p.arg); }
});

poll();
