package panelurl

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestParseAndBuildURLs(t *testing.T) {
	cases := []struct {
		name     string
		raw      string
		wantTLS  bool
		wantWS   string
		wantHTTP string
	}{
		{
			name:     "ipv4 host port",
			raw:      "1.2.3.4:6365",
			wantTLS:  false,
			wantWS:   "ws://1.2.3.4:6365/system-info?type=1",
			wantHTTP: "http://1.2.3.4:6365/flow/upload",
		},
		{
			name:     "ipv6 host port",
			raw:      "[2001:db8::1]:6365",
			wantTLS:  false,
			wantWS:   "ws://[2001:db8::1]:6365/system-info?type=1",
			wantHTTP: "http://[2001:db8::1]:6365/flow/upload",
		},
		{
			name:     "https domain",
			raw:      "https://panel.example.com",
			wantTLS:  true,
			wantWS:   "wss://panel.example.com/system-info?type=1",
			wantHTTP: "https://panel.example.com/flow/upload",
		},
		{
			name:     "https domain with port and trailing slash",
			raw:      "HTTPS://panel.example.com:8443/",
			wantTLS:  true,
			wantWS:   "wss://panel.example.com:8443/system-info?type=1",
			wantHTTP: "https://panel.example.com:8443/flow/upload",
		},
		{
			name:     "https ipv6 with subpath",
			raw:      "https://[2001:db8::1]:8443/sub/",
			wantTLS:  true,
			wantWS:   "wss://[2001:db8::1]:8443/sub/system-info?type=1",
			wantHTTP: "https://[2001:db8::1]:8443/sub/flow/upload",
		},
		{
			name:     "wss scheme",
			raw:      "wss://panel.example.com",
			wantTLS:  true,
			wantWS:   "wss://panel.example.com/system-info?type=1",
			wantHTTP: "https://panel.example.com/flow/upload",
		},
		{
			name:     "http and ws schemes",
			raw:      "ws://panel.example.com:6365",
			wantTLS:  false,
			wantWS:   "ws://panel.example.com:6365/system-info?type=1",
			wantHTTP: "http://panel.example.com:6365/flow/upload",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ep, err := Parse(tc.raw)
			if err != nil {
				t.Fatalf("Parse(%q) unexpected error: %v", tc.raw, err)
			}
			if ep.IsTLS != tc.wantTLS {
				t.Fatalf("IsTLS = %v, want %v", ep.IsTLS, tc.wantTLS)
			}
			if got := ep.WebSocketURL("/system-info", "type=1"); got != tc.wantWS {
				t.Fatalf("WebSocketURL = %q, want %q", got, tc.wantWS)
			}
			if got := ep.HTTPURL("/flow/upload", ""); got != tc.wantHTTP {
				t.Fatalf("HTTPURL = %q, want %q", got, tc.wantHTTP)
			}
		})
	}

	if _, err := Parse("   "); err == nil {
		t.Fatal("expected empty address to fail")
	}
}

func TestSameOrigin(t *testing.T) {
	mustURL := func(raw string) *url.URL {
		u, err := url.Parse(raw)
		if err != nil {
			t.Fatal(err)
		}
		return u
	}

	if !SameOrigin(mustURL("https://Panel.Example.com/a"), mustURL("https://panel.example.com:443/b")) {
		t.Fatal("expected implicit :443 to match explicit :443")
	}
	if !SameOrigin(mustURL("http://example.com:80/a"), mustURL("http://example.com/b")) {
		t.Fatal("expected implicit :80 to match explicit :80")
	}
	if SameOrigin(mustURL("https://example.com/a"), mustURL("http://example.com/a")) {
		t.Fatal("expected https -> http downgrade to be cross-origin")
	}
	if SameOrigin(mustURL("http://example.com/a"), mustURL("https://example.com/a")) {
		t.Fatal("expected http -> https scheme change to be cross-origin")
	}
	if SameOrigin(mustURL("https://example.com/a"), mustURL("https://evil.com/a")) {
		t.Fatal("expected different host to be cross-origin")
	}
	if SameOrigin(mustURL("https://example.com:8443/a"), mustURL("https://example.com:9443/a")) {
		t.Fatal("expected different port to be cross-origin")
	}
}

