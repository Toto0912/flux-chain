package socket

import (
	"context"
	"sync/atomic"
	"testing"
	"time"
)

func TestProbeAdmissionIsNonBlockingAndDoesNotQueueExcessWork(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	d := newBoundedProbeDispatcher(ctx, 16)
	started := make(chan struct{}, 16)
	release := make(chan struct{})
	finished := make(chan struct{}, 16)
	defer close(release)
	var active, peak atomic.Int32
	for i := 0; i < 16; i++ {
		if !d.trySubmit(func() {
			current := active.Add(1)
			for old := peak.Load(); current > old && !peak.CompareAndSwap(old, current); old = peak.Load() {
			}
			started <- struct{}{}
			<-release
			active.Add(-1)
			finished <- struct{}{}
		}) {
			t.Fatal("available probe slot rejected")
		}
	}
	for i := 0; i < 16; i++ {
		select {
		case <-started:
		case <-time.After(time.Second):
			t.Fatal("probe did not start concurrently")
		}
	}
	begin := time.Now()
	for i := 0; i < 100; i++ {
		if d.trySubmit(func() { t.Error("excess probe was queued") }) {
			t.Fatal("full dispatcher accepted another probe")
		}
	}
	if time.Since(begin) > time.Second {
		t.Fatal("probe admission blocked the control reader")
	}
	if peak.Load() != 16 {
		t.Fatalf("expected 16 concurrent probes, got %d", peak.Load())
	}
}

func TestProbeSlotIsReusableAfterCompletion(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	d := newBoundedProbeDispatcher(ctx, 1)
	done := make(chan struct{}, 2)
	if !d.trySubmit(func() { done <- struct{}{} }) {
		t.Fatal("initial probe rejected")
	}
	<-done
	deadline := time.Now().Add(time.Second)
	for !d.trySubmit(func() { done <- struct{}{} }) {
		if time.Now().After(deadline) {
			t.Fatal("completed probe did not release slot")
		}
		time.Sleep(time.Millisecond)
	}
	<-done
}

func TestStoppedReporterRejectsProbes(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	d := newBoundedProbeDispatcher(ctx, 16)
	cancel()
	if d.trySubmit(func() { t.Error("probe ran after stop") }) {
		t.Fatal("stopped dispatcher accepted a probe")
	}
}
