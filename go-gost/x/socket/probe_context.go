package socket

import (
	"context"
	"net"
)

// Relay handshakes may read from a stream that does not observe ctx directly.
// Bound the externally visible probe deadline and close any late connection.
// Relay connectors also have a finite connectTimeout so the dial worker exits.
func dialProbeContext(ctx context.Context, dial func(context.Context) (net.Conn, error)) (net.Conn, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	type result struct {
		conn net.Conn
		err  error
	}
	ready := make(chan result)
	go func() {
		conn, err := dial(ctx)
		select {
		case ready <- result{conn, err}:
		case <-ctx.Done():
			if conn != nil {
				_ = conn.Close()
			}
		}
	}()
	select {
	case value := <-ready:
		if err := ctx.Err(); err != nil {
			if value.conn != nil {
				_ = value.conn.Close()
			}
			return nil, err
		}
		return value.conn, value.err
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}
