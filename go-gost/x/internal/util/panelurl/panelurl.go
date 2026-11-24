package panelurl

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const SecretHeader = "X-Node-Secret"

type Endpoint struct {
	HTTPScheme string
	WSScheme   string
	IsTLS      bool
	Host       string
	BasePath   string
}

func Parse(rawAddr string) (Endpoint, error) {
	trimmed := strings.TrimSpace(rawAddr)
	trimmed = strings.TrimRight(trimmed, "/")
	if trimmed == "" {
		return Endpoint{}, errors.New("面板地址不能为空")
	}

	lower := strings.ToLower(trimmed)
	ep := Endpoint{
		HTTPScheme: "http",
		WSScheme:   "ws",
	}
	parseTarget := trimmed
	switch {
	case strings.HasPrefix(lower, "https://"):
		ep.HTTPScheme = "https"
		ep.WSScheme = "wss"
		ep.IsTLS = true
		parseTarget = "https://" + trimmed[len("https://"):]
	case strings.HasPrefix(lower, "wss://"):
		ep.HTTPScheme = "https"
		ep.WSScheme = "wss"
		ep.IsTLS = true
		parseTarget = "https://" + trimmed[len("wss://"):]
	case strings.HasPrefix(lower, "http://"):
		parseTarget = "http://" + trimmed[len("http://"):]
	case strings.HasPrefix(lower, "ws://"):
		parseTarget = "http://" + trimmed[len("ws://"):]
	default:
		parseTarget = "http://" + trimmed
	}

	u, err := url.Parse(parseTarget)
	if err != nil || u.Host == "" {
		return Endpoint{}, errors.New("面板地址格式无效")
	}

	ep.Host = u.Host
	ep.BasePath = strings.TrimRight(u.Path, "/")
	return ep, nil
}

func (e Endpoint) WebSocketURL(endpointPath, rawQuery string) string {
	return e.buildURL(e.WSScheme, endpointPath, rawQuery)
}

func (e Endpoint) HTTPURL(endpointPath, rawQuery string) string {
	return e.buildURL(e.HTTPScheme, endpointPath, rawQuery)
}

func (e Endpoint) buildURL(scheme, endpointPath, rawQuery string) string {
	path := e.BasePath + endpointPath
	u := scheme + "://" + e.Host + path
	if rawQuery != "" {
		u += "?" + rawQuery
	}
	return u
}

func (e Endpoint) NewReportRequest(ctx context.Context, endpointPath string, body []byte, secret, userAgent string, allowQueryFallback bool) (*http.Request, error) {
	rawQuery := ""
	if !e.IsTLS && allowQueryFallback && secret != "" {
		rawQuery = "secret=" + url.QueryEscape(secret)
	}
	targetURL := e.HTTPURL(endpointPath, rawQuery)
	req, err := http.NewRequestWithContext(ctx, "POST", targetURL, bytes.NewBuffer(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", userAgent)
	if secret != "" {
		req.Header.Set(SecretHeader, secret)
	}
	return req, nil
}

func SanitizeURL(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return raw
	}
	q := u.Query()
	if q.Has("secret") {
		q.Set("secret", "***")
		u.RawQuery = q.Encode()
	}
	return u.String()
}

func SanitizeError(err error, secret string) string {
	if err == nil {
		return ""
	}
	var msg string
	var urlErr *url.Error
	if errors.As(err, &urlErr) && urlErr.URL != "" {
		cleanErr := &url.Error{
			Op:  urlErr.Op,
			URL: SanitizeURL(urlErr.URL),
			Err: urlErr.Err,
		}
		msg = cleanErr.Error()
	} else {
		msg = err.Error()
	}
	if secret != "" {
		if escaped := url.QueryEscape(secret); escaped != "" {
			msg = strings.ReplaceAll(msg, escaped, "***")
		}
		msg = strings.ReplaceAll(msg, secret, "***")
	}
	return msg
}

func SameOrigin(a, b *url.URL) bool {
	if a == nil || b == nil {
		return false
	}
	return strings.EqualFold(a.Scheme, b.Scheme) &&
		strings.EqualFold(a.Hostname(), b.Hostname()) &&
		effectivePort(a) == effectivePort(b)
}

func effectivePort(u *url.URL) string {
	if port := u.Port(); port != "" {
		return port
	}
	switch strings.ToLower(u.Scheme) {
	case "https", "wss":
		return "443"
	case "http", "ws":
		return "80"
	default:
		return ""
	}
}

func NewHTTPClient(timeout time.Duration) *http.Client {
	return &http.Client{
		Timeout: timeout,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 10 {
				return errors.New("stopped after 10 redirects")
			}
			if len(via) > 0 && !SameOrigin(via[0].URL, req.URL) {
				req.Header.Del(SecretHeader)
				return fmt.Errorf("拒绝跨源重定向: %s -> %s", via[0].URL.Host, req.URL.Host)
			}
			if req.Response != nil &&
				req.Response.StatusCode != http.StatusTemporaryRedirect &&
				req.Response.StatusCode != http.StatusPermanentRedirect {
				return fmt.Errorf("拒绝不支持保留请求体的重定向（状态码 %d）", req.Response.StatusCode)
			}
			if len(via) > 0 && !strings.EqualFold(req.Method, via[0].Method) {
				return fmt.Errorf("拒绝变更请求方法的重定向: %s -> %s", via[0].Method, req.Method)
			}
			return nil
		},
	}
}

func ValidateReportResponse(statusCode int, body io.Reader) error {
	if statusCode != http.StatusOK {
		return fmt.Errorf("HTTP响应错误: 状态码 %d", statusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(body, 32))
	if err != nil {
		return errors.New("读取响应内容失败")
	}
	if strings.TrimSpace(string(raw)) != "ok" {
		return fmt.Errorf("服务器返回异常响应（状态码 %d，期望: ok）", statusCode)
	}
	return nil
}
