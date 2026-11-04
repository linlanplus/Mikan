package main

import (
	"encoding/base64"
	"strings"
	"testing"
)

func TestShareSubscription(t *testing.T) {
	const id = "d6f6e076-7ebe-42e7-a99c-892aa54fb454"
	vm := base64.StdEncoding.EncodeToString([]byte(`{"ps":"VM","add":"127.0.0.1","port":"443","id":"` + id + `","net":"ws","path":"/m","host":"example.org","tls":"tls","sni":"example.org"}`))
	ss := base64.RawURLEncoding.EncodeToString([]byte("aes-128-gcm:pw:with:colon"))
	links := strings.Join([]string{"vmess://" + vm, "vless://" + id + "@[::1]:443?security=tls&type=grpc&serviceName=svc#VL",
		"trojan://p%40ss@localhost:443?sni=example.org#TR", "ss://" + ss + "@127.0.0.1:8388#SS", "unknown://invalid"}, "\r\n")
	for _, input := range []string{links, base64.RawURLEncoding.EncodeToString([]byte(links))} {
		got, err := parseSubscription([]byte(input))
		if err != nil || len(got.Nodes) != 4 || got.Skipped != 1 {
			t.Fatalf("imported %d, skipped %d: %v", len(got.Nodes), got.Skipped, err)
		}
		if got.Nodes[0].Outbound["streamSettings"].(object)["wsSettings"].(object)["path"] != "/m" ||
			got.Nodes[1].Outbound["streamSettings"].(object)["grpcSettings"].(object)["serviceName"] != "svc" ||
			got.Nodes[2].Outbound["settings"].(object)["password"] != "p@ss" ||
			got.Nodes[3].Outbound["settings"].(object)["password"] != "pw:with:colon" {
			t.Fatal("transport or credential lost on import")
		}
	}
}

func TestClashMixedAndMalformed(t *testing.T) {
	data := `# https://example.org/subscriptions
proxies:
  - {name: "one, two", type: ss, server: 127.0.0.1, port: 10000, cipher: aes-128-gcm, password: "a:b#c"}
  - {name: unsupported, type: hysteria2, server: localhost, port: 443}
  - {name: invalid, type: vless, server: localhost, port: 0}
`
	c, err := parseSubscription([]byte(data))
	if err != nil || len(c.Nodes) != 1 || c.Nodes[0].Name != "one, two" || c.Skipped != 2 {
		t.Fatal("mixed catalog was not preserved")
	}
	for _, s := range []string{"<html>login required</html>", "proxies: []", "ss://broken", "trojan://@host:443"} {
		if _, e := parseSubscription([]byte(s)); e == nil {
			t.Fatal("unexpectedly accepted an empty/invalid catalog")
		}
	}
}
