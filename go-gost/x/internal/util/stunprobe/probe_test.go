package stunprobe

import (
	"bytes"
	"context"
	"encoding/binary"
	"net"
	"testing"
	"time"
)

func TestBindingReplyRequiresMatchingTransactionAndMappedAddress(t *testing.T) {
	request := make([]byte, stunHeaderLen)
	copy(request[8:], "transaction1")
	reply := make([]byte, stunHeaderLen+12)
	copy(reply, request)
	binary.BigEndian.PutUint16(reply, bindingOK)
	binary.BigEndian.PutUint16(reply[2:], 12)
	binary.BigEndian.PutUint32(reply[4:], magicCookie)
	binary.BigEndian.PutUint16(reply[20:], 0x0020)
	binary.BigEndian.PutUint16(reply[22:], 8)
	reply[25] = 1
	if !validBindingReply(reply, request) {
		t.Fatal("valid Binding response rejected")
	}
	wrongID := bytes.Clone(reply)
	wrongID[8] ^= 1
	if validBindingReply(wrongID, request) || validBindingReply(reply[:len(reply)-1], request) {
		t.Fatal("mismatched or truncated Binding response accepted")
	}
	reply[21] = 0x21
	if validBindingReply(reply, request) {
		t.Fatal("accepted a response without XOR-MAPPED-ADDRESS")
	}
}

func TestProbeDoesNotAcceptUdpEchoAsSuccessfulStun(t *testing.T) {
	listener, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	key := bytes.Repeat([]byte{1}, 32)
	go func() {
		packet := make([]byte, signedLen)
		n, peer, err := listener.ReadFrom(packet)
		if err == nil {
			listener.WriteTo(packet[:n], peer)
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	if elapsed, err := Probe(ctx, listener.LocalAddr().String(), key); err == nil {
		t.Fatalf("ordinary UDP echo was counted as a STUN response (%s)", elapsed)
	}
}

func TestExpiredOrUnsignedRelayDatagramsAreIgnored(t *testing.T) {
	packet := make([]byte, signedLen)
	binary.BigEndian.PutUint16(packet, binding)
	binary.BigEndian.PutUint32(packet[4:], magicCookie)
	binary.BigEndian.PutUint64(packet[stunHeaderLen:], uint64(time.Now().Unix()-120))
	key := bytes.Repeat([]byte{1}, 32)
	sign(packet, key)
	if reply, err := Relay(context.Background(), "invalid", packet, key); reply != nil || err != nil {
		t.Fatal("expired packet reached the next hop dialer")
	}
	binary.BigEndian.PutUint64(packet[stunHeaderLen:], uint64(time.Now().Unix()))
	if reply, err := Relay(context.Background(), "invalid", packet, key); reply != nil || err != nil {
		t.Fatal("unsigned packet reached the next hop dialer")
	}
}
