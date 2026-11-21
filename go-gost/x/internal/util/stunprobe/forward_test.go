package stunprobe

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

func TestTunnelProbeTicketIsServiceBoundAndSingleUse(t *testing.T) {
	token, err := PrepareTunnelProbe("test-exit", true)
	if err != nil {
		t.Fatal(err)
	}
	if _, accepted := AcceptTunnelProbe("other-exit", token); accepted {
		t.Fatal("wrong exit accepted the ticket")
	}
	if linkOnly, accepted := AcceptTunnelProbe("test-exit", token); !accepted || !linkOnly {
		t.Fatal("exit did not recover the link-only mode from its own ticket")
	}
	if _, accepted := AcceptTunnelProbe("test-exit", token); accepted {
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
		serve <- RespondTunnel(ctx, exit, false, func(_ context.Context, addr string) (net.Conn, error) {
			if addr != GoogleAddress {
				return nil, errors.New("unexpected STUN destination")
			}
			return nil, unreachable
		})
	}()
	timings, err := MeasureTunnel(ctx, entry, false)
	if timings.LinkRTT == nil || timings.STUNRTT != nil || err == nil {
		t.Fatalf("expected only a tunnel RTT, got %+v, %v", timings, err)
	}
	if err := <-serve; !errors.Is(err, unreachable) {
		t.Fatalf("unexpected exit result: %v", err)
	}
}

func TestLinkOnlyTunnelProbeStopsAfterUdpEcho(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	entry, exit := net.Pipe()
	defer entry.Close()
	serve := make(chan error, 1)
	go func() {
		defer exit.Close()
		serve <- RespondTunnel(ctx, exit, true, func(context.Context, string) (net.Conn, error) {
			return nil, errors.New("link-only probe unexpectedly dialed a STUN server")
		})
	}()
	timings, err := MeasureTunnel(ctx, entry, true)
	if err != nil || timings.LinkRTT == nil || timings.STUNRTT != nil {
		t.Fatalf("expected a successful UDP echo, got %+v, %v", timings, err)
	}
	if err := <-serve; err != nil {
		t.Fatal(err)
	}
}
