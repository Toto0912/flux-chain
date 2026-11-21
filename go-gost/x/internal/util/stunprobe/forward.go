package stunprobe

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"net"
	"sync"
	"time"
)

const (
	// FluxChain reserves this command on managed Relay forwarders.
	TunnelProbeCommand = 0x04
	TunnelProbeAck     = "fluxchain-stun-accepted"
	ticketLifetime     = 15 * time.Second
	maxTickets         = 64
)

type tunnelTicket struct {
	service  string
	expires  time.Time
	linkOnly bool
}

var pendingTickets = struct {
	sync.Mutex
	values map[string]tunnelTicket
}{values: make(map[string]tunnelTicket)}

// PrepareTunnelProbe issues a one-use ticket for a specific exit service.
func PrepareTunnelProbe(service string, linkOnly bool) (string, error) {
	var random [16]byte
	if _, err := rand.Read(random[:]); err != nil {
		return "", err
	}
	token := hex.EncodeToString(random[:])
	pendingTickets.Lock()
	defer pendingTickets.Unlock()
	now := time.Now()
	for existing, ticket := range pendingTickets.values {
		if !now.Before(ticket.expires) {
			delete(pendingTickets.values, existing)
		}
	}
	if len(pendingTickets.values) >= maxTickets {
		return "", errors.New("出口节点的探测请求过多，请稍后重试")
	}
	pendingTickets.values[token] = tunnelTicket{service: service, expires: now.Add(ticketLifetime), linkOnly: linkOnly}
	return token, nil
}

func AcceptTunnelProbe(service, token string) (linkOnly, accepted bool) {
	pendingTickets.Lock()
	defer pendingTickets.Unlock()
	ticket, exists := pendingTickets.values[token]
	if !exists || ticket.service != service || !time.Now().Before(ticket.expires) {
		return false, false
	}
	delete(pendingTickets.values, token)
	return ticket.linkOnly, true
}

type tunnelProbeContextKey struct{}

func WithTunnelProbe(ctx context.Context, token string) context.Context {
	return context.WithValue(ctx, tunnelProbeContextKey{}, token)
}

func TunnelProbeToken(ctx context.Context) string {
	token, _ := ctx.Value(tunnelProbeContextKey{}).(string)
	return token
}

type DialUDP func(context.Context, string) (net.Conn, error)

func ProbeGoogle(ctx context.Context, dial DialUDP) (time.Duration, error) {
	var request [stunHeaderLen]byte
	if err := fillBindingRequest(request[:]); err != nil {
		return 0, err
	}
	_, latency, err := exchangeGoogle(ctx, request[:], dial)
	return latency, err
}

func exchangeGoogle(ctx context.Context, request []byte, dial DialUDP) ([]byte, time.Duration, error) {
	conn, err := dial(ctx, GoogleAddress)
	if err != nil {
		return nil, 0, err
	}
	defer conn.Close()
	return exchangeConnected(ctx, conn, request, maxSTUNReply, func(reply []byte) bool {
		return validBindingReply(reply, request)
	})
}

type TunnelTimings struct {
	LinkRTT *time.Duration
	STUNRTT *time.Duration
}

// MeasureTunnel reads the exit's echo and, unless linkOnly is set, the Google Binding reply.
// Both timings start when the datagram is sent over the established tunnel.
func MeasureTunnel(ctx context.Context, conn net.Conn, linkOnly bool) (TunnelTimings, error) {
	var result TunnelTimings
	var request [stunHeaderLen]byte
	if err := fillBindingRequest(request[:]); err != nil {
		return result, err
	}
	deadline, _ := ctx.Deadline()
	if err := conn.SetDeadline(deadline); err != nil {
		return result, err
	}
	started := time.Now()
	if _, err := conn.Write(request[:]); err != nil {
		return result, err
	}

	var reply [maxSTUNReply + 1]byte
	n, err := conn.Read(reply[:])
	if err != nil {
		return result, err
	}
	if !bytes.Equal(reply[:n], request[:]) {
		return result, errors.New("出口节点返回了无效的 UDP 回显")
	}
	link := time.Since(started)
	result.LinkRTT = &link
	if linkOnly {
		return result, nil
	}

	n, err = conn.Read(reply[:])
	if err != nil {
		return result, err
	}
	if n > maxSTUNReply || !validBindingReply(reply[:n], request[:]) {
		return result, errors.New("Google STUN 回包无效")
	}
	total := time.Since(started)
	result.STUNRTT = &total
	return result, nil
}

// RespondTunnel handles one datagram on an authenticated probe association.
func RespondTunnel(ctx context.Context, conn net.Conn, linkOnly bool, dial DialUDP) error {
	var request [stunHeaderLen + 1]byte
	n, err := conn.Read(request[:])
	if err != nil {
		return err
	}
	packet := request[:n]
	if n != stunHeaderLen || binary.BigEndian.Uint16(packet) != binding ||
		binary.BigEndian.Uint16(packet[2:]) != 0 || binary.BigEndian.Uint32(packet[4:]) != magicCookie {
		return errors.New("无效的 UDP STUN 探测包")
	}
	if _, err := conn.Write(packet); err != nil {
		return err
	}
	if linkOnly {
		return nil
	}
	reply, _, err := exchangeGoogle(ctx, packet, dial)
	if err != nil {
		return err
	}
	_, err = conn.Write(reply)
	return err
}
