package socket

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"time"

	"github.com/go-gost/core/chain"
	xchain "github.com/go-gost/x/chain"
	"github.com/go-gost/x/internal/util/stunprobe"
	"github.com/go-gost/x/registry"
)

func prepareForwardUdpStun(data interface{}) (interface{}, error) {
	var request struct {
		Service  string `json:"service"`
		LinkOnly bool   `json:"linkOnly"`
	}
	payload, err := json.Marshal(data)
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(payload, &request); err != nil {
		return nil, err
	}
	if request.Service == "" || registry.ServiceRegistry().Get(request.Service) == nil {
		return nil, errors.New("出口节点未运行该转发的隧道服务")
	}
	token, err := stunprobe.PrepareTunnelProbe(request.Service, request.LinkOnly)
	if err != nil {
		return nil, err
	}
	return map[string]string{"token": token}, nil
}

func (w *WebSocketReporter) handleForwardUdpStun(data interface{}) (interface{}, error) {
	var request struct {
		Chain     string `json:"chain"`
		Token     string `json:"token"`
		Interface string `json:"interface"`
		Timeout   int    `json:"timeout"`
		LinkOnly  bool   `json:"linkOnly"`
	}
	payload, err := json.Marshal(data)
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(payload, &request); err != nil {
		return nil, err
	}
	if request.Timeout < 1000 || request.Timeout > int(stunprobe.MaxTimeout/time.Millisecond) {
		return nil, errors.New("UDP STUN 探测超时必须在 1 至 5 秒之间")
	}
	ctx, cancel := context.WithTimeout(w.ctx, time.Duration(request.Timeout)*time.Millisecond)
	defer cancel()

	if request.Chain == "" {
		router := xchain.NewRouter(chain.InterfaceRouterOption(request.Interface))
		rtt, err := stunprobe.ProbeGoogle(ctx, func(ctx context.Context, address string) (net.Conn, error) {
			return router.Dial(ctx, "udp", address)
		})
		if err != nil {
			if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
				return nil, errors.New("未收到 Google STUN 应答（探测超时）")
			}
			return nil, fmt.Errorf("UDP STUN 探测失败：%w", err)
		}
		return map[string]float64{"latencyMs": durationMilliseconds(rtt)}, nil
	}

	ch := registry.ChainRegistry().Get(request.Chain)
	if ch == nil || request.Token == "" {
		return nil, errors.New("入口节点未运行该转发的隧道链路或缺少探测凭据")
	}
	ctx = stunprobe.WithTunnelProbe(ctx, request.Token)
	route := ch.Route(ctx, "udp", stunprobe.GoogleAddress)
	if route == nil || len(route.Nodes()) != 1 {
		return nil, errors.New("入口节点未找到该转发的单跳出口路由")
	}
	conn, err := dialProbeContext(ctx, func(ctx context.Context) (net.Conn, error) {
		return route.Dial(ctx, "udp", stunprobe.GoogleAddress)
	})
	if err != nil {
		return nil, fmt.Errorf("隧道 UDP 探测连接失败：%w", err)
	}
	defer conn.Close()
	timings, err := stunprobe.MeasureTunnel(ctx, conn, request.LinkOnly)
	if timings.LinkRTT == nil {
		return nil, fmt.Errorf("未收到出口节点的 UDP 回显：%w", err)
	}
	result := map[string]interface{}{"linkLatencyMs": durationMilliseconds(*timings.LinkRTT)}
	if err != nil {
		result["errorMessage"] = "出口未返回有效的 Google STUN 回包"
	} else if timings.STUNRTT != nil {
		result["latencyMs"] = durationMilliseconds(*timings.STUNRTT)
	}
	return result, nil
}

func durationMilliseconds(duration time.Duration) float64 {
	return float64(duration) / float64(time.Millisecond)
}
