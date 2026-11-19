package main

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/xtls/xray-core/common/buf"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/routing"
	xtransport "github.com/xtls/xray-core/transport"
)

// Only the Android host can grant a route. An empty policy, a stale owner,
// an unsupported network or an upstream error never selects direct egress.
type policy struct {
	Owner, Mode, Pin string
	UDP              bool
	Answers          []string
	Outbound         json.RawMessage
}
type bridge interface {
	route(flow, bool) (policy, error)
	directAnswers(string) ([]string, error)
	direct(context.Context, string, string) (net.Conn, error)
}

var denied = errors.New("no authorized route")

type liveCore struct {
	instance *core.Instance
	refs     int
}
type gateway struct {
	bridge bridge
	names  fakeNames
	mu     sync.Mutex
	cores  map[[32]byte]*liveCore
}

func (g *gateway) node(p policy) (*core.Instance, func(), error) {
	var ob object
	if json.Unmarshal(p.Outbound, &ob) != nil {
		return nil, nil, denied
	}
	switch field(ob, "protocol") {
	case "socks", "shadowsocks", "vless", "vmess", "trojan":
	default:
		return nil, nil, denied
	}
	server := child(ob, "settings")
	if server == nil {
		return nil, nil, denied
	}
	host := field(server, "address")
	if net.ParseIP(host) == nil {
		if net.ParseIP(p.Pin) == nil {
			return nil, nil, denied
		}
		// Preserve transport identity when substituting the explicitly pinned IP.
		stream := child(ob, "streamSettings")
		for _, v := range []struct{ key, name string }{{"tlsSettings", "serverName"}, {"realitySettings", "serverName"}, {"wsSettings", "host"}, {"httpupgradeSettings", "host"}, {"xhttpSettings", "host"}, {"grpcSettings", "authority"}} {
			if settings := child(stream, v.key); settings != nil && field(settings, v.name) == "" {
				settings[v.name] = host
			}
		}
		server["address"] = p.Pin
	}
	cfg, err := json.Marshal(object{"log": object{"loglevel": "none"}, "outbounds": []object{ob}})
	if err != nil {
		return nil, nil, err
	}
	key := sha256.Sum256(cfg)
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.cores == nil {
		g.cores = make(map[[32]byte]*liveCore)
	}
	n := g.cores[key]
	if n == nil {
		instance, err := core.StartInstance("json", cfg)
		if err != nil {
			return nil, nil, err
		}
		n = &liveCore{instance: instance}
		g.cores[key] = n
	}
	n.refs++
	return n.instance, func() {
		g.mu.Lock()
		n.refs--
		last := n.refs == 0
		if last {
			delete(g.cores, key)
		}
		g.mu.Unlock()
		if last {
			n.instance.Close()
		}
	}, nil
}
func (g *gateway) dial(ctx context.Context, p policy, network, host string, port uint16) (net.Conn, func(), error) {
	if p.Owner == "" || (network == "udp" && !p.UDP) {
		return nil, nil, denied
	}
	if p.Mode == "direct" {
		conn, err := g.bridge.direct(ctx, network, net.JoinHostPort(host, strconv.Itoa(int(port))))
		return conn, func() {}, err
	}
	if p.Mode != "node" {
		return nil, nil, denied
	}
	inst, release, err := g.node(p)
	if err != nil {
		return nil, nil, err
	}
	dst := xnet.TCPDestination(xnet.ParseAddress(host), xnet.Port(port))
	if network == "udp" {
		dst.Network = xnet.Network_UDP
	}
	conn, err := core.Dial(ctx, inst, dst)
	if err != nil {
		release()
		return nil, nil, err
	}
	return conn, release, nil
}
func (g *gateway) handle(f flow, guest net.Conn) {
	defer guest.Close()
	virtualDNS := f.DP == 53 && (f.Dst == dns4 || f.Dst == dns6)
	p, err := g.bridge.route(f, false)
	if virtualDNS {
		g.dns(f, guest, p, err)
		return
	}
	if f.Proto == "tcp" && f.DP == 5354 && f.Dst == dns4 {
		if err == nil && (p.Mode == "node" || p.Mode == "direct") && p.Owner != "" {
			g.lookup(guest, p)
		}
		return
	}
	if err != nil || p.Owner == "" {
		return
	}
	if f.Proto == "tcp" && f.DP == 5355 && f.Dst == dns4 {
		if p.Mode == "direct" && strings.HasPrefix(p.Owner, "host:") {
			guest.SetDeadline(time.Now().Add(9 * time.Second))
			var test policy
			if json.NewDecoder(io.LimitReader(guest, 1<<20)).Decode(&test) == nil {
				if inst, release, e := g.node(test); e == nil {
					defer release()
					fmt.Fprintln(guest, latencyThrough(inst, "https://www.gstatic.com/generate_204", 6*time.Second))
				}
			}
		}
		return
	}
	host, ok := g.names.destination(f.Dst)
	if !ok {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if f.Proto == "tcp" && p.Mode == "node" {
		inst, release, e := g.node(p)
		if e != nil {
			return
		}
		defer release()
		dispatcher, ok := inst.GetFeature(routing.DispatcherType()).(routing.Dispatcher)
		if !ok {
			return
		}
		// Let Xray receive TCP EOF from the socket reader. Protocols such as
		// Shadowsocks can then finish their uplink without dropping the response.
		dispatcher.DispatchLink(ctx, xnet.TCPDestination(xnet.ParseAddress(host), xnet.Port(f.DP)),
			&xtransport.Link{Reader: buf.NewReader(guest), Writer: buf.NewWriter(guest)})
		return
	}
	up, release, err := g.dial(ctx, p, f.Proto, host, f.DP)
	if err != nil {
		return
	}
	defer release()
	defer up.Close()
	if f.Proto == "tcp" {
		go func() {
			io.Copy(up, guest)
			if c, ok := up.(interface{ CloseWrite() error }); ok {
				c.CloseWrite()
			}
		}()
		io.Copy(guest, up)
		return
	}
	go func() {
		packet := make([]byte, 65535)
		for {
			n, e := up.Read(packet)
			if e != nil {
				guest.Close()
				return
			}
			owner, e := g.bridge.route(f, true)
			if e != nil || owner.Owner != p.Owner {
				guest.Close()
				return
			}
			if _, e = guest.Write(packet[:n]); e != nil {
				return
			}
		}
	}()
	packet := make([]byte, 65535)
	for {
		guest.SetReadDeadline(time.Now().Add(udpIdle))
		n, e := guest.Read(packet)
		if e != nil {
			return
		}
		owner, e := g.bridge.route(f, true)
		if e != nil || owner.Owner != p.Owner {
			return
		}
		// Xray's stream Write splits large buffers. Keep one buffer per datagram.
		if writer, ok := up.(buf.Writer); ok {
			e = writer.WriteMultiBuffer(buf.MultiBuffer{buf.FromBytes(append([]byte(nil), packet[:n]...))})
		} else {
			_, e = up.Write(packet[:n])
		}
		if e != nil {
			return
		}
	}
}
func writeDNSFrame(w io.Writer, msg []byte) error {
	if len(msg) > 65535 {
		return denied
	}
	b := make([]byte, 2+len(msg))
	binary.BigEndian.PutUint16(b, uint16(len(msg)))
	copy(b[2:], msg)
	_, e := w.Write(b)
	return e
}
func readDNSFrame(r io.Reader) ([]byte, error) {
	var b [2]byte
	if _, e := io.ReadFull(r, b[:]); e != nil {
		return nil, e
	}
	out := make([]byte, binary.BigEndian.Uint16(b[:]))
	_, e := io.ReadFull(r, out)
	return out, e
}
