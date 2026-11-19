package main

import (
	"context"
	"errors"
	"io"
	"net"
	"os"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

type flow struct {
	Proto    string
	Src, Dst string
	SP, DP   uint16
}

func tuple(proto string, id stack.TransportEndpointID) flow {
	return flow{proto, net.IP(id.RemoteAddress.AsSlice()).String(), net.IP(id.LocalAddress.AsSlice()).String(), id.RemotePort, id.LocalPort}
}

// Android supplies raw IPv4/IPv6 packets without a Linux packet-info header.
func startStack(ctx context.Context, tun *os.File, gateway *gateway) (func(), <-chan error, error) {
	link := channel.New(512, 1500, "")
	s := stack.New(stack.Options{NetworkProtocols: []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol}})
	closeStack := func() { tun.Close(); s.Close(); link.Close() }
	if err := s.CreateNIC(1, link); err != nil {
		closeStack()
		return nil, nil, errors.New(err.String())
	}
	if err := s.SetSpoofing(1, true); err != nil {
		closeStack()
		return nil, nil, errors.New(err.String())
	}
	if err := s.SetPromiscuousMode(1, true); err != nil {
		closeStack()
		return nil, nil, errors.New(err.String())
	}
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}, {Destination: header.IPv6EmptySubnet, NIC: 1}})
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcp.NewForwarder(s, 0, 512, func(r *tcp.ForwarderRequest) {
		var q waiter.Queue
		id := r.ID()
		ep, err := r.CreateEndpoint(&q)
		r.Complete(err != nil)
		if err == nil {
			go gateway.handle(tuple("tcp", id), gonet.NewTCPConn(&q, ep))
		}
	}).HandlePacket)
	s.SetTransportProtocolHandler(udp.ProtocolNumber, udp.NewForwarder(s, func(r *udp.ForwarderRequest) bool {
		var q waiter.Queue
		ep, err := r.CreateEndpoint(&q)
		if err != nil {
			return false
		}
		go gateway.handle(tuple("udp", r.ID()), gonet.NewUDPConn(&q, ep))
		return true
	}).HandlePacket)
	done := make(chan error, 2)
	go func() {
		raw := make([]byte, 65535)
		for {
			n, err := tun.Read(raw)
			if err != nil {
				done <- err
				return
			}
			if n == 0 {
				continue
			}
			var proto tcpip.NetworkProtocolNumber
			switch raw[0] >> 4 {
			case 4:
				proto = ipv4.ProtocolNumber
			case 6:
				proto = ipv6.ProtocolNumber
			default:
				continue
			}
			p := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(append([]byte(nil), raw[:n]...))})
			link.InjectInbound(proto, p)
			p.DecRef()
		}
	}()
	go func() {
		for {
			p := link.ReadContext(ctx)
			if p == nil {
				done <- io.EOF
				return
			}
			v := p.ToView()
			_, err := tun.Write(v.AsSlice())
			v.Release()
			p.DecRef()
			if err != nil {
				done <- err
				return
			}
		}
	}()
	return closeStack, done, nil
}

const udpIdle = 90 * time.Second
