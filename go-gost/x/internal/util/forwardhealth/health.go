package forwardhealth

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/go-gost/core/chain"
	"github.com/go-gost/core/selector"
	"github.com/go-gost/x/config"
	selectorparser "github.com/go-gost/x/config/parsing/selector"
	"github.com/go-gost/x/internal/util/stunprobe"
)

const (
	probesPerForward = 4
	roundsPerNode    = 8
	googleSTUNMode   = "stun"
)

var (
	probeSlots = make(chan struct{}, 16)
	roundSlots = make(chan struct{}, roundsPerNode)
)

type Sample struct {
	Target       string   `json:"target,omitempty"`
	ProbeAddress string   `json:"probeAddress"`
	Available    bool     `json:"available"`
	LatencyMs    *float64 `json:"latencyMs,omitempty"`
	Error        string   `json:"error,omitempty"`
}

type Snapshot struct {
	LastProbeAt    int64    `json:"lastProbeAt"`
	AllUnavailable bool     `json:"allUnavailable"`
	SelectedTarget string   `json:"selectedTarget,omitempty"`
	Targets        []Sample `json:"targets"`
	Egress         *Sample  `json:"egress,omitempty"`
}

type roundState struct {
	snapshot Snapshot
	eligible map[string]bool
}

type probeDestination struct {
	address       string
	targetIndexes []int
}

type protocolProbe struct {
	network      string
	mode         string
	destinations []probeDestination
	selectBy     selector.Strategy[*chain.Node]
	state        atomic.Pointer[roundState]
}

type Monitor struct {
	config    config.ForwardTargetHealthConfig
	strategy  string
	addresses []string
	probes    []*protocolProbe
	echoKey   []byte
	dial      func(context.Context, string, string) (net.Conn, error)
}

func New(check *config.ForwardTargetHealthConfig, nodes []*config.ForwardNodeConfig, strategy, handlerType string, dial func(context.Context, string, string) (net.Conn, error)) (*Monitor, error) {
	if check == nil {
		return nil, nil
	}
	if check.IntervalSeconds < 5 || check.IntervalSeconds > 3600 ||
		check.TimeoutSeconds < 1 || check.TimeoutSeconds > 5 ||
		check.SwitchThresholdMs < 1 || check.SwitchThresholdMs > check.TimeoutSeconds*1000 {
		return nil, errors.New("目标探测的间隔、超时或切换阈值无效")
	}
	if check.UDPMode != "off" && check.UDPMode != "echo" && check.UDPMode != googleSTUNMode && check.UDPMode != "echo_fixed" {
		return nil, errors.New("目标 UDP 探测方式无效")
	}
	m := &Monitor{config: *check, strategy: strategy, dial: dial}
	if check.UDPMode == "echo_fixed" {
		if check.UDPEchoPort != 0 && (check.UDPEchoPort < 1 || check.UDPEchoPort > 65535) {
			return nil, errors.New("UDP 回显端口必须在 1 到 65535 之间")
		}
		if strings.TrimSpace(check.UDPEchoKey) != "" {
			echoKey, err := stunprobe.ParseEchoKey(check.UDPEchoKey)
			if err != nil {
				return nil, err
			}
			m.echoKey = echoKey
		}
	}
	for _, node := range nodes {
		if node == nil {
			continue
		}
		if !validAddress(node.Addr) {
			return nil, fmt.Errorf("无效的转发目标 %s", node.Addr)
		}
		m.addresses = append(m.addresses, node.Addr)
	}
	if len(m.addresses) < 2 {
		return nil, errors.New("目标探测至少需要两个转发目标")
	}
	if check.UDPMode == "echo" {
		if len(check.UDPTargets) != 0 && len(check.UDPTargets) != len(m.addresses) {
			return nil, errors.New("UDP 探测地址与转发目标数不一致")
		}
		for _, address := range check.UDPTargets {
			if address != "" && !validAddress(address) {
				return nil, fmt.Errorf("无效的 UDP 探测地址 %s", address)
			}
		}
	}
	if check.TCP && (handlerType == "tcp" || handlerType == "relay") {
		m.addProbe("tcp", "tcp")
	}
	if check.UDPMode != "off" && (handlerType == "udp" || handlerType == "relay") {
		m.addProbe("udp", check.UDPMode)
	}
	if len(m.probes) == 0 {
		return nil, nil
	}
	return m, nil
}

