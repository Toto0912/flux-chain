package stunprobe

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"net"
	"testing"
	"time"

	"github.com/go-gost/x/internal/net/udp"
	"github.com/go-gost/x/internal/util/stunprobe"
	"github.com/go-gost/x/logger"
	xmetadata "github.com/go-gost/x/metadata"
)

func TestListenerSurvivesProbeDeadline(t *testing.T) {
	socket, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	listener := udp.NewListener(socket, &udp.ListenConfig{
		Backlog: 8, ReadQueueSize: 8, ReadBufferSize: 128,
		TTL: time.Second, Logger: logger.Nop(),
	})
	t.Cleanup(func() { _ = listener.Close() })

	next, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = next.Close() })
	key := bytes.Repeat([]byte{1}, sha256.Size)
	go func() {
		var buf [128]byte
		for {
			n, addr, err := next.ReadFrom(buf[:])
			if err != nil || n < sha256.Size+2 {
				return
			}
			packet := buf[:n]
			binary.BigEndian.PutUint16(packet, 0x0101)
			mac := hmac.New(sha256.New, key)
			mac.Write(packet[:n-sha256.Size])
			copy(packet[n-sha256.Size:], mac.Sum(nil))
			if _, err := next.WriteTo(packet, addr); err != nil {
				return
			}
		}
	}()

	h := &probeHandler{nextHop: next.LocalAddr().String(), key: key}
	const requestTimeout = 300 * time.Millisecond
	go func() {
		for {
			peer, err := listener.Accept()
			if err != nil {
				return
			}
			ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
			_ = h.Handle(ctx, peer)
			cancel()
		}
	}()

	for round := 1; round <= 2; round++ {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		_, err := stunprobe.Probe(ctx, listener.Addr().String(), key)
		cancel()
		if err != nil {
			t.Fatalf("probe round %d: %v", round, err)
		}
		if round == 1 {
			// The next request arrives after the first handler's deadline.
			time.Sleep(requestTimeout + 50*time.Millisecond)
		}
	}
}

func TestInitAllowsLegacyEchoTargetWithoutKey(t *testing.T) {
	stunKey := hex.EncodeToString(bytes.Repeat([]byte{1}, sha256.Size))
	echoKey := hex.EncodeToString(bytes.Repeat([]byte{2}, sha256.Size))

	h := &probeHandler{}
	if err := h.Init(xmetadata.NewMetadata(map[string]any{
		"fluxchainStunKey": stunKey,
		"echoTarget":       "198.51.100.1:19302",
	})); err != nil {
		t.Fatalf("expected legacy echoTarget without udpEchoKey to initialize, got err: %v", err)
	}
	if len(h.echoKey) != 0 {
		t.Fatalf("expected empty echoKey for legacy metadata, got %d bytes", len(h.echoKey))
	}

	hInvalid := &probeHandler{}
	if err := hInvalid.Init(xmetadata.NewMetadata(map[string]any{
		"fluxchainStunKey": stunKey,
		"echoTarget":       "198.51.100.1:19302",
		"udpEchoKey":       "invalid",
	})); err == nil {
		t.Fatal("expected invalid non-empty udpEchoKey to fail")
	}

	hValid := &probeHandler{}
	if err := hValid.Init(xmetadata.NewMetadata(map[string]any{
		"fluxchainStunKey": stunKey,
		"echoTarget":       "198.51.100.1:19302",
		"udpEchoKey":       echoKey,
	})); err != nil {
		t.Fatalf("expected valid udpEchoKey to initialize, got err: %v", err)
	}
	if len(hValid.echoKey) != sha256.Size {
		t.Fatalf("expected %d-byte echoKey, got %d", sha256.Size, len(hValid.echoKey))
	}
}
