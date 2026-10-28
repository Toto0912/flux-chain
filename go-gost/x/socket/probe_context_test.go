package socket

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

func TestProbeDialSuccess(t *testing.T) {
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	got, err := dialProbeContext(context.Background(), func(context.Context) (net.Conn, error) { return a, nil })
	if err != nil || got != a {
		t.Fatalf("got %v, %v", got, err)
	}
}

func TestProbeDialFailure(t *testing.T) {
	want := errors.New("target unavailable")
	got, err := dialProbeContext(context.Background(), func(context.Context) (net.Conn, error) { return nil, want })
	if got != nil || !errors.Is(err, want) {
		t.Fatalf("got %v, %v", got, err)
	}
}

func TestProbeDialAlreadyCanceledDoesNotStart(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := dialProbeContext(ctx, func(context.Context) (net.Conn, error) {
		t.Fatal("dial must not start for a canceled probe")
		return nil, nil
	})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("got %v", err)
	}
}

func TestProbeDialDeadlineClosesLateConnection(t *testing.T) {
	a, b := net.Pipe()
	defer b.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	release := make(chan struct{})
	got, err := dialProbeContext(ctx, func(context.Context) (net.Conn, error) {
		<-release
		return a, nil
	})
	if got != nil || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("got %v, %v", got, err)
	}
	close(release)
	_ = b.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := b.Read(make([]byte, 1)); err == nil {
		t.Fatal("late connection must be closed without sending application bytes")
	} else if nerr, ok := err.(net.Error); ok && nerr.Timeout() {
		t.Fatal("late connection was not closed")
	}
}
