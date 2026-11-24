package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"net"
	"net/netip"
	"testing"
	"time"
)

func buildSignedPacket(magic string, ts int64, key []byte) []byte {
	pkt := make([]byte, packetLen)
	copy(pkt[:magicLen], magic)
	binary.BigEndian.PutUint64(pkt[magicLen:magicLen+timestampLen], uint64(ts))
	copy(pkt[magicLen+timestampLen:headerLen], []byte("0123456789abcdef"))
	mac := hmac.New(sha256.New, key)
	mac.Write(pkt[:headerLen])
	copy(pkt[headerLen:], mac.Sum(nil))
	return pkt
}

func TestVerifyAndBuildReply(t *testing.T) {
	key := bytes.Repeat([]byte{0xab}, sha256.Size)
	now := int64(1700000000)

	req := buildSignedPacket(requestMagic, now, key)
	reply, ok := verifyAndBuildReply(req, key, now)
	if !ok {
		t.Fatal("expected valid FCQ2 packet to be accepted")
	}
	if string(reply[:magicLen]) != replyMagic {
		t.Fatalf("expected magic %s, got %s", replyMagic, string(reply[:magicLen]))
	}
	if !bytes.Equal(reply[magicLen:headerLen], req[magicLen:headerLen]) {
		t.Fatal("expected timestamp and nonce to be preserved")
	}
	mac := hmac.New(sha256.New, key)
	mac.Write(reply[:headerLen])
	if !hmac.Equal(reply[headerLen:], mac.Sum(nil)) {
		t.Fatal("expected valid HMAC on FCR2 reply")
	}

	// Legacy FCE1
	if _, ok := verifyAndBuildReply([]byte("FCE10123456789abcdef"), key, now); ok {
		t.Fatal("expected legacy FCE1 packet to be rejected")
	}
	// Wrong key
	wrongKey := bytes.Repeat([]byte{0xcd}, sha256.Size)
	if _, ok := verifyAndBuildReply(buildSignedPacket(requestMagic, now, wrongKey), key, now); ok {
		t.Fatal("expected wrong-key packet to be rejected")
	}
	// Expired timestamp
	if _, ok := verifyAndBuildReply(buildSignedPacket(requestMagic, now-120, key), key, now); ok {
		t.Fatal("expected expired packet to be rejected")
	}
	// Replayed FCR2 reply
	if _, ok := verifyAndBuildReply(buildSignedPacket(replyMagic, now, key), key, now); ok {
		t.Fatal("expected FCR2 reply packet to be rejected as request")
	}
}

func TestServeRejectsBadPacketsWithoutExhaustingRateLimit(t *testing.T) {
	key := bytes.Repeat([]byte{0x11}, sha256.Size)
	wrongKey := bytes.Repeat([]byte{0x22}, sha256.Size)

	serverAddr, err := net.ResolveUDPAddr("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	serverConn, err := net.ListenUDP("udp4", serverAddr)
	if err != nil {
		t.Fatal(err)
	}
	defer serverConn.Close()

	go func() {
		_ = serve(serverConn, key)
	}()

	clientConn, err := net.DialUDP("udp4", nil, serverConn.LocalAddr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer clientConn.Close()

	// Send >300 invalid packets (legacy FCE1, wrong key, FCR2 replay)
	now := time.Now().Unix()
	for i := 0; i < 120; i++ {
		_, _ = clientConn.Write([]byte("FCE10123456789abcdef"))
		_, _ = clientConn.Write(buildSignedPacket(requestMagic, now, wrongKey))
		_, _ = clientConn.Write(buildSignedPacket(replyMagic, now, key))
	}

	// Verify no reply to bad packets
	_ = clientConn.SetReadDeadline(time.Now().Add(80 * time.Millisecond))
	buf := make([]byte, 128)
	if n, err := clientConn.Read(buf); err == nil {
		t.Fatalf("expected no reply to invalid packets, got %d bytes", n)
	}

	// Subsequent valid packet must still succeed
	req := buildSignedPacket(requestMagic, time.Now().Unix(), key)
	if _, err := clientConn.Write(req); err != nil {
		t.Fatal(err)
	}
	_ = clientConn.SetReadDeadline(time.Now().Add(time.Second))
	n, err := clientConn.Read(buf)
	if err != nil {
		t.Fatalf("expected valid packet after bad flood to succeed, got err: %v", err)
	}
	if n != packetLen || string(buf[:magicLen]) != replyMagic {
		t.Fatalf("unexpected reply: len=%d magic=%q", n, string(buf[:min(n, magicLen)]))
	}
}

func TestPerIPRateLimit(t *testing.T) {
	limiter := rateLimiter{perIP: make(map[netip.Addr]int)}
	peer1 := &net.UDPAddr{IP: net.ParseIP("198.51.100.10"), Port: 12345}
	peer2 := &net.UDPAddr{IP: net.ParseIP("198.51.100.11"), Port: 12345}
	now := int64(1700000000)

	for i := 0; i < maxPerIPPerSecond; i++ {
		if !limiter.allow(now, peer1) {
			t.Fatalf("expected request %d from peer1 to be allowed", i+1)
		}
	}
	if limiter.allow(now, peer1) {
		t.Fatal("expected 33rd request from peer1 in same second to be rate limited")
	}
	if !limiter.allow(now, peer2) {
		t.Fatal("expected peer2 to have its own per-IP quota")
	}
	if !limiter.allow(now+1, peer1) {
		t.Fatal("expected peer1 quota to reset on next second")
	}
}

func TestParsePort(t *testing.T) {
	if port, err := parsePort(""); err != nil || port != defaultEchoPort {
		t.Fatalf("expected default port %d, got %d err=%v", defaultEchoPort, port, err)
	}
	if port, err := parsePort(" 29302 "); err != nil || port != 29302 {
		t.Fatalf("expected port 29302, got %d err=%v", port, err)
	}
	for _, bad := range []string{"0", "65536", "-1", "abc"} {
		if _, err := parsePort(bad); err == nil {
			t.Fatalf("expected parsePort(%q) to fail", bad)
		}
	}
}
