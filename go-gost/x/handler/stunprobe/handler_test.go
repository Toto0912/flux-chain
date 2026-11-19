package stunprobe

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"net"
	"testing"
	"time"

	"github.com/go-gost/x/internal/net/udp"
	"github.com/go-gost/x/internal/util/stunprobe"
	"github.com/go-gost/x/logger"
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
