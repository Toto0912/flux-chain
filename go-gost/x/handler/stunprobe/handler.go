package stunprobe

import (
	"context"
	"net"

	"github.com/go-gost/core/handler"
	md "github.com/go-gost/core/metadata"
	"github.com/go-gost/x/internal/util/stunprobe"
	mdutil "github.com/go-gost/x/metadata/util"
	"github.com/go-gost/x/registry"
)

func init() {
	registry.HandlerRegistry().Register("fluxchain-stun", newHandler)
}

type probeHandler struct {
	nextHop    string
	echoTarget string
	key        []byte
	echoKey    []byte
}

func newHandler(_ ...handler.Option) handler.Handler {
	return &probeHandler{}
}

func (h *probeHandler) Init(metadata md.Metadata) error {
	h.nextHop = mdutil.GetString(metadata, "nextHop")
	h.echoTarget = mdutil.GetString(metadata, "echoTarget")
	key, err := stunprobe.ParseKey(mdutil.GetString(metadata, "fluxchainStunKey"))
	if err != nil {
		return err
	}
	h.key = key
	if h.echoTarget != "" {
		echoKey, err := stunprobe.ParseEchoKey(mdutil.GetString(metadata, "udpEchoKey"))
		if err != nil {
			return err
		}
		h.echoKey = echoKey
	}
	return nil
}

func (h *probeHandler) Handle(ctx context.Context, conn net.Conn, _ ...handler.HandleOption) error {
	defer conn.Close()
	ctx, cancel := context.WithTimeout(ctx, stunprobe.MaxTimeout)
	defer cancel()
	// Accepted UDP peers share a socket. Closing this peer unblocks its read
	// without putting a deadline on the listener that serves later probes.
	stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
	defer stop()

	var packet [128]byte
	n, err := conn.Read(packet[:])
	if err != nil {
		return err
	}
	reply, err := stunprobe.Relay(ctx, h.nextHop, h.echoTarget, packet[:n], h.key, h.echoKey)
	if err != nil || reply == nil {
		return err
	}
	_, err = conn.Write(reply)
	return err
}
