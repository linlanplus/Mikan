package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"

	"github.com/xtls/xray-core/infra/conf"
	"gopkg.in/yaml.v3"
)

type node struct {
	Name     string `json:"name"`
	Protocol string `json:"protocol"`
	Outbound object `json:"outbound"`
}
type catalog struct {
	Nodes   []node `json:"nodes"`
	Skipped int    `json:"skipped"`
}

var invalid = errors.New("unsupported node")

func field(m object, keys ...string) string {
	for _, k := range keys {
		switch v := m[k].(type) {
		case string:
			if v != "" {
				return v
			}
		case int, int64, uint64, float64, bool, json.Number:
			return fmt.Sprint(v)
		}
	}
	return ""
}
func flag(m object, keys ...string) bool {
	v, _ := strconv.ParseBool(field(m, keys...))
	return v
}
func child(m object, k string) object { v, _ := m[k].(map[string]any); return v }
func fallback(v, otherwise string) string {
	if v == "" {
		return otherwise
	}
	return v
}
func decode64(s string) ([]byte, error) {
	s = strings.Map(func(r rune) rune {
		if r == '\r' || r == '\n' || r == ' ' || r == '\t' {
			return -1
		}
		return r
	}, s)
	s = strings.NewReplacer("-", "+", "_", "/").Replace(s)
	return base64.RawStdEncoding.DecodeString(strings.TrimRight(s, "="))
}
func parseSubscription(data []byte) (catalog, error) {
	var result catalog
	data = bytes.TrimSpace(bytes.TrimPrefix(data, []byte{0xef, 0xbb, 0xbf}))
	if len(data) == 0 {
		return result, invalid
	}
	if !bytes.Contains(data, []byte("://")) && !bytes.Contains(data, []byte("proxies")) {
		if decoded, err := decode64(string(data)); err == nil {
			data = bytes.TrimSpace(decoded)
		}
	}
	var entries []object
	if bytes.Contains(data, []byte("proxies")) {
		var doc struct {
			Proxies []object `yaml:"proxies"`
		}
		if yaml.Unmarshal(data, &doc) == nil && len(doc.Proxies) > 0 {
			entries = doc.Proxies
		}
	}
	if len(entries) == 0 {
		for _, line := range strings.Split(string(data), "\n") {
			line = strings.TrimSpace(line)
			if line == "" || strings.HasPrefix(line, "#") {
				continue
			}
			m, err := shareLink(line)
			if err != nil {
				result.Skipped++
				continue
			}
			entries = append(entries, m)
		}
	}
	if len(entries) > 4096 {
		return result, invalid
	}
	for _, m := range entries {
		n, err := convert(m)
		if err != nil {
			result.Skipped++
			continue
		}
		result.Nodes = append(result.Nodes, n)
	}
	if len(result.Nodes) == 0 {
		return result, invalid
	}
	return result, nil
}

