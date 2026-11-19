package stunprobe

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

func TestTunnelProbeTicketIsServiceBoundAndSingleUse(t *testing.T) {
	token, err := PrepareTunnelProbe("test-exit")
	if err != nil {
		t.Fatal(err)
	}
	if AcceptTunnelProbe("other-exit", token) {
		t.Fatal("wrong exit accepted the ticket")
	}
	if !AcceptTunnelProbe("test-exit", token) {
		t.Fatal("exit rejected its own ticket")
	}
	if AcceptTunnelProbe("test-exit", token) {
		t.Fatal("exit accepted a second use of the ticket")
	}
}

func TestMeasureTunnelKeepsLinkRTTWhenGoogleIsUnavailable(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	entry, exit := net.Pipe()
	defer entry.Close()
	serve := make(chan error, 1)
	unreachable := errors.New("test STUN destination unavailable")
	go func() {
		defer exit.Close()
		serve <- RespondTunnel(ctx, exit, func(_ context.Context, addr string) (net.Conn, error) {
			if addr != GoogleAddress {
				return nil, errors.New("unexpected STUN destination")
			}
			return nil, unreachable
		})
	}()
	timings, err := MeasureTunnel(ctx, entry)
	if timings.LinkRTT == nil || timings.STUNRTT != nil || err == nil {
		t.Fatalf("expected only a tunnel RTT, got %+v, %v", timings, err)
	}
	if err := <-serve; !errors.Is(err, unreachable) {
		t.Fatalf("unexpected exit result: %v", err)
	}
}
