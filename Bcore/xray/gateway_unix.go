//go:build linux

package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"os"
	"strings"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/transport/internet"
	"golang.org/x/sys/unix"
)

type androidBridge struct{ Socket, Token string }
type request struct {
	Op, Token, Host string
	Flow            *flow
}

// Each local socket carries one operation. SCM_RIGHTS is attached to the first byte.
func (b androidBridge) call(op, host string, f *flow, wantFD bool) (policy, int, error) {
	c, e := net.DialUnix("unix", nil, &net.UnixAddr{Name: "@" + b.Socket, Net: "unix"})
	if e != nil {
		return policy{}, -1, e
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(8 * time.Second))
	msg, e := json.Marshal(request{op, b.Token, host, f})
	if e != nil {
		return policy{}, -1, e
	}
	msg = append(msg, '\n')
	if n, e := c.Write(msg); e != nil || n != len(msg) {
		return policy{}, -1, io.ErrShortWrite
	}
	if wantFD {
		var tag [1]byte
		anc := make([]byte, unix.CmsgSpace(4))
		n, on, flags, _, e := c.ReadMsgUnix(tag[:], anc)
		if e != nil || n != 1 || tag[0] != 1 || flags&unix.MSG_CTRUNC != 0 {
			return policy{}, -1, denied
		}
		m, e := unix.ParseSocketControlMessage(anc[:on])
		if e != nil || len(m) != 1 {
			return policy{}, -1, denied
		}
		fds, e := unix.ParseUnixRights(&m[0])
		if e != nil || len(fds) != 1 {
			return policy{}, -1, denied
		}
		unix.CloseOnExec(fds[0])
		return policy{}, fds[0], nil
	}
	var p policy
	e = json.NewDecoder(io.LimitReader(c, 1<<20)).Decode(&p)
	if e != nil || p.Mode == "" {
		return policy{}, -1, denied
	}
	return p, -1, nil
}
func (b androidBridge) route(f flow, check bool) (policy, error) {
	op := "route"
	if check {
		op = "owner"
	}
	p, _, e := b.call(op, "", &f, false)
	return p, e
}
func (b androidBridge) protected(ctx context.Context, network, address string) (net.Conn, error) {
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	host, port, err := net.SplitHostPort(address)
	if err != nil || net.ParseIP(host) == nil || (network != "tcp" && network != "udp") {
		return nil, denied
	}
	_, fd, err := b.call(network, host+"/"+port, nil, true)
	if err != nil {
		return nil, err
	}
	f := os.NewFile(uintptr(fd), "guard-egress")
	defer f.Close()
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	return net.FileConn(f)
}

func (b androidBridge) directAnswers(host string) ([]string, error) {
	p, _, e := b.call("answers", host, nil, false)
	return p.Answers, e
}

// Only explicit direct owners may ask Android's physical network to resolve a guest name.
func (b androidBridge) direct(ctx context.Context, network, address string) (net.Conn, error) {
	host, port, e := net.SplitHostPort(address)
	if e != nil {
		return nil, e
	}
	if net.ParseIP(host) == nil {
		p, _, e := b.call("resolve", host, nil, false)
		if e != nil || net.ParseIP(p.Pin) == nil {
			return nil, denied
		}
		address = net.JoinHostPort(p.Pin, port)
	}
	return b.protected(ctx, network, address)
}

type nodeDialer struct{ androidBridge }

func (d nodeDialer) Dial(ctx context.Context, _ xnet.Address, dst xnet.Destination, _ *internet.SocketConfig) (net.Conn, error) {
	// Node hostnames have already been pinned, preserving SNI/HTTP authority.
	// In particular, never call the Android resolver for a guest destination.
	return d.protected(ctx, dst.Network.SystemString(), dst.NetAddr())
}
func (d nodeDialer) DestIpAddress() xnet.IP { return nil }

func init() {
	runGateway = func(input io.Reader, output io.Writer) error {
		reader := bufio.NewReader(input)
		line, e := reader.ReadString('\n')
		if e != nil || len(line) > 2048 {
			return denied
		}
		var b androidBridge
		if json.Unmarshal([]byte(line), &b) != nil || len(b.Token) < 32 || strings.ContainsRune(b.Socket, 0) {
			return denied
		}
		_, fd, e := b.call("tun", "", nil, true)
		if e != nil {
			return e
		}
		tun := os.NewFile(uintptr(fd), "clone-vpn")
		internet.UseAlternativeSystemDialer(nodeDialer{b})
		net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: func(context.Context, string, string) (net.Conn, error) {
			return nil, errors.New("external DNS disabled")
		}}
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		stop, done, e := startStack(ctx, tun, &gateway{bridge: b})
		if e != nil {
			return e
		}
		defer stop()
		if _, e = io.WriteString(output, "READY\n"); e != nil {
			return e
		}
		closed := make(chan struct{})
		go func() { io.Copy(io.Discard, reader); close(closed) }()
		select {
		case <-closed:
			return nil
		case e := <-done:
			return e
		}
	}
}
