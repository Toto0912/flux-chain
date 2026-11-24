package socket

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"net"
	"strings"
	"testing"

	"github.com/go-gost/core/logger"
	xlogger "github.com/go-gost/x/logger"
)

func TestHandleForwardUdpEchoSuccess(t *testing.T) {
	logger.SetDefault(xlogger.NewLogger())
	keyBytes := bytes.Repeat([]byte{0x5a}, sha256.Size)
	keyHex := hex.EncodeToString(keyBytes)

	server, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen udp: %v", err)
	}
	defer server.Close()

	go func() {
		buf := make([]byte, 128)
		for {
			n, addr, err := server.ReadFrom(buf)
			if err != nil {
				return
			}
			if n == 60 && string(buf[:4]) == "FCQ2" {
				mac := hmac.New(sha256.New, keyBytes)
				mac.Write(buf[:28])
				if !hmac.Equal(buf[28:60], mac.Sum(nil)) {
					continue
				}
				reply := make([]byte, 60)
				copy(reply[:4], "FCR2")
				copy(reply[4:28], buf[4:28])
				replyMac := hmac.New(sha256.New, keyBytes)
				replyMac.Write(reply[:28])
				copy(reply[28:60], replyMac.Sum(nil))
				_, _ = server.WriteTo(reply, addr)
			}
		}
	}()

	reporter := &WebSocketReporter{ctx: context.Background()}
	result, err := reporter.handleForwardUdpStun(map[string]interface{}{
		"mode":       "udp_echo",
		"target":     server.LocalAddr().String(),
		"timeout":    1500,
		"udpEchoKey": keyHex,
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
	validKey := hex.EncodeToString(bytes.Repeat([]byte{0x5a}, sha256.Size))

	if _, err := reporter.handleForwardUdpStun(map[string]interface{}{
		"mode":       "udp_echo",
		"target":     "invalid-target",
		"timeout":    1500,
		"udpEchoKey": validKey,
	}); err == nil || !strings.Contains(err.Error(), "目标地址无效") {
		t.Fatalf("expected invalid target error, got: %v", err)
	}

	if _, err := reporter.handleForwardUdpStun(map[string]interface{}{
		"mode":    "udp_echo",
		"target":  "127.0.0.1:19302",
		"timeout": 1500,
	}); err == nil || !strings.Contains(err.Error(), "密钥无效") {
		t.Fatalf("expected missing key error, got: %v", err)
	}

	silent, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen silent udp: %v", err)
	}
	defer silent.Close()

	_, err = reporter.handleForwardUdpStun(map[string]interface{}{
		"mode":       "udp_echo",
		"target":     silent.LocalAddr().String(),
		"timeout":    1000,
		"udpEchoKey": validKey,
	})
	if err == nil || !strings.Contains(err.Error(), "UDP 回显") {
		t.Fatalf("expected udp echo timeout error, got: %v", err)
	}
}