func validAddress(address string) bool {
	host, portText, err := net.SplitHostPort(address)
	port, portErr := strconv.Atoi(portText)
	return err == nil && portErr == nil && host != "" && port > 0 && port <= 65535
}

func (m *Monitor) addProbe(network, mode string) {
	probe := &protocolProbe{network: network, mode: mode}
	initial := Snapshot{Targets: []Sample{}}
	if mode == googleSTUNMode {
		probe.destinations = []probeDestination{{address: stunprobe.GoogleAddress}}
		initial.Egress = &Sample{ProbeAddress: stunprobe.GoogleAddress}
	} else {
		probe.destinations = m.targetDestinations(mode)
		probe.selectBy = selectorparser.ParseNodeStrategy(m.strategy)
		if m.strategy == "latency" || m.strategy == "fifo" {
			initial.SelectedTarget = m.addresses[0]
		}
	}
	probe.state.Store(&roundState{snapshot: initial})
	m.probes = append(m.probes, probe)
}

func (m *Monitor) targetDestinations(mode string) []probeDestination {
	destinations := make([]probeDestination, 0, len(m.addresses))
	fixedAddresses := make(map[string]int)
	for i, target := range m.addresses {
		address := target
		if mode == "echo_fixed" {
			host, _, _ := net.SplitHostPort(target)
			port := stunprobe.EchoPort
			if m.config.UDPEchoPort > 0 {
				port = strconv.Itoa(m.config.UDPEchoPort)
			}
			address = net.JoinHostPort(host, port)
			if existing, ok := fixedAddresses[address]; ok {
				destinations[existing].targetIndexes = append(destinations[existing].targetIndexes, i)
				continue
			}
			fixedAddresses[address] = len(destinations)
		} else if mode == "echo" && len(m.config.UDPTargets) > 0 && m.config.UDPTargets[i] != "" {
			address = m.config.UDPTargets[i]
		}
		destinations = append(destinations, probeDestination{address: address, targetIndexes: []int{i}})
	}
	return destinations
}

func (m *Monitor) findProbe(network string) *protocolProbe {
	if m != nil {
		for _, probe := range m.probes {
			if probe.network == network {
				return probe
			}
		}
	}
	return nil
}

func (m *Monitor) SelectsTargets(network string) bool {
	probe := m.findProbe(network)
	return probe != nil && probe.selectBy != nil
}

func (m *Monitor) Status() map[string]Snapshot {
	result := make(map[string]Snapshot)
	if m != nil {
		for _, probe := range m.probes {
			result[probe.network] = probe.state.Load().snapshot
		}
	}
	return result
}

func (m *Monitor) Select(ctx context.Context, network string, nodes []*chain.Node) (*chain.Node, bool) {
	probe := m.findProbe(network)
	if probe == nil || probe.selectBy == nil {
		return nil, false
	}
	state := probe.state.Load()
	eligible := nodes
	if len(state.eligible) > 0 {
		eligible = make([]*chain.Node, 0, len(nodes))
		for _, node := range nodes {
			if state.eligible[node.Addr] {
				eligible = append(eligible, node)
			}
		}
		if len(eligible) == 0 {
			return nil, true
		}
	}
	if m.strategy == "latency" {
		for _, node := range eligible {
			if node.Addr == state.snapshot.SelectedTarget {
				return node, true
			}
		}
	}
	return probe.selectBy.Apply(ctx, eligible...), true
}

func (m *Monitor) Run(ctx context.Context) {
	if m == nil {
		return
	}
	interval := time.Duration(m.config.IntervalSeconds) * time.Second
	for ctx.Err() == nil {
		// A waiting forward holds no workers or result buffers until a round can start.
		select {
		case <-ctx.Done():
			return
		case roundSlots <- struct{}{}:
		}
		started := time.Now()
		m.runRound(ctx)
		<-roundSlots
		wait := max(time.Second, interval-time.Since(started))
		timer := time.NewTimer(wait)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
	}
}

