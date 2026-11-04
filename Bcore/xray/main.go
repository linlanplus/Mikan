// The executable is installed in Android's native library directory for each supported ABI.
// A guest owns its child through stdin. Android closing that pipe also stops its Xray instance.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"time"

	_ "github.com/xtls/xray-core/app/dispatcher"
	_ "github.com/xtls/xray-core/app/log"
	_ "github.com/xtls/xray-core/app/proxyman/inbound"
	_ "github.com/xtls/xray-core/app/proxyman/outbound"
	xlog "github.com/xtls/xray-core/common/log"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/json"
	_ "github.com/xtls/xray-core/proxy/shadowsocks"
	_ "github.com/xtls/xray-core/proxy/socks"
	_ "github.com/xtls/xray-core/proxy/trojan"
	_ "github.com/xtls/xray-core/proxy/vless/outbound"
	_ "github.com/xtls/xray-core/proxy/vmess/outbound"
	_ "github.com/xtls/xray-core/transport/internet/grpc"
	_ "github.com/xtls/xray-core/transport/internet/headers/http"
	_ "github.com/xtls/xray-core/transport/internet/headers/noop"
	_ "github.com/xtls/xray-core/transport/internet/httpupgrade"
	_ "github.com/xtls/xray-core/transport/internet/reality"
	_ "github.com/xtls/xray-core/transport/internet/splithttp"
	_ "github.com/xtls/xray-core/transport/internet/tcp"
	_ "github.com/xtls/xray-core/transport/internet/tls"
	_ "github.com/xtls/xray-core/transport/internet/udp"
	_ "github.com/xtls/xray-core/transport/internet/websocket"
)

const maxSubscription = 4 << 20

type object = map[string]any

type quietLog struct{}

func (quietLog) Handle(xlog.Message) {}

func main() {
	// stdout is the Android control pipe, including during validation before Xray starts.
	xlog.RegisterHandler(quietLog{})
	if len(os.Args) == 2 {
		switch os.Args[1] {
		case "import":
			data, err := io.ReadAll(io.LimitReader(os.Stdin, maxSubscription+1))
			if err == nil && len(data) <= maxSubscription {
				result, err := parseSubscription(data)
				if err == nil && json.NewEncoder(os.Stdout).Encode(result) == nil {
					return
				}
			}
		case "latency":
			var outbound json.RawMessage
			if json.NewDecoder(io.LimitReader(os.Stdin, maxSubscription)).Decode(&outbound) == nil {
				fmt.Fprintln(os.Stdout, measureLatency(outbound, "https://www.gstatic.com/generate_204", 6*time.Second))
				return
			}
		case "run":
			if serve(os.Stdin, os.Stdout) == nil {
				return
			}
		}
	}
	// Core errors may include a remote URL, password, or user ID.
	fmt.Fprintln(os.Stderr, "Proxy configuration could not be used.")
	os.Exit(1)
}

func serve(input io.Reader, output io.Writer) error {
	reader := bufio.NewReader(input)
	line, err := reader.ReadBytes('\n')
	if err != nil {
		return err
	}
	var request struct {
		Outbound json.RawMessage
		Password string
	}
	if err := json.Unmarshal(line, &request); err != nil {
		return err
	}
	if len(request.Password) < 16 {
		return fmt.Errorf("missing local credentials")
	}
	for attempt := 0; attempt < 4; attempt++ {
		reservation, err := net.Listen("tcp4", "127.0.0.1:0")
		if err != nil {
			return err
		}
		port := reservation.Addr().(*net.TCPAddr).Port
		cfg, err := json.Marshal(object{
			"log": object{"loglevel": "none"},
			"inbounds": []object{{"listen": "127.0.0.1", "port": port, "protocol": "socks",
				"settings": object{"auth": "password", "accounts": []object{{"user": "mikan", "pass": request.Password}}}}},
			"outbounds": []json.RawMessage{request.Outbound},
		})
		reservation.Close()
		if err != nil {
			return err
		}
		instance, err := core.StartInstance("json", cfg)
		if err != nil {
			if attempt == 3 {
				return err
			}
			continue
		}
		defer instance.Close()
		if _, err := fmt.Fprintf(output, "READY %d\n", port); err != nil {
			return err
		}
		_, err = io.Copy(io.Discard, reader)
		return err
	}
	return fmt.Errorf("no local listener")
}
