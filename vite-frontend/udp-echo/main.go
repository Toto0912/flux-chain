package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"log"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const (
	defaultEchoPort        = 19302
	portEnvVar             = "FLUXCHAIN_UDP_ECHO_PORT"
	keyEnvVar              = "FLUXCHAIN_UDP_ECHO_KEY"
	requestMagic           = "FCQ2"
	replyMagic             = "FCR2"
	magicLen               = 4
	timestampLen           = 8
	nonceLen               = 16
	headerLen              = magicLen + timestampLen + nonceLen
	packetLen              = headerLen + sha256.Size
	maxClockSkewSeconds    = 60
	maxRepliesPerSecond    = 256
	maxPerIPPerSecond      = 32
	maxTrackedIPsPerSecond = 256
)

func parseKey(raw string) ([]byte, error) {
	key, err := hex.DecodeString(strings.TrimSpace(raw))
	if err != nil || len(key) != sha256.Size {
		return nil, errors.New("环境变量 FLUXCHAIN_UDP_ECHO_KEY 必须为 64 位十六进制密钥")
	}
	return key, nil
}

func parsePort(raw string) (int, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return defaultEchoPort, nil
	}
	port, err := strconv.Atoi(trimmed)
	if err != nil || port < 1 || port > 65535 {
		return 0, errors.New("环境变量 FLUXCHAIN_UDP_ECHO_PORT 必须为 1-65535 之间的端口")
	}
	return port, nil
}

func main() {
	key, err := parseKey(os.Getenv(keyEnvVar))
	if err != nil {
		log.Fatal(err)
	}
	port, err := parsePort(os.Getenv(portEnvVar))
	if err != nil {
		log.Fatal(err)
	}
	listener := net.ListenConfig{Control: enablePacketInfo}
	packetConn, err := listener.ListenPacket(context.Background(), "udp", ":"+strconv.Itoa(port))
	if err != nil {
		log.Fatal(err)
	}
	defer packetConn.Close()
	if err := notifyReady(); err != nil {
		log.Fatal(err)
	}
	log.Printf("FluxChain UDP echo listening on %s", packetConn.LocalAddr())
	if err := serve(packetConn.(*net.UDPConn), key); err != nil {
		log.Fatal(err)
	}
}

type rateLimiter struct {
	second int64
	total  int
	perIP  map[netip.Addr]int
}

func (r *rateLimiter) allow(now int64, peer *net.UDPAddr) bool {
	if r.second != now {
		r.second = now
		r.total = 0
		clear(r.perIP)
	}
	if r.total >= maxRepliesPerSecond {
		return false
	}
	addr, ok := netip.AddrFromSlice(peer.IP)
	if !ok {
		return false
	}
	addr = addr.Unmap()
	count, exists := r.perIP[addr]
	if !exists && len(r.perIP) >= maxTrackedIPsPerSecond {
		return false
	}
	if count >= maxPerIPPerSecond {
		return false
	}
	r.total++
	r.perIP[addr] = count + 1
	return true
}

func verifyAndBuildReply(packet, key []byte, now int64) ([]byte, bool) {
	if len(packet) != packetLen || string(packet[:magicLen]) != requestMagic {
		return nil, false
	}
	sent := int64(binary.BigEndian.Uint64(packet[magicLen : magicLen+timestampLen]))
	if sent < now-maxClockSkewSeconds || sent > now+maxClockSkewSeconds {
		return nil, false
	}
	mac := hmac.New(sha256.New, key)
	mac.Write(packet[:headerLen])
	if !hmac.Equal(packet[headerLen:], mac.Sum(nil)) {
		return nil, false
	}

	reply := make([]byte, packetLen)
	copy(reply[:magicLen], replyMagic)
	copy(reply[magicLen:headerLen], packet[magicLen:headerLen])
	replyMac := hmac.New(sha256.New, key)
	replyMac.Write(reply[:headerLen])
	copy(reply[headerLen:], replyMac.Sum(nil))
	return reply, true
}

func serve(conn *net.UDPConn, key []byte) error {
	var packet [packetLen + 1]byte
	var control [96]byte
	limiter := rateLimiter{perIP: make(map[netip.Addr]int)}
	for {
		n, controlLen, _, peer, err := conn.ReadMsgUDP(packet[:], control[:])
		if err != nil {
			return err
		}
		now := time.Now().Unix()
		reply, ok := verifyAndBuildReply(packet[:n], key, now)
		if !ok {
			continue
		}
		if !limiter.allow(now, peer) {
			continue
		}
		_, _, _ = conn.WriteMsgUDP(reply, replyControl(control[:controlLen], peer.IP.To4() != nil), peer)
	}
}

func enablePacketInfo(network, _ string, raw syscall.RawConn) error {
	var socketErr error
	if err := raw.Control(func(fd uintptr) {
		socketErr = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IP, syscall.IP_PKTINFO, 1)
		if socketErr == nil && network == "udp6" {
			socketErr = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IPV6, syscall.IPV6_RECVPKTINFO, 1)
		}
	}); err != nil {
		return err
	}
	return socketErr
}

// Reply from the address the probe contacted, including on multi-IP servers.
func replyControl(control []byte, ipv4 bool) []byte {
	messages, _ := syscall.ParseSocketControlMessage(control)
	for _, message := range messages {
		size := min(len(control), syscall.CmsgSpace(len(message.Data)))
		raw := control[:size]
		control = control[size:]
		if ipv4 && message.Header.Level == syscall.IPPROTO_IP && message.Header.Type == syscall.IP_PKTINFO && len(message.Data) == 12 {
			clear(message.Data[:4])                     // ipi_ifindex
			copy(message.Data[4:8], message.Data[8:12]) // reply source from ipi_addr
			return raw
		}
		if !ipv4 && message.Header.Level == syscall.IPPROTO_IPV6 && message.Header.Type == syscall.IPV6_PKTINFO {
			return raw
		}
	}
	return nil
}

func notifyReady() error {
	name := os.Getenv("NOTIFY_SOCKET")
	if name == "" {
		return nil
	}
	conn, err := net.DialUnix("unixgram", nil, &net.UnixAddr{Name: name, Net: "unixgram"})
	if err != nil {
		return err
	}
	defer conn.Close()
	_, err = conn.Write([]byte("READY=1"))
	return err
}
