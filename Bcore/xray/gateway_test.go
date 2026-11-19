package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"testing"
	"time"

	"github.com/miekg/dns"
)

type testBridge struct {
	policy      policy
	err         error
	directCalls int
}

func (b *testBridge) route(flow, bool) (policy, error)       { return b.policy, b.err }
func (b *testBridge) directAnswers(string) ([]string, error) { b.directCalls++; return nil, denied }
func (b *testBridge) direct(context.Context, string, string) (net.Conn, error) {
	b.directCalls++
	return nil, errors.New("unexpected direct dial")
}

func TestFailedRoutesNeverUseDirect(t *testing.T) {
	for _, c := range []struct {
		name string
		p    policy
		err  error
	}{
		{"unknown", policy{}, denied},
		{"removed", policy{Owner: "one", Mode: "blocked"}, nil},
		{"no-udp", policy{Owner: "one", Mode: "node", UDP: false}, nil},
		{"unusable-node", policy{Owner: "one", Mode: "node", UDP: true, Outbound: json.RawMessage("{")}, nil},
		{"direct-outbound-in-node", policy{Owner: "one", Mode: "node", UDP: true, Outbound: json.RawMessage("{\"protocol\":\"freedom\"}")}, nil},
	} {
		t.Run(c.name, func(t *testing.T) {
			b := &testBridge{policy: c.p, err: c.err}
			g := gateway{bridge: b}
			a, z := net.Pipe()
			defer z.Close()
			go g.handle(flow{Proto: "udp", Dst: "8.8.8.8", DP: 53}, a)
			z.SetDeadline(time.Now().Add(2 * time.Second))
			var raw [1]byte
			if _, e := z.Read(raw[:]); e != io.EOF {
				t.Fatalf("expected a closed route: %v", e)
			}
			if b.directCalls != 0 {
				t.Fatal("attempted direct fallback")
			}
		})
	}
}
func TestGuestDNSUsesSelectedTCPOnlyNode(t *testing.T) {
	// Each SOCKS node returns its own answer for exactly the same question.
	for _, answer := range []string{"203.0.113.11", "203.0.113.22"} {
		l, e := net.Listen("tcp4", "127.0.0.1:0")
		if e != nil {
			t.Fatal(e)
		}
		done := make(chan error, 1)
		go func() { done <- serveTestDNS(l, answer) }()
		out, _ := json.Marshal(object{"protocol": "socks", "settings": object{"address": "qa-node.invalid", "port": l.Addr().(*net.TCPAddr).Port}})
		p := policy{Mode: "node", Owner: "guest", Pin: "127.0.0.1", UDP: false, Outbound: out}
		b := &testBridge{policy: p}
		g := gateway{bridge: b}
		q := new(dns.Msg)
		q.SetQuestion("same.mikan.test.", dns.TypeA)
		wire, _ := q.Pack()
		raw := g.exchangeDNS(p, q, wire)
		l.Close()
		var m dns.Msg
		if len(raw) == 0 || m.Unpack(raw) != nil || len(m.Answer) != 1 || m.Answer[0].(*dns.A).A.String() != answer {
			t.Fatalf("wrong DNS answer for %s", answer)
		}
		if err := <-done; err != nil {
			t.Fatal(err)
		}
		if b.directCalls != 0 {
			t.Fatal("DNS used direct")
		}
	}
}
func serveTestDNS(l net.Listener, answer string) error {
	c, e := l.Accept()
	if e != nil {
		return e
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(3 * time.Second))
	var greeting [2]byte
	if _, e = io.ReadFull(c, greeting[:]); e != nil {
		return e
	}
	if _, e = io.CopyN(io.Discard, c, int64(greeting[1])); e != nil {
		return e
	}
	if _, e = c.Write([]byte{5, 0}); e != nil {
		return e
	}
	var request [10]byte
	if _, e = io.ReadFull(c, request[:]); e != nil {
		return e
	}
	expected := [10]byte{5, 1, 0, 1, 1, 1, 1, 1, 0, 53}
	if request != expected {
		return errors.New("DNS did not use SOCKS CONNECT to the configured resolver")
	}
	if _, e = c.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 1, 1}); e != nil {
		return e
	}
	raw, e := readDNSFrame(c)
	if e != nil {
		return e
	}
	q := new(dns.Msg)
	if e = q.Unpack(raw); e != nil {
		return e
	}
	m := new(dns.Msg)
	m.SetReply(q)
	m.Answer = []dns.RR{&dns.A{Hdr: dns.RR_Header{Name: q.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET}, A: net.ParseIP(answer)}}
	reply, e := m.Pack()
	if e != nil {
		return e
	}
	return writeDNSFrame(c, reply)
}
