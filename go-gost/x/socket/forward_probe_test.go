package socket

import (
	"bytes"
	"context"
	"net"
	"strings"
	"testing"

	"github.com/go-gost/core/logger"
	xlogger "github.com/go-gost/x/logger"
)

func TestHandleForwardUdpEchoSuccess(t *testing.T) {
	logger.SetDefault(xlogger.NewLogger())
	server, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen udp: %v", err)
	}
	defer server.Close()

	go func() {
		buf := make([]byte, 64)
		for {
			n, addr, err := server.ReadFrom(buf)
			if err != nil {
				return
			}
			if n == 20 && bytes.HasPrefix(buf[:n], []byte("FCE1")) {
				_, _ = server.WriteTo(buf[:n], addr)
			}
		}
	}()

	reporter := &WebSocketReporter{ctx: context.Background()}
	result, err := reporter.handleForwardUdpStun(map[string]interface{}{
		"mode":    "udp_echo",
		"target":  server.LocalAddr().String(),
		"timeout": 1500,
	})
	if err != nil {
		t.Fatalf("expected udp_echo success, got err: %v", err)
	}
	values, ok := result.(map[string]float64)
	if !ok {
		t.Fatalf("unexpected result type: %T", result)
	}
	if latency, exists := values["latencyMs"]; !exists || latency < 0 {
		t.Fatalf("unexpected latency result: %+v", values)
	}
}

func TestHandleForwardUdpEchoValidationAndTimeout(t *testing.T) {
	logger.SetDefault(xlogger.NewLogger())
	reporter := &WebSocketReporter{ctx: context.Background()}

	if _, err := reporter.handleForwardUdpStun(map[string]interface{}{
		"mode":    "udp_echo",
		"target":  "invalid-target",
		"timeout": 1500,
	}); err == nil || !strings.Contains(err.Error(), "目标地址无效") {
		t.Fatalf("expected invalid target error, got: %v", err)
	}

	silent, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen silent udp: %v", err)
	}
	defer silent.Close()

	_, err = reporter.handleForwardUdpStun(map[string]interface{}{
		"mode":    "udp_echo",
		"target":  silent.LocalAddr().String(),
		"timeout": 1000,
	})
	if err == nil || !strings.Contains(err.Error(), "UDP 回显") {
		t.Fatalf("expected udp echo timeout error, got: %v", err)
	}
}
