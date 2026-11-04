// Each test runs in its own short-lived process; it never changes a clone's running Xray instance.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
)

// Returns milliseconds through the configured outbound, -1 for timeout or -2 for an unusable route.
func measureLatency(outbound json.RawMessage, target string, timeout time.Duration) int64 {
	cfg, err := json.Marshal(object{"log": object{"loglevel": "none"}, "outbounds": []json.RawMessage{outbound}})
	if err != nil {
		return -2
	}
	instance, err := core.StartInstance("json", cfg)
	if err != nil {
		return -2
	}
	defer instance.Close()

	transport := &http.Transport{DisableKeepAlives: true, DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
		dest, err := xnet.ParseDestination("tcp:" + addr)
		if err != nil {
			return nil, err
		}
		return core.Dial(ctx, instance, dest)
	}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: timeout, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	start := time.Now()
	response, err := client.Get(target)
	if err != nil {
		var timed net.Error
		if errors.As(err, &timed) && timed.Timeout() {
			return -1
		}
		return -2
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusNoContent {
		return -2
	}
	return time.Since(start).Milliseconds()
}