func shareLink(raw string) (object, error) {
	scheme, rest, ok := strings.Cut(raw, "://")
	if !ok {
		return nil, invalid
	}
	scheme = strings.ToLower(scheme)
	if scheme == "vmess" {
		payload, frag, _ := strings.Cut(rest, "#")
		b, err := decode64(payload)
		if err != nil {
			return nil, err
		}
		var v object
		if err = json.Unmarshal(b, &v); err != nil {
			return nil, err
		}
		m := object{"type": "vmess", "server": field(v, "add"), "port": field(v, "port"), "uuid": field(v, "id"), "name": field(v, "ps"), "cipher": field(v, "scy"), "network": field(v, "net"), "tls": field(v, "tls") == "tls", "servername": field(v, "sni"), "client-fingerprint": field(v, "fp"), "alpn": v["alpn"], "header": field(v, "type"), "path": field(v, "path"), "host": field(v, "host")}
		if m["name"] == "" {
			m["name"], _ = url.PathUnescape(frag)
		}
		if field(m, "network") == "grpc" {
			m["grpc-opts"] = object{"grpc-service-name": field(v, "path")}
		}
		if flag(v, "allowInsecure") {
			m["skip-cert-verify"] = true
		}
		return m, nil
	}
	if scheme == "ss" && !strings.Contains(strings.SplitN(rest, "#", 2)[0], "@") {
		v, frag, _ := strings.Cut(rest, "#")
		plain, err := decode64(v)
		if err != nil {
			return nil, err
		}
		auth, addr, ok := cutLast(string(plain), "@")
		if !ok {
			return nil, invalid
		}
		raw = "ss://" + base64.RawURLEncoding.EncodeToString([]byte(auth)) + "@" + addr + "#" + frag
	}
	u, err := url.Parse(raw)
	if err != nil || u.Hostname() == "" {
		return nil, invalid
	}
	q := u.Query()
	network := q.Get("type")
	security := q.Get("security")
	if security != "" && security != "none" && security != "tls" && security != "reality" {
		return nil, invalid
	}
	m := object{"type": scheme, "name": u.Fragment, "server": u.Hostname(), "port": u.Port(), "network": network, "tls": security == "tls" || security == "reality", "servername": q.Get("sni"), "client-fingerprint": q.Get("fp"), "host": q.Get("host"), "path": q.Get("path"), "alpn": q.Get("alpn"), "header": q.Get("headerType"), "mode": q.Get("mode"), "pinned": q.Get("pcs")}
	if b, _ := strconv.ParseBool(q.Get("allowInsecure")); b {
		m["skip-cert-verify"] = true
	}
	if security == "reality" {
		m["reality-opts"] = object{"public-key": q.Get("pbk"), "short-id": q.Get("sid"), "spider-x": q.Get("spx")}
	}
	if network == "grpc" {
		m["grpc-opts"] = object{"grpc-service-name": q.Get("serviceName"), "authority": q.Get("authority")}
	}
	user := ""
	pass := ""
	hasPass := false
	if u.User != nil {
		user = u.User.Username()
		pass, hasPass = u.User.Password()
	}
	switch scheme {
	case "vless":
		m["uuid"] = user
		m["flow"] = q.Get("flow")
		m["encryption"] = q.Get("encryption")
	case "trojan":
		m["password"] = user
		if hasPass {
			m["password"] = user + ":" + pass
		}
		m["tls"] = security != "none"
	case "ss":
		if !hasPass {
			b, e := decode64(user)
			if e != nil {
				return nil, e
			}
			user, pass, hasPass = strings.Cut(string(b), ":")
			if !hasPass {
				return nil, invalid
			}
		}
		m["cipher"] = user
		m["password"] = pass
		if p := q.Get("plugin"); p != "" {
			parts := strings.Split(p, ";")
			m["plugin"] = parts[0]
			opts := object{}
			for _, part := range parts[1:] {
				k, v, ok := strings.Cut(part, "=")
				if ok {
					opts[k] = v
				} else {
					opts[k] = true
				}
			}
			m["plugin-opts"] = opts
		}
	case "socks", "socks5":
		m["type"] = "socks5"
		m["username"] = user
		m["password"] = pass
	default:
		return nil, invalid
	}
	return m, nil
}

func cutLast(s, sep string) (string, string, bool) {
	i := strings.LastIndex(s, sep)
	if i < 0 {
		return "", "", false
	}
	return s[:i], s[i+len(sep):], true
}

func convert(m object) (node, error) {
	var n node
	addr := field(m, "server")
	port, err := strconv.Atoi(field(m, "port"))
	if err != nil || port < 1 || port > 65535 || addr == "" || strings.ContainsAny(addr, " \t\r\n/") {
		return n, invalid
	}
	if field(m, "dialer-proxy") != "" {
		return n, invalid
	}
	proto := strings.ToLower(field(m, "type"))
	settings := object{"address": addr, "port": port}
	switch proto {
	case "vmess", "vless":
		id := field(m, "uuid")
		if id == "" {
			return n, invalid
		}
		settings["id"] = id
		if proto == "vmess" {
			settings["security"] = fallback(field(m, "cipher"), "auto")
		} else {
			settings["encryption"] = fallback(field(m, "encryption"), "none")
			settings["flow"] = field(m, "flow")
		}
	case "trojan", "ss":
		p := field(m, "password")
		if p == "" {
			return n, invalid
		}
		settings["password"] = p
		if proto == "ss" {
			proto = "shadowsocks"
			method := field(m, "cipher")
			if method == "" {
				return n, invalid
			}
			settings["method"] = method
		}
	case "socks5", "socks":
		proto = "socks"
		settings["user"] = field(m, "username")
		settings["pass"] = field(m, "password")
	default:
		return n, invalid
	}
	stream, err := transport(m)
	if err != nil {
		return n, err
	}
	outbound := object{"protocol": proto, "settings": settings, "streamSettings": stream}
	// Reject individual entries the bundled core cannot build, without losing the remaining nodes.
	b, err := json.Marshal(outbound)
	if err != nil {
		return n, err
	}
	var parsed conf.OutboundDetourConfig
	if err = json.Unmarshal(b, &parsed); err != nil {
		return n, err
	}
	if _, err = parsed.Build(); err != nil {
		return n, err
	}
	return node{fallback(field(m, "name"), net.JoinHostPort(addr, strconv.Itoa(port))), proto, outbound}, nil
}

