package quic

import (
	"crypto/tls"
	"errors"
	"io"
	"net"
	"testing"
	"time"

	"github.com/go-gost/core/listener"
	xlogger "github.com/go-gost/x/logger"
)

func newTestListener(addr string, config *tls.Config) *quicListener {
	return &quicListener{
		logger:  xlogger.NewLogger(xlogger.OutputOption(io.Discard)),
		options: listener.Options{Addr: addr, TLSConfig: config},
	}
}

func TestCloseReleasesUdpPortImmediately(t *testing.T) {
	config := &tls.Config{GetCertificate: func(*tls.ClientHelloInfo) (*tls.Certificate, error) {
		return nil, errors.New("no handshake needed in listener lifecycle test")
	}}
	l := newTestListener("127.0.0.1:0", config)
	if err := l.Init(nil); err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	addr := l.Addr().String()
	if err := l.Close(); err != nil {
		t.Fatal(err)
	}
	conn, err := net.ListenPacket("udp", addr)
	if err != nil {
		t.Fatalf("closed QUIC listener retained UDP port: %v", err)
	}
	conn.Close()
	replacement := newTestListener(addr, config.Clone())
	if err := replacement.Init(nil); err != nil {
		t.Fatalf("closed QUIC listener cannot be rebound immediately: %v", err)
	}
	defer replacement.Close()
	if err := l.Close(); err != nil {
		t.Fatalf("repeated close should be safe: %v", err)
	}
}

func TestFailedInitializationDoesNotCloseAnExistingSocket(t *testing.T) {
	free, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := free.LocalAddr().String()
	defer free.Close()
	l := newTestListener(addr, &tls.Config{}) // The port belongs to another socket.
	if err := l.Init(nil); err == nil {
		l.Close()
		t.Fatal("initialization unexpectedly accepted an occupied UDP port")
	}
	if err := l.Close(); err != nil {
		t.Fatal(err)
	}
	if err := free.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatalf("failed initialization closed a socket owned by another listener: %v", err)
	}
	if _, err := free.WriteTo([]byte("owned"), free.LocalAddr()); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 10)
	if _, _, err := free.ReadFrom(buf); err != nil {
		t.Fatal(err)
	}
}

func TestCloseBeforeInitializationIsSafe(t *testing.T) {
	if err := (&quicListener{}).Close(); err != nil {
		t.Fatal(err)
	}
}
