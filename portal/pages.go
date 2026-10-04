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
)

func render(w http.ResponseWriter, status int, t *template.Template, data any) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	if err := t.Execute(w, data); err != nil {
		log.Printf("render %s: %v", t.Name(), err)
	}
}
