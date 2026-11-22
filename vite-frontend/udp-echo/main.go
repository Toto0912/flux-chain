package main

import (
	"context"
	"log"
	"net"
	"os"
	"syscall"
	"time"
)

const (
	echoAddress         = ":19302"
	echoPrefix          = "FCE1"
	echoLength          = len(echoPrefix) + 16
	maxRepliesPerSecond = 1024
)

func main() {
	listener := net.ListenConfig{Control: enablePacketInfo}
	packetConn, err := listener.ListenPacket(context.Background(), "udp", echoAddress)
	if err != nil {
		log.Fatal(err)
	}
	defer packetConn.Close()
	if err := notifyReady(); err != nil {
		log.Fatal(err)
	}
	log.Printf("FluxChain UDP echo listening on %s", packetConn.LocalAddr())
	if err := serve(packetConn.(*net.UDPConn)); err != nil {
		log.Fatal(err)
	}
}

func serve(conn *net.UDPConn) error {
	var packet [echoLength + 1]byte
	var control [96]byte
	var second int64
	var replies int
	for {
		n, controlLen, _, peer, err := conn.ReadMsgUDP(packet[:], control[:])
		if err != nil {
			return err
		}
		if n != echoLength || string(packet[:len(echoPrefix)]) != echoPrefix {
			continue
		}
		if now := time.Now().Unix(); second != now {
			second, replies = now, 0
		}
		if replies >= maxRepliesPerSecond {
			continue
		}
		replies++
		_, _, _ = conn.WriteMsgUDP(packet[:n], replyControl(control[:controlLen], peer.IP.To4() != nil), peer)
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
