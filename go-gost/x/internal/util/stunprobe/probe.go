// Package stunprobe implements FluxChain's UDP-only probe between managed nodes.
package stunprobe

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"net"
	"time"
)

const (
	GoogleAddress = "stun.l.google.com:19302"
	binding       = 0x0001
	bindingOK     = 0x0101
	magicCookie   = 0x2112a442
	stunHeaderLen = 20
	signedLen     = stunHeaderLen + 8 + sha256.Size
	maxSTUNReply  = 256
	MaxTimeout    = 5 * time.Second
)

func ParseKey(value string) ([]byte, error) {
	key, err := hex.DecodeString(value)
	if err != nil || len(key) != sha256.Size {
		return nil, errors.New("UDP STUN 探测密钥无效")
	}
	return key, nil
}

// Probe times one complete request and response across the selected UDP relays.
func Probe(ctx context.Context, firstHop string, key []byte) (time.Duration, error) {
	request := make([]byte, signedLen)
	if err := fillBindingRequest(request); err != nil {
		return 0, err
	}
	binary.BigEndian.PutUint64(request[stunHeaderLen:], uint64(time.Now().Unix()))
	sign(request, key)
	_, elapsed, err := exchange(ctx, firstHop, request, signedLen, func(reply []byte) bool {
		return validSigned(reply, key, bindingOK) && sameTransaction(reply, request)
	})
	return elapsed, err
}

// Relay accepts only an authenticated, short-lived probe. The last node checks
// either the configured echo service or Google STUN before acknowledging it.
func Relay(ctx context.Context, nextHop, echoTarget string, packet, key []byte) ([]byte, error) {
	if !validSigned(packet, key, binding) {
		return nil, nil
	}
	if nextHop != "" {
		reply, _, err := exchange(ctx, nextHop, packet, signedLen, func(reply []byte) bool {
			return validSigned(reply, key, bindingOK) && sameTransaction(reply, packet)
		})
		return reply, err
	}

	var err error
	if echoTarget == "" {
		_, _, err = exchange(ctx, GoogleAddress, packet[:stunHeaderLen], maxSTUNReply, func(reply []byte) bool {
			return validBindingReply(reply, packet)
		})
	} else {
		dialer := net.Dialer{}
		_, err = ProbeFixedEcho(ctx, echoTarget, func(ctx context.Context, addr string) (net.Conn, error) {
			return dialer.DialContext(ctx, "udp", addr)
		})
	}
	if err != nil {
		return nil, err
	}
	receipt := bytes.Clone(packet)
	binary.BigEndian.PutUint16(receipt, bindingOK)
	sign(receipt, key)
	return receipt, nil
}

func fillBindingRequest(packet []byte) error {
	binary.BigEndian.PutUint16(packet, binding)
	binary.BigEndian.PutUint32(packet[4:], magicCookie)
	_, err := rand.Read(packet[8:stunHeaderLen])
	return err
}

func sign(packet, key []byte) {
	mac := hmac.New(sha256.New, key)
	mac.Write(packet[:signedLen-sha256.Size])
	copy(packet[signedLen-sha256.Size:], mac.Sum(nil))
}

func validSigned(packet, key []byte, kind uint16) bool {
	if len(packet) != signedLen || binary.BigEndian.Uint16(packet) != kind ||
		binary.BigEndian.Uint16(packet[2:]) != 0 || binary.BigEndian.Uint32(packet[4:]) != magicCookie {
		return false
	}
	sent := int64(binary.BigEndian.Uint64(packet[stunHeaderLen:]))
	now := time.Now().Unix()
	if sent < now-30 || sent > now+30 {
		return false
	}
	mac := hmac.New(sha256.New, key)
	mac.Write(packet[:signedLen-sha256.Size])
	return hmac.Equal(packet[signedLen-sha256.Size:], mac.Sum(nil))
}

func sameTransaction(reply, request []byte) bool {
	return bytes.Equal(reply[8:stunHeaderLen], request[8:stunHeaderLen])
}

// RFC 8489 Binding Success carries a matching transaction ID and an
// XOR-MAPPED-ADDRESS attribute of the appropriate size for its family.
func validBindingReply(reply, request []byte) bool {
	if len(reply) < stunHeaderLen || binary.BigEndian.Uint16(reply) != bindingOK ||
		binary.BigEndian.Uint32(reply[4:]) != magicCookie || !sameTransaction(reply, request) {
		return false
	}
	attrs := reply[stunHeaderLen:]
	if int(binary.BigEndian.Uint16(reply[2:])) != len(attrs) {
		return false
	}
	mapped := false
	for len(attrs) >= 4 {
		kind, size := binary.BigEndian.Uint16(attrs), int(binary.BigEndian.Uint16(attrs[2:]))
		padded := (size + 3) &^ 3
		attrs = attrs[4:]
		if padded > len(attrs) {
			return false
		}
		if kind == 0x0020 && ((size == 8 && attrs[1] == 1) || (size == 20 && attrs[1] == 2)) {
			mapped = true
		}
		attrs = attrs[padded:]
	}
	return mapped && len(attrs) == 0
}

func exchange(ctx context.Context, addr string, request []byte, maxReply int, matches func([]byte) bool) ([]byte, time.Duration, error) {
	dialer := net.Dialer{}
	conn, err := dialer.DialContext(ctx, "udp", addr)
	if err != nil {
		return nil, 0, err
	}
	defer conn.Close()
	return exchangeConnected(ctx, conn, request, maxReply, matches)
}

func exchangeConnected(ctx context.Context, conn net.Conn, request []byte, maxReply int, matches func([]byte) bool) ([]byte, time.Duration, error) {
	deadline, _ := ctx.Deadline()
	if err := conn.SetDeadline(deadline); err != nil {
		return nil, 0, err
	}
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()

	started := time.Now()
	if _, err := conn.Write(request); err != nil {
		return nil, 0, err
	}
	reply := make([]byte, maxReply+1)
	for {
		n, err := conn.Read(reply)
		if err != nil {
			if ctx.Err() != nil {
				return nil, 0, ctx.Err()
			}
			return nil, 0, err
		}
		if n <= maxReply && matches(reply[:n]) {
			return reply[:n], time.Since(started), nil
		}
	}
}