func TestNewReportRequestHeaderAndFallback(t *testing.T) {
	epHTTP, err := Parse("127.0.0.1:6365")
	if err != nil {
		t.Fatal(err)
	}
	reqNoFallback, err := epHTTP.NewReportRequest(context.Background(), "/flow/upload", []byte("{}"), "sec-1", "ua", false)
	if err != nil {
		t.Fatal(err)
	}
	if reqNoFallback.Header.Get(SecretHeader) != "sec-1" {
		t.Fatal("expected X-Node-Secret header")
	}
	if reqNoFallback.URL.Query().Get("secret") != "" {
		t.Fatalf("unexpected query secret when fallback disabled: %s", reqNoFallback.URL.String())
	}

	reqFallback, err := epHTTP.NewReportRequest(context.Background(), "/flow/upload", []byte("{}"), "sec-1", "ua", true)
	if err != nil {
		t.Fatal(err)
	}
	if reqFallback.URL.Query().Get("secret") != "sec-1" {
		t.Fatalf("expected legacy query secret on non-TLS when enabled: %s", reqFallback.URL.String())
	}

	epHTTPS, err := Parse("https://panel.example.com")
	if err != nil {
		t.Fatal(err)
	}
	reqHTTPS, err := epHTTPS.NewReportRequest(context.Background(), "/flow/upload", []byte("{}"), "sec-1", "ua", true)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(reqHTTPS.URL.String(), "https://panel.example.com/flow/upload") {
		t.Fatalf("unexpected HTTPS URL: %s", reqHTTPS.URL.String())
	}
	if reqHTTPS.URL.Query().Get("secret") != "" {
		t.Fatalf("must not append secret to HTTPS query string: %s", reqHTTPS.URL.String())
	}
	if reqHTTPS.Header.Get(SecretHeader) != "sec-1" {
		t.Fatal("expected X-Node-Secret header on HTTPS request")
	}
}

func TestNewHTTPClientBlocksCrossOriginRedirectWithoutLeakingSecret(t *testing.T) {
	var foreignHits int
	var leakedSecret string
	foreignSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		foreignHits++
		leakedSecret = r.Header.Get(SecretHeader)
		w.WriteHeader(http.StatusOK)
	}))
	defer foreignSrv.Close()

	var sameOriginHeader string
	panelSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/flow/upload":
			http.Redirect(w, r, foreignSrv.URL+"/steal", http.StatusTemporaryRedirect)
		case "/flow/same":
			http.Redirect(w, r, "/flow/ok", http.StatusTemporaryRedirect)
		case "/flow/ok":
			sameOriginHeader = r.Header.Get(SecretHeader)
			_, _ = w.Write([]byte("ok"))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer panelSrv.Close()

	ep, err := Parse(panelSrv.URL)
	if err != nil {
		t.Fatal(err)
	}
	client := NewHTTPClient(5 * time.Second)

	req, err := ep.NewReportRequest(context.Background(), "/flow/upload", []byte("{}"), "top-secret-node", "ua", false)
	if err != nil {
		t.Fatal(err)
	}
	if resp, err := client.Do(req); err == nil {
		resp.Body.Close()
		t.Fatal("expected cross-origin redirect to be rejected")
	}
	if foreignHits != 0 || leakedSecret != "" {
		t.Fatalf("cross-origin server was contacted (%d hits, secret=%q)", foreignHits, leakedSecret)
	}

	sameReq, err := ep.NewReportRequest(context.Background(), "/flow/same", []byte("{}"), "top-secret-node", "ua", false)
	if err != nil {
		t.Fatal(err)
	}
	sameResp, err := client.Do(sameReq)
	if err != nil {
		t.Fatalf("expected same-origin redirect to succeed, got err: %v", err)
	}
	sameResp.Body.Close()
	if sameOriginHeader != "top-secret-node" {
		t.Fatalf("same-origin redirected request secret = %q, want top-secret-node", sameOriginHeader)
	}
}

func TestSanitizeErrorRedactsSecretInLegacyQueryMode(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	deadURL := srv.URL
	srv.Close()

	ep, err := Parse(deadURL)
	if err != nil {
		t.Fatal(err)
	}
	secret := "synth-secret+abc/123="
	req, err := ep.NewReportRequest(context.Background(), "/flow/upload", []byte("{}"), secret, "ua", true)
	if err != nil {
		t.Fatal(err)
	}
	_, doErr := NewHTTPClient(time.Second).Do(req)
	if doErr == nil {
		t.Fatal("expected request to closed test server to fail")
	}
	if !strings.Contains(doErr.Error(), url.QueryEscape(secret)) {
		t.Fatalf("expected raw *url.Error to contain query secret before sanitization, got: %v", doErr)
	}
	sanitized := SanitizeError(doErr, secret)
	if strings.Contains(sanitized, secret) || strings.Contains(sanitized, url.QueryEscape(secret)) {
		t.Fatalf("SanitizeError leaked secret: %s", sanitized)
	}
	if !strings.Contains(sanitized, "secret=%2A%2A%2A") && !strings.Contains(sanitized, "secret=***") {
		t.Fatalf("expected redacted secret parameter in sanitized error, got: %s", sanitized)
	}
}

