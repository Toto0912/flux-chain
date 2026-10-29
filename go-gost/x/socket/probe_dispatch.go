package socket

import "context"

// No pending probe queue: a slow diagnostic must never stop WebSocket control
// commands, or leave work queued after the panel has already timed it out.
type boundedProbeDispatcher struct {
	ctx   context.Context
	slots chan struct{}
}

func newBoundedProbeDispatcher(ctx context.Context, concurrency int) *boundedProbeDispatcher {
	return &boundedProbeDispatcher{ctx: ctx, slots: make(chan struct{}, concurrency)}
}

func (d *boundedProbeDispatcher) trySubmit(probe func()) bool {
	select {
	case <-d.ctx.Done():
		return false
	default:
	}
	select {
	case d.slots <- struct{}{}:
		go func() {
			defer func() { <-d.slots }()
			select {
			case <-d.ctx.Done():
				return
			default:
				probe()
			}
		}()
		return true
	default:
		return false
	}
}
