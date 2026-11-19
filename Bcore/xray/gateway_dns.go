package main

import (
	"context"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"time"

	"github.com/miekg/dns"
)

const dns4 = "10.112.0.2"
const dns6 = "fd75:6d69:6b61::2"

var fake4 = netip.MustParsePrefix("198.18.0.0/15")
var fake6 = netip.MustParsePrefix("fd75:6d69:6baf::/96")

// Android's system resolver shares sockets across processes. Never send an
// unattributed question upstream. Its synthetic answers carry only a name;
// the subsequent application socket supplies the node that resolves the name.
type fakeNames struct {
	mu    sync.Mutex
	index map[string]uint32
	names []string
}

func (f *fakeNames) address(name string, v6 bool) (net.IP, bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	name = strings.ToLower(strings.TrimSuffix(name, "."))
	if f.index == nil {
		f.index = make(map[string]uint32)
	}
	n := f.index[name]
	if n == 0 {
		if len(f.names) >= 131070 {
			return nil, false
		}
		n = uint32(len(f.names) + 1)
		f.index[name] = n
		f.names = append(f.names, name)
	}
	if !v6 {
		return net.IPv4(198, byte(18+(n>>16)), byte(n>>8), byte(n)), true
	}
	b := fake6.Addr().As16()
	b[13] = byte(n >> 16)
	b[14] = byte(n >> 8)
	b[15] = byte(n)
	return net.IP(b[:]), true
}
func (f *fakeNames) destination(addr string) (string, bool) {
	ip, err := netip.ParseAddr(addr)
	if err != nil {
		return "", false
	}
	var n uint32
	if fake4.Contains(ip) {
		v := ip.As4()
		n = uint32(v[1]-18)<<16 | uint32(v[2])<<8 | uint32(v[3])
	} else if fake6.Contains(ip) {
		v := ip.As16()
		n = uint32(v[12])<<24 | uint32(v[13])<<16 | uint32(v[14])<<8 | uint32(v[15])
	} else {
		return addr, true
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if n == 0 || uint64(n) > uint64(len(f.names)) {
		return "", false
	}
	return f.names[n-1], true
}
func (f *fakeNames) answer(q *dns.Msg) *dns.Msg {
	r := new(dns.Msg)
	r.SetReply(q)
	r.RecursionAvailable = true
	if q.Opcode != dns.OpcodeQuery || len(q.Question) != 1 || q.Question[0].Qclass != dns.ClassINET {
		r.Rcode = dns.RcodeRefused
		return r
	}
	v := q.Question[0]
	if v.Qtype != dns.TypeA && v.Qtype != dns.TypeAAAA {
		r.Rcode = dns.RcodeRefused
		return r
	}
	ip, ok := f.address(v.Name, v.Qtype == dns.TypeAAAA)
	if !ok {
		r.Rcode = dns.RcodeServerFailure
		return r
	}
	h := dns.RR_Header{Name: v.Name, Rrtype: v.Qtype, Class: dns.ClassINET, Ttl: 0}
	if v.Qtype == dns.TypeA {
		r.Answer = []dns.RR{&dns.A{Hdr: h, A: ip}}
	} else {
		r.Answer = []dns.RR{&dns.AAAA{Hdr: h, AAAA: ip}}
	}
	return r
}
func (g *gateway) dns(f flow, guest net.Conn, p policy, ownerErr error) {
	for {
		var raw []byte
		var err error
		guest.SetReadDeadline(time.Now().Add(15 * time.Second))
		if f.Proto == "tcp" {
			raw, err = readDNSFrame(guest)
		} else {
			b := make([]byte, 65535)
			var n int
			n, err = guest.Read(b)
			raw = b[:n]
		}
		if err != nil {
			return
		}
		q := new(dns.Msg)
		if q.Unpack(raw) != nil {
			return
		}
		var answer []byte
		if ownerErr == nil && p.Mode == "system-dns" {
			answer, _ = g.names.answer(q).Pack()
		} else if ownerErr == nil && p.Owner != "" {
			current, e := g.bridge.route(f, true)
			if e == nil && current.Owner == p.Owner {
				answer = g.exchangeDNS(p, q, raw)
			}
		}
		if answer == nil {
			r := new(dns.Msg)
			r.SetRcode(q, dns.RcodeServerFailure)
			answer, _ = r.Pack()
		}
		if f.Proto == "tcp" {
			if writeDNSFrame(guest, answer) != nil {
				return
			}
		} else {
			if _, err = guest.Write(answer); err != nil {
				return
			}
		}
	}
}
func (g *gateway) exchangeDNS(p policy, q *dns.Msg, raw []byte) []byte {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	conn, release, err := g.dial(ctx, p, "tcp", "1.1.1.1", 53)
	if err != nil {
		return nil
	}
	defer release()
	defer conn.Close()
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()
	conn.SetDeadline(time.Now().Add(8 * time.Second))
	if writeDNSFrame(conn, raw) != nil {
		return nil
	}
	result, err := readDNSFrame(conn)
	var reply dns.Msg
	if err != nil || reply.Unpack(result) != nil || !reply.Response || reply.Id != q.Id || len(q.Question) != 1 || len(reply.Question) != 1 || !strings.EqualFold(reply.Question[0].Name, q.Question[0].Name) || reply.Question[0].Qtype != q.Question[0].Qtype {
		return nil
	}
	return result
}

// libc and libcore use a guest-owned socket for lookups. The Android system
// resolver otherwise loses the virtual user's identity before reaching the TUN.
func (g *gateway) lookup(c net.Conn, p policy) {
	c.SetDeadline(time.Now().Add(10 * time.Second))
	var head [2]byte
	if _, e := io.ReadFull(c, head[:]); e != nil || head[1] == 0 {
		return
	}
	name := make([]byte, int(head[1]))
	if _, e := io.ReadFull(c, name); e != nil {
		return
	}
	fqdn := dns.Fqdn(string(name))
	if _, ok := dns.IsDomainName(fqdn); !ok {
		return
	}
	var typ uint16
	switch head[0] {
	case 4:
		typ = dns.TypeA
	case 6:
		typ = dns.TypeAAAA
	default:
		return
	}
	if p.Mode == "direct" {
		answers, err := g.bridge.directAnswers(strings.TrimSuffix(fqdn, "."))
		if err != nil {
			return
		}
		out := []byte{0}
		for _, s := range answers {
			ip := net.ParseIP(s)
			if ip == nil || out[0] >= 64 {
				continue
			}
			if typ == dns.TypeA {
				ip = ip.To4()
			} else if ip.To4() != nil {
				continue
			} else {
				ip = ip.To16()
			}
			if ip != nil {
				out = append(out, ip...)
				out[0]++
			}
		}
		c.Write(out)
		return
	}
	q := new(dns.Msg)
	q.SetQuestion(fqdn, typ)
	raw, e := q.Pack()
	if e != nil {
		return
	}
	answer := g.exchangeDNS(p, q, raw)
	reply := new(dns.Msg)
	if answer == nil || reply.Unpack(answer) != nil || (reply.Rcode != dns.RcodeSuccess && reply.Rcode != dns.RcodeNameError) {
		return
	}
	canonical := fqdn
	for i := 0; i < len(reply.Answer); i++ {
		next := ""
		for _, rr := range reply.Answer {
			if alias, ok := rr.(*dns.CNAME); ok && strings.EqualFold(alias.Hdr.Name, canonical) {
				next = alias.Target
				break
			}
		}
		if next == "" {
			break
		}
		canonical = next
	}
	out := []byte{0}
	for _, rr := range reply.Answer {
		if !strings.EqualFold(rr.Header().Name, canonical) || rr.Header().Rrtype != typ || out[0] >= 64 {
			continue
		}
		switch a := rr.(type) {
		case *dns.A:
			out = append(out, a.A.To4()...)
		case *dns.AAAA:
			out = append(out, a.AAAA.To16()...)
		default:
			continue
		}
		out[0]++
	}
	c.Write(out)
}