func TestNewHTTPClientAllowsOnly307And308SameOriginRedirects(t *testing.T) {
	var hit301Target int
	var got307Method, got307Body, got307Secret string
	var got308Method, got308Body, got308Secret string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/r301":
			http.Redirect(w, r, "/target301", http.StatusMovedPermanently)
		case "/r302":
			http.Redirect(w, r, "/target301", http.StatusFound)
		case "/target301":
			hit301Target++
			_, _ = w.Write([]byte("ok"))
		case "/r307":
			http.Redirect(w, r, "/target307", http.StatusTemporaryRedirect)
		case "/target307":
			raw, _ := io.ReadAll(r.Body)
			got307Method = r.Method
			got307Body = string(raw)
			got307Secret = r.Header.Get(SecretHeader)
			_, _ = w.Write([]byte("ok"))
		case "/r308":
			http.Redirect(w, r, "/target308", http.StatusPermanentRedirect)
		case "/target308":
			raw, _ := io.ReadAll(r.Body)
			got308Method = r.Method
			got308Body = string(raw)
			got308Secret = r.Header.Get(SecretHeader)
			_, _ = w.Write([]byte("ok"))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	ep, err := Parse(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	client := NewHTTPClient(5 * time.Second)
	payload := `{"n":"svc","u":1}`

	for _, path := range []string{"/r301", "/r302"} {
		req, err := ep.NewReportRequest(context.Background(), path, []byte(payload), "sec-1", "ua", false)
		if err != nil {
			t.Fatal(err)
		}
		if resp, err := client.Do(req); err == nil {
			resp.Body.Close()
			t.Fatalf("expected %s redirect to be rejected", path)
		}
	}
	if hit301Target != 0 {
		t.Fatalf("expected 301/302 target not to be reached, got %d hits", hit301Target)
	}

	req307, err := ep.NewReportRequest(context.Background(), "/r307", []byte(payload), "sec-307", "ua", false)
	if err != nil {
		t.Fatal(err)
	}
	resp307, err := client.Do(req307)
	if err != nil {
		t.Fatalf("expected 307 redirect to succeed, got: %v", err)
	}
	resp307.Body.Close()
	if got307Method != http.MethodPost || got307Body != payload || got307Secret != "sec-307" {
		t.Fatalf("unexpected 307 forwarded request: method=%s body=%q secret=%q", got307Method, got307Body, got307Secret)
	}

	req308, err := ep.NewReportRequest(context.Background(), "/r308", []byte(payload), "sec-308", "ua", false)
	if err != nil {
		t.Fatal(err)
	}
	resp308, err := client.Do(req308)
	if err != nil {
		t.Fatalf("expected 308 redirect to succeed, got: %v", err)
	}
	resp308.Body.Close()
	if got308Method != http.MethodPost || got308Body != payload || got308Secret != "sec-308" {
		t.Fatalf("unexpected 308 forwarded request: method=%s body=%q secret=%q", got308Method, got308Body, got308Secret)
	}
}

func TestValidateReportResponseDoesNotLeakBody(t *testing.T) {
	if err := ValidateReportResponse(http.StatusOK, strings.NewReader(" ok\n")); err != nil {
		t.Fatalf("expected valid ok response to pass, got: %v", err)
	}

	proxyLeak := "<html>error for http://panel/flow/upload?secret=synth-secret-999</html>"
	err200 := ValidateReportResponse(http.StatusOK, strings.NewReader(proxyLeak))
	if err200 == nil {
		t.Fatal("expected non-ok 200 body to fail")
	}
	if !strings.Contains(err200.Error(), "200") || strings.Contains(err200.Error(), "synth-secret-999") || strings.Contains(err200.Error(), "<html>") {
		t.Fatalf("unexpected 200 error message (must contain status only, no body): %s", err200.Error())
	}

	err502 := ValidateReportResponse(http.StatusBadGateway, strings.NewReader(proxyLeak))
	if err502 == nil {
		t.Fatal("expected 502 status to fail")
	}
	if !strings.Contains(err502.Error(), "502") || strings.Contains(err502.Error(), "synth-secret-999") {
		t.Fatalf("unexpected 502 error message: %s", err502.Error())
	}
}