func transport(m object) (object, error) {
	network := fallback(field(m, "network"), "tcp")
	tls := flag(m, "tls") || (field(m, "type") == "trojan" && field(m, "tls") == "")
	host, path := field(m, "host"), field(m, "path")
	if p := field(m, "plugin"); p != "" {
		if p != "v2ray-plugin" {
			return nil, invalid
		}
		opts := child(m, "plugin-opts")
		if mode := field(opts, "mode"); mode != "" && mode != "websocket" {
			return nil, invalid
		}
		if flag(opts, "mux") {
			return nil, invalid
		}
		network = "ws"
		host = field(opts, "host")
		path = field(opts, "path")
		tls = flag(opts, "tls")
	}
	s := object{"network": network}
	switch network {
	case "tcp", "raw":
		if header := field(m, "header"); header == "http" {
			req := object{}
			if path != "" {
				req["path"] = strings.Split(path, ",")
			}
			if host != "" {
				req["headers"] = object{"Host": strings.Split(host, ",")}
			}
			s["tcpSettings"] = object{"header": object{"type": "http", "request": req}}
		} else if header != "" && header != "none" {
			return nil, invalid
		}
	case "ws", "httpupgrade", "xhttp", "splithttp":
		key := "wsSettings"
		optsName := "ws-opts"
		if network == "httpupgrade" {
			key = "httpupgradeSettings"
			optsName = "http-upgrade-opts"
		}
		if network == "xhttp" || network == "splithttp" {
			key = "xhttpSettings"
			optsName = "xhttp-opts"
		}
		opts := child(m, optsName)
		headers := child(opts, "headers")
		host = fallback(field(opts, "host"), host)
		if headers != nil {
			host = fallback(field(headers, "Host", "host"), host)
		}
		t := object{"path": fallback(field(opts, "path"), fallback(path, "/")), "host": host}
		if headers != nil {
			t["headers"] = headers
		}
		if mode := fallback(field(opts, "mode"), field(m, "mode")); mode != "" && key == "xhttpSettings" {
			t["mode"] = mode
		}
		if ed := field(opts, "max-early-data"); ed != "" && key == "wsSettings" {
			if h := field(opts, "early-data-header-name"); h != "" && !strings.EqualFold(h, "Sec-WebSocket-Protocol") {
				return nil, invalid
			}
			if u, e := url.Parse(t["path"].(string)); e == nil {
				q := u.Query()
				q.Set("ed", ed)
				u.RawQuery = q.Encode()
				t["path"] = u.String()
			}
		}
		s[key] = t
	case "grpc":
		opts := child(m, "grpc-opts")
		s["grpcSettings"] = object{"serviceName": field(opts, "grpc-service-name"), "authority": field(opts, "authority"), "multiMode": field(m, "mode") == "multi"}
	default:
		return nil, invalid
	}
	sni := fallback(field(m, "servername", "sni"), host)
	fp := field(m, "client-fingerprint")
	if reality := child(m, "reality-opts"); reality != nil {
		key := field(reality, "public-key")
		if key == "" {
			return nil, invalid
		}
		s["security"] = "reality"
		s["realitySettings"] = object{"serverName": sni, "fingerprint": fallback(fp, "chrome"), "publicKey": key, "shortId": field(reality, "short-id"), "spiderX": fallback(field(reality, "spider-x"), "/")}
	} else if tls {
		t := object{"serverName": sni}
		if fp != "" {
			t["fingerprint"] = fp
		}
		if a, ok := m["alpn"].([]any); ok && len(a) > 0 {
			t["alpn"] = a
		} else if alpn := field(m, "alpn"); alpn != "" {
			t["alpn"] = strings.Split(alpn, ",")
		}
		if pin := field(m, "pinned"); pin != "" {
			t["pinnedPeerCertSha256"] = pin
		} else if flag(m, "skip-cert-verify") {
			t["allowInsecure"] = true
		}
		s["security"] = "tls"
		s["tlsSettings"] = t
	}
	return s, nil
}
