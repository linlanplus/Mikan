package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestLatencyWaitsForProbeThroughOutbound(t *testing.T) {
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/slow":
			<-r.Context().Done()
		case "/portal":
			w.WriteHeader(http.StatusOK)
		default:
			w.WriteHeader(http.StatusNoContent)
		}
	}))
	defer target.Close()
	direct := json.RawMessage(`{"protocol":"freedom","settings":{}}`)
	if ms := measureLatency(direct, target.URL, time.Second); ms < 0 {
		t.Fatalf("working route returned %d", ms)
	}
	if ms := measureLatency(direct, target.URL+"/slow", 150*time.Millisecond); ms != -1 {
		t.Fatalf("timeout returned %d", ms)
	}
	if ms := measureLatency(direct, target.URL+"/portal", time.Second); ms != -2 {
		t.Fatalf("non-204 response returned %d", ms)
	}
	broken := json.RawMessage(`{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":1}]}}`)
	if ms := measureLatency(broken, target.URL, 500*time.Millisecond); ms >= 0 {
		t.Fatalf("broken proxy reached destination in %d ms", ms)
	}
}