func (m *Monitor) runRound(ctx context.Context) {
	type job struct {
		probeIndex       int
		destinationIndex int
	}
	samples := make([][]Sample, len(m.probes))
	var destinationCount int
	for i, probe := range m.probes {
		samples[i] = make([]Sample, len(probe.destinations))
		destinationCount += len(probe.destinations)
	}
	jobs := make(chan job)
	var workers sync.WaitGroup
	for range min(probesPerForward, destinationCount) {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for request := range jobs {
				probe := m.probes[request.probeIndex]
				destination := probe.destinations[request.destinationIndex]
				samples[request.probeIndex][request.destinationIndex] = m.measure(ctx, probe, destination.address)
			}
		}()
	}

schedule:
	for destinationIndex := range m.addresses {
		for probeIndex, probe := range m.probes {
			if destinationIndex >= len(probe.destinations) {
				continue
			}
			select {
			case <-ctx.Done():
				break schedule
			case jobs <- job{probeIndex, destinationIndex}:
			}
		}
	}
	close(jobs)
	workers.Wait()
	if ctx.Err() != nil {
		return
	}
	for i, probe := range m.probes {
		if probe.mode == googleSTUNMode {
			probe.state.Store(&roundState{snapshot: Snapshot{
				LastProbeAt: time.Now().UnixMilli(), Targets: []Sample{}, Egress: &samples[i][0],
			}})
			continue
		}
		byTarget := make([]Sample, len(m.addresses))
		for destinationIndex, destination := range probe.destinations {
			sample := samples[i][destinationIndex]
			for _, targetIndex := range destination.targetIndexes {
				sample.Target = m.addresses[targetIndex]
				byTarget[targetIndex] = sample
			}
		}
		m.publish(probe, byTarget)
	}
}

func (m *Monitor) measure(parent context.Context, probe *protocolProbe, address string) Sample {
	sample := Sample{ProbeAddress: address}
	select {
	case <-parent.Done():
		return sample
	case probeSlots <- struct{}{}:
		defer func() { <-probeSlots }()
	}
	ctx, cancel := context.WithTimeout(parent, time.Duration(m.config.TimeoutSeconds)*time.Second)
	defer cancel()
	dialUDP := func(ctx context.Context, address string) (net.Conn, error) {
		return m.dial(ctx, "udp", address)
	}
	var latency time.Duration
	var err error
	switch probe.mode {
	case "tcp":
		started := time.Now()
		var conn net.Conn
		conn, err = m.dial(ctx, "tcp", address)
		if err == nil {
			latency = time.Since(started)
			conn.Close()
		}
	case "echo":
		latency, err = stunprobe.ProbeEcho(ctx, address, dialUDP)
	case "echo_fixed":
		latency, err = stunprobe.ProbeFixedEcho(ctx, address, m.echoKey, dialUDP)
	case googleSTUNMode:
		latency, err = stunprobe.ProbeGoogle(ctx, dialUDP)
	}
	if err != nil {
		sample.Error = err.Error()
		return sample
	}
	value := float64(latency) / float64(time.Millisecond)
	sample.Available = true
	sample.LatencyMs = &value
	return sample
}

func (m *Monitor) publish(probe *protocolProbe, samples []Sample) {
	previous := probe.state.Load()
	next := &roundState{
		snapshot: Snapshot{LastProbeAt: time.Now().UnixMilli(), Targets: samples, SelectedTarget: previous.snapshot.SelectedTarget},
		eligible: make(map[string]bool),
	}
	best := -1
	current := -1
	first := -1
	for i, sample := range samples {
		if !sample.Available {
			continue
		}
		next.eligible[sample.Target] = true
		if first < 0 {
			first = i
		}
		if m.strategy == "latency" {
			if best < 0 || *sample.LatencyMs < *samples[best].LatencyMs {
				best = i
			}
			if sample.Target == next.snapshot.SelectedTarget {
				current = i
			}
		}
	}
	if first < 0 {
		next.snapshot.AllUnavailable = true
		next.eligible = previous.eligible
	} else if m.strategy == "fifo" {
		next.snapshot.SelectedTarget = samples[first].Target
	} else if m.strategy == "latency" &&
		(current < 0 || *samples[current].LatencyMs-*samples[best].LatencyMs > float64(m.config.SwitchThresholdMs)) {
		next.snapshot.SelectedTarget = samples[best].Target
	}
	probe.state.Store(next)
}
