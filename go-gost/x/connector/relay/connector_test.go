package relay

import (
	"context"
	"net"
	"testing"
	"time"

	"github.com/go-gost/relay"
	"github.com/go-gost/x/internal/util/stunprobe"
)

func TestTunnelProbeRequiresExplicitExitAcknowledgement(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	entry, exit := net.Pipe()
	defer entry.Close()
	defer exit.Close()
	served := make(chan error, 1)
	go func() {
		var req relay.Request
		_, err := req.ReadFrom(exit)
		if err == nil {
			// An older forwarder returns OK without the probe feature.
			_, err = (&relay.Response{Version: relay.Version1, Status: relay.StatusOK}).WriteTo(exit)
		}
		served <- err
	}()
	req := &relay.Request{Version: relay.Version1, Cmd: stunprobe.TunnelProbeCommand | relay.FUDP}
	if err := openTunnelProbe(ctx, entry, req); err == nil {
		t.Fatal("plain Relay OK was accepted as a tunnel probe acknowledgement")
	}
	if err := <-served; err != nil {
		t.Fatal(err)
	}
}
