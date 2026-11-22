package forwardhealth

import (
	"context"
	"errors"
	"net"
	"reflect"
	"testing"

	"github.com/go-gost/core/chain"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/internal/util/stunprobe"
)

func TestFixedEchoGroupsPortsByHost(t *testing.T) {
	nodes := []*config.ForwardNodeConfig{{Addr: "edge.example:1000"}, {Addr: "[2001:db8::1]:2000"}, {Addr: "edge.example:3000"}}
	m, err := New(&config.ForwardTargetHealthConfig{
		UDPMode: "echo_fixed", IntervalSeconds: 5, TimeoutSeconds: 1, SwitchThresholdMs: 1,
	}, nodes, "latency", "udp", nil)
	if err != nil {
		t.Fatal(err)
	}
	want := []probeDestination{
		{address: "edge.example:19302", targetIndexes: []int{0, 2}},
		{address: "[2001:db8::1]:19302", targetIndexes: []int{1}},
	}
	if got := m.findProbe("udp").destinations; !reflect.DeepEqual(got, want) {
		t.Fatalf("expected %v, got %v", want, got)
	}
}

var testNodes = []*config.ForwardNodeConfig{{Addr: "a:1234"}, {Addr: "b:1234"}, {Addr: "c:1234"}}

func TestGoogleSTUNProbesEgressOnceWithoutSelectingTargets(t *testing.T) {
	var addresses []string
	m, err := New(&config.ForwardTargetHealthConfig{
		UDPMode: "stun", IntervalSeconds: 5, TimeoutSeconds: 1, SwitchThresholdMs: 1,
		UDPTargets: []string{"obsolete-stun-port:30000"},
	}, testNodes, "latency", "relay", func(_ context.Context, network, address string) (net.Conn, error) {
		addresses = append(addresses, network+"/"+address)
		return nil, errors.New("simulated egress outage")
	})
	if err != nil {
		t.Fatal(err)
	}
	m.runRound(context.Background())
	if want := []string{"udp/" + stunprobe.GoogleAddress}; !reflect.DeepEqual(addresses, want) {
		t.Fatalf("expected one probe to Google, got %v", addresses)
	}
	state := m.Status()["udp"]
	if state.Egress == nil || state.Egress.ProbeAddress != stunprobe.GoogleAddress || state.Egress.Available ||
		state.Egress.Error != "simulated egress outage" || state.LastProbeAt == 0 ||
		len(state.Targets) != 0 || state.AllUnavailable || state.SelectedTarget != "" {
		t.Fatalf("unexpected egress snapshot: %+v", state)
	}
	if _, handled := m.Select(context.Background(), "udp", nil); handled || m.SelectsTargets("udp") {
		t.Fatal("egress status affected UDP target selection")
	}
}

func available(target string, latency float64) Sample {
	return Sample{Target: target, Available: true, LatencyMs: &latency}
}

func testMonitor(t *testing.T, strategy string) *Monitor {
	t.Helper()
	m, err := New(&config.ForwardTargetHealthConfig{
		TCP: true, UDPMode: "echo", IntervalSeconds: 5, TimeoutSeconds: 1, SwitchThresholdMs: 10,
	}, testNodes, strategy, "relay", nil)
	if err != nil {
		t.Fatal(err)
	}
	return m
}

func TestLatencyThresholdAndFallbackAreIndependentByProtocol(t *testing.T) {
	m := testMonitor(t, "latency")
	udp := m.findProbe("udp")
	m.publish(udp, []Sample{available("a:1234", 25), available("b:1234", 20)})
	if got := m.Status()["udp"].SelectedTarget; got != "a:1234" {
		t.Fatalf("small improvement changed the route: %s", got)
	}
	m.publish(udp, []Sample{available("a:1234", 25), available("b:1234", 10)})
	if got := m.Status()["udp"].SelectedTarget; got != "b:1234" {
		t.Fatalf("faster target was not selected: %s", got)
	}
	m.publish(udp, []Sample{{Target: "a:1234"}, {Target: "b:1234"}})
	status := m.Status()
	if !status["udp"].AllUnavailable || status["udp"].SelectedTarget != "b:1234" ||
		status["tcp"].SelectedTarget != "a:1234" || status["tcp"].LastProbeAt != 0 {
		t.Fatalf("all-failed fallback or protocol isolation failed: %+v", status)
	}
	m.publish(udp, []Sample{available("a:1234", 25), {Target: "b:1234"}})
	if got := m.Status()["udp"].SelectedTarget; got != "a:1234" {
		t.Fatalf("failed current target was not replaced: %s", got)
	}
}

func TestRoundRobinKeepsPreviousEligibleTargetsAcrossTotalFailure(t *testing.T) {
	m := testMonitor(t, "round")
	probe := m.findProbe("tcp")
	m.publish(probe, []Sample{available("a:1234", 2), {Target: "b:1234"}, available("c:1234", 5)})
	m.publish(probe, []Sample{{Target: "a:1234"}, {Target: "b:1234"}, {Target: "c:1234"}})
	nodes := []*chain.Node{chain.NewNode("a", "a:1234"), chain.NewNode("b", "b:1234"), chain.NewNode("c", "c:1234")}
	for _, want := range []string{"a:1234", "c:1234", "a:1234", "c:1234"} {
		got, handled := m.Select(context.Background(), "tcp", nodes)
		if !handled || got == nil || got.Addr != want {
			t.Fatalf("expected %s, got %+v", want, got)
		}
	}
}
