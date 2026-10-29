package com.admin.common.utils;

import com.admin.common.dto.GostConfigDto;
import com.admin.common.dto.GostDto;
import com.admin.entity.Tunnel;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.apache.bcel.generic.RET;

import java.util.Objects;

public class GostUtil {


    public static GostDto AddLimiters(Long node_id, Long name, String speed) {
        JSONObject data = createLimiterData(name, speed);
        return WebSocketServer.send_msg(node_id, data, "AddLimiters");
    }

    public static GostDto UpdateLimiters(Long node_id, Long name, String speed) {
        JSONObject data = createLimiterData(name, speed);
        JSONObject req = new JSONObject();
        req.put("limiter", name + "");
        req.put("data", data);
        return WebSocketServer.send_msg(node_id, req, "UpdateLimiters");
    }

    public static GostDto DeleteLimiters(Long node_id, Long name) {
        JSONObject req = new JSONObject();
        req.put("limiter", name + "");
        return WebSocketServer.send_msg(node_id, req, "DeleteLimiters");
    }

    public static GostDto AddService(Long node_id, String name, Integer in_port, Integer limiter, String remoteAddr, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName) {
        JSONArray services = new JSONArray();
        String[] protocols = {"tcp", "udp"};
        for (String protocol : protocols) {
            JSONObject service = createServiceConfig(name, in_port, limiter, remoteAddr, protocol, fow_type, tunnel, strategy, interfaceName);
            services.add(service);
        }
        return WebSocketServer.send_msg(node_id, services, "AddService");
    }

    public static GostDto UpdateService(Long node_id, String name, Integer in_port, Integer limiter, String remoteAddr, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName) {
        JSONArray services = new JSONArray();
        String[] protocols = {"tcp", "udp"};
        for (String protocol : protocols) {
            JSONObject service = createServiceConfig(name, in_port, limiter, remoteAddr, protocol, fow_type, tunnel, strategy, interfaceName);
            services.add(service);
        }
        return WebSocketServer.send_msg(node_id, services, "UpdateService");
    }

    public static GostDto DeleteService(Long node_id, String name) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(name + "_tcp");
        services.add(name + "_udp");
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, "DeleteService");
    }

    public static GostDto AddRemoteService(Long node_id, String name, Integer out_port, String remoteAddr,  String protocol, String strategy, String interfaceName) {
        JSONObject data = new JSONObject();
        data.put("name", name + "_tls");
        data.put("addr", ":" + out_port);

        if (StringUtils.isNotBlank(interfaceName)) {
            JSONObject metadata = new JSONObject();
            metadata.put("interface", interfaceName);
            data.put("metadata", metadata);
        }


        JSONObject handler = new JSONObject();
        handler.put("type", "relay");
        data.put("handler", handler);
        JSONObject listener = new JSONObject();
        listener.put("type", protocol);
        data.put("listener", listener);
        JSONObject forwarder = new JSONObject();
        JSONArray nodes = new JSONArray();

        String[] split = remoteAddr.split(",");
        int num = 1;
        for (String addr : split) {
            JSONObject node = new JSONObject();
            node.put("name", "node_" + num );
            node.put("addr", addr);
            nodes.add(node);
            num ++;
        }
        if (strategy == null || strategy.equals("")){
            strategy = "fifo";
        }
        forwarder.put("nodes", nodes);
        JSONObject selector = new JSONObject();
        selector.put("strategy", strategy);
        selector.put("maxFails", 1);
        selector.put("failTimeout", "600s");
        forwarder.put("selector", selector);

        data.put("forwarder", forwarder);
        JSONArray services = new JSONArray();
        services.add(data);
        return WebSocketServer.send_msg(node_id, services, "AddService");
    }

    public static GostDto UpdateRemoteService(Long node_id, String name, Integer out_port, String remoteAddr,String protocol, String strategy, String interfaceName) {
        JSONObject data = new JSONObject();
        data.put("name", name + "_tls");
        data.put("addr", ":" + out_port);

        if (StringUtils.isNotBlank(interfaceName)) {
            JSONObject metadata = new JSONObject();
            metadata.put("interface", interfaceName);
            data.put("metadata", metadata);
        }


        JSONObject handler = new JSONObject();
        handler.put("type", "relay");
        data.put("handler", handler);
        JSONObject listener = new JSONObject();
        listener.put("type", protocol);
        data.put("listener", listener);
        JSONObject forwarder = new JSONObject();
        JSONArray nodes = new JSONArray();

        String[] split = remoteAddr.split(",");
        int num = 1;
        for (String addr : split) {
            JSONObject node = new JSONObject();
            node.put("name", "node_" + num );
            node.put("addr", addr);
            nodes.add(node);
            num ++;
        }
        if (strategy == null || strategy.equals("")){
            strategy = "fifo";
        }
        forwarder.put("nodes", nodes);
        JSONObject selector = new JSONObject();
        selector.put("strategy", strategy);
        selector.put("maxFails", 1);
        selector.put("failTimeout", "600s");
        forwarder.put("selector", selector);

        data.put("forwarder", forwarder);
        JSONArray services = new JSONArray();
        services.add(data);
        return WebSocketServer.send_msg(node_id, services, "UpdateService");
    }

    public static GostDto DeleteRemoteService(Long node_id, String name) {
        JSONArray data = new JSONArray();
        data.add(name + "_tls");
        JSONObject req = new JSONObject();
        req.put("services", data);
        return WebSocketServer.send_msg(node_id, req, "DeleteService");
    }

    public static GostDto PauseService(Long node_id, String name) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(name + "_tcp");
        services.add(name + "_udp");
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, "PauseService");
    }

    public static GostDto ResumeService(Long node_id, String name) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(name + "_tcp");
        services.add(name + "_udp");
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, "ResumeService");
    }

    public static GostDto PauseRemoteService(Long node_id, String name) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(name + "_tls");
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, "PauseService");
    }

    public static GostDto ResumeRemoteService(Long node_id, String name) {
        JSONObject data = new JSONObject();
        JSONArray services = new JSONArray();
        services.add(name + "_tls");
        data.put("services", services);
        return WebSocketServer.send_msg(node_id, data, "ResumeService");
    }

    public static GostDto AddChains(Long node_id, String name, String remoteAddr, String protocol, String interfaceName) {
        JSONObject dialer = new JSONObject();
        dialer.put("type", protocol);
        if (Objects.equals(protocol, "quic")){
            JSONObject metadata = new JSONObject();
            metadata.put("keepAlive", true);
            metadata.put("ttl", "10s");
            dialer.put("metadata", metadata);
        }




        JSONObject connector = new JSONObject();
        connector.put("type", "relay");

        JSONObject node = new JSONObject();
        node.put("name", "node-" + name);
        node.put("addr", remoteAddr);
        node.put("connector", connector);
        node.put("dialer", dialer);

        if (StringUtils.isNotBlank(interfaceName)) {
            node.put("interface", interfaceName);
        }


        JSONArray nodes = new JSONArray();
        nodes.add(node);

        JSONObject hop = new JSONObject();
        hop.put("name", "hop-" + name);
        hop.put("nodes", nodes);

        JSONArray hops = new JSONArray();
        hops.add(hop);

        JSONObject data = new JSONObject();
        data.put("name", name + "_chains");
        data.put("hops", hops);

        return WebSocketServer.send_msg(node_id, data, "AddChains");
    }

    public static GostDto UpdateChains(Long node_id, String name, String remoteAddr, String protocol, String interfaceName) {
        JSONObject dialer = new JSONObject();
        dialer.put("type", protocol);

        if (Objects.equals(protocol, "quic")){
            JSONObject metadata = new JSONObject();
            metadata.put("keepAlive", true);
            metadata.put("ttl", "10s");
            dialer.put("metadata", metadata);
        }


        JSONObject connector = new JSONObject();
        connector.put("type", "relay");

        JSONObject node = new JSONObject();
        node.put("name", "node-" + name);
        node.put("addr", remoteAddr);
        node.put("connector", connector);
        node.put("dialer", dialer);

        if (StringUtils.isNotBlank(interfaceName)) {
            node.put("interface", interfaceName);
        }

        JSONArray nodes = new JSONArray();
        nodes.add(node);

        JSONObject hop = new JSONObject();
        hop.put("name", "hop-" + name);
        hop.put("nodes", nodes);

        JSONArray hops = new JSONArray();
        hops.add(hop);

        JSONObject data = new JSONObject();
        data.put("name", name + "_chains");
        data.put("hops", hops);
        JSONObject req = new JSONObject();
        req.put("chain", name + "_chains");
        req.put("data", data);
       return WebSocketServer.send_msg(node_id, req, "UpdateChains");
    }

    public static GostDto DeleteChains(Long node_id, String name) {
        JSONObject data = new JSONObject();
        data.put("chain", name + "_chains");
        return WebSocketServer.send_msg(node_id, data, "DeleteChains");
    }

    private static JSONObject createLimiterData(Long name, String speed) {
        JSONObject data = new JSONObject();
        data.put("name", name.toString());
        JSONArray limits = new JSONArray();
        limits.add("$ " + speed + "MB " + speed + "MB");
        data.put("limits", limits);
        return data;
    }

    private static JSONObject createServiceConfig(String name, Integer in_port, Integer limiter, String remoteAddr, String protocol, Integer fow_type, Tunnel tunnel, String strategy, String interfaceName) {
        JSONObject service = new JSONObject();
        service.put("name", name + "_" + protocol);
        if (Objects.equals(protocol, "tcp")){
            service.put("addr", tunnel.getTcpListenAddr() + ":" + in_port);
        }else {
            service.put("addr", tunnel.getUdpListenAddr() + ":" + in_port);
        }

        if (StringUtils.isNotBlank(interfaceName)) {
            JSONObject metadata = new JSONObject();
            metadata.put("interface", interfaceName);
            service.put("metadata", metadata);
        }


        // 添加限流器配置
        if (limiter != null) {
            service.put("limiter", limiter.toString());
        }

        // 配置处理器
        JSONObject handler = createHandler(protocol, name, fow_type);
        service.put("handler", handler);

        // 配置监听器
        JSONObject listener = createListener(protocol);
        service.put("listener", listener);

        // 端口转发需要配置转发器
        if (isPortForwarding(fow_type)) {
            JSONObject forwarder = createForwarder(remoteAddr, strategy);
            service.put("forwarder", forwarder);
        }
        return service;
    }

    private static JSONObject createHandler(String protocol, String name, Integer fow_type) {
        JSONObject handler = new JSONObject();
        handler.put("type", protocol);

        // 隧道转发需要添加链配置
        if (isTunnelForwarding(fow_type)) {
            handler.put("chain", name + "_chains");
        }

        return handler;
    }

    private static JSONObject createListener(String protocol) {
        JSONObject listener = new JSONObject();
        listener.put("type", protocol);
        if (Objects.equals(protocol, "udp")){
            JSONObject metadata = new JSONObject();
            metadata.put("keepAlive", true);
            // A listener otherwise falls back to 8192 bytes and silently
            // truncates larger datagrams. Use a string for older agent parsers.
            metadata.put("readBufferSize", "65535");
            listener.put("metadata", metadata);
        }
        return listener;
    }

    private static JSONObject createForwarder(String remoteAddr, String strategy) {
        JSONObject forwarder = new JSONObject();
        JSONArray nodes = new JSONArray();

        String[] split = remoteAddr.split(",");
        int num = 1;
        for (String addr : split) {
            JSONObject node = new JSONObject();
            node.put("name", "node_" + num );
            node.put("addr", addr);
            nodes.add(node);
            num ++;
        }

        if (strategy == null || strategy.equals("")){
            strategy = "fifo";
        }

        forwarder.put("nodes", nodes);

        JSONObject selector = new JSONObject();
        selector.put("strategy", strategy);
        selector.put("maxFails", 1);
        selector.put("failTimeout", "600s");
        forwarder.put("selector", selector);
        return forwarder;
    }

    private static boolean isPortForwarding(Integer fow_type) {
        return fow_type != null && fow_type == 1;
    }

    private static boolean isTunnelForwarding(Integer fow_type) {
        return fow_type != null && fow_type != 1;
    }


    public static GostDto AddRouteChain(Long nodeId, String name, JSONArray hops, String protocol) {
        JSONObject data = new JSONObject();
        data.put("name", name);
        data.put("hops", hops);
        return WebSocketServer.send_msg(nodeId, data, "AddChains");
    }

    public static GostDto AddChainEndpoint(Long nodeId, String name, Integer port, String protocol, String autherName) {
        JSONObject service = new JSONObject();
        service.put("name", name);
        service.put("addr", ":" + port);
        JSONObject handler = new JSONObject();
        handler.put("type", "relay");
        handler.put("auther", autherName);
        handler.put("metadata", chainRelayConnectorMetadata());
        service.put("handler", handler);
        JSONObject listener = new JSONObject();
        listener.put("type", protocol);
        service.put("listener", listener);
        return WebSocketServer.send_msg(nodeId, new JSONArray(java.util.Collections.singletonList(service)), "AddService");
    }

    /**
     * Add a per-hop relay endpoint. Each endpoint terminates one QUIC link and
     * forwards the TCP/UDP request through its own next-hop chain. A null
     * chainName means the endpoint forwards directly to the final target.
     */
    public static GostDto AddChainStageEndpoint(Long nodeId, String name, Integer port, String protocol,
                                                String autherName, String targetHost, Integer targetPort,
                                                String chainName) {
        JSONObject service = new JSONObject();
        service.put("name", name);
        service.put("addr", ":" + port);

        JSONObject handler = new JSONObject();
        handler.put("type", "relay");
        handler.put("auther", autherName);
        handler.put("metadata", chainRelayConnectorMetadata());
        if (StringUtils.isNotBlank(chainName)) handler.put("chain", chainName);
        service.put("handler", handler);

        JSONObject listener = new JSONObject();
        listener.put("type", protocol);
        service.put("listener", listener);

        JSONObject target = new JSONObject();
        target.put("addr", targetHost != null && targetHost.contains(":") && !targetHost.startsWith("[")
                ? "[" + targetHost + "]:" + targetPort
                : targetHost + ":" + targetPort);
        JSONObject forwarder = new JSONObject();
        forwarder.put("nodes", new JSONArray(java.util.Collections.singletonList(target)));
        service.put("forwarder", forwarder);

        return WebSocketServer.send_msg(nodeId, new JSONArray(java.util.Collections.singletonList(service)), "AddService");
    }

    public static GostDto DeleteNamedService(Long nodeId, String serviceName) {
        JSONObject data = new JSONObject();
        JSONArray names = new JSONArray();
        names.add(serviceName);
        data.put("services", names);
        return WebSocketServer.send_msg(nodeId, data, "DeleteService");
    }

    public static GostDto AddChainForwardServices(Long nodeId, String serviceName, Integer port, String host, Integer targetPort, String chainName, boolean update) {
        return WebSocketServer.send_msg(nodeId,
                buildChainForwardServices(serviceName, port, host, targetPort, chainName, null, null),
                update ? "UpdateService" : "AddService");
    }

    public static GostDto AddSplitChainForwardServices(Long nodeId, String serviceName, Integer port, String host,
            Integer targetPort, String tcpChainName, String udpHost, Integer udpPort, boolean update) {
        return WebSocketServer.send_msg(nodeId,
                buildChainForwardServices(serviceName, port, host, targetPort, tcpChainName, udpHost, udpPort),
                update ? "UpdateService" : "AddService");
    }

    public static GostDto AddPlainUdpStage(Long nodeId, String serviceName, Integer port, String host, Integer targetPort) {
        return WebSocketServer.send_msg(nodeId,
                new JSONArray(java.util.Collections.singletonList(plainUdpStageConfig(serviceName, port, host, targetPort))), "AddService");
    }

    static JSONObject plainUdpStageConfig(String serviceName, Integer port, String host, Integer targetPort) {
        JSONObject service = new JSONObject();
        service.put("name", serviceName);
        service.put("addr", ":" + port);
        JSONObject handler = new JSONObject();
        handler.put("type", "udp");
        service.put("handler", handler);
        JSONObject listener = new JSONObject();
        listener.put("type", "udp");
        listener.put("metadata", chainUdpListenerMetadata());
        service.put("listener", listener);
        JSONObject target = new JSONObject();
        target.put("addr", formatChainTarget(host, targetPort));
        JSONObject forwarder = new JSONObject();
        forwarder.put("nodes", new JSONArray(java.util.Collections.singletonList(target)));
        service.put("forwarder", forwarder);
        return service;
    }

    private static JSONObject chainUdpListenerMetadata() {
        JSONObject metadata = new JSONObject();
        metadata.put("keepalive", true);
        metadata.put("ttl", "60s");
        // Preserve a complete UDP datagram at ingress and every intermediate hop.
        // Older agents decode JSON numbers as float64, while GetInt accepts
        // only int/string. A string works for both old and current node builds.
        metadata.put("readBufferSize", "65535");
        return metadata;
    }

    public static JSONObject chainRelayConnectorMetadata() {
        JSONObject metadata = new JSONObject();
        // Exchange the relay request/response immediately, even when a TCP
        // probe sends no application bytes. Every stage confirms the next one.
        metadata.put("nodelay", true);
        metadata.put("connectTimeout", "5s");
        return metadata;
    }

    private static String formatChainTarget(String host, Integer port) {
        return host != null && host.contains(":") && !host.startsWith("[")
                ? "[" + host + "]:" + port : host + ":" + port;
    }

    static JSONArray buildChainForwardServices(String serviceName, Integer port, String host, Integer targetPort,
            String chainName, String udpHost, Integer udpPort) {
        JSONArray services = new JSONArray();
        for (String protocol : new String[]{"tcp", "udp"}) {
            boolean directUdp = "udp".equals(protocol) && udpHost != null;
            JSONObject service = new JSONObject();
            service.put("name", serviceName + "_" + protocol);
            service.put("addr", ":" + port);
            JSONObject handler = new JSONObject();
            handler.put("type", protocol);
            if (!directUdp) handler.put("chain", chainName);
            service.put("handler", handler);
            JSONObject forwarder = new JSONObject();
            JSONArray nodes = new JSONArray();
            JSONObject target = new JSONObject();
            target.put("addr", formatChainTarget(directUdp ? udpHost : host, directUdp ? udpPort : targetPort));
            nodes.add(target);
            forwarder.put("nodes", nodes);
            service.put("forwarder", forwarder);
            JSONObject listener = new JSONObject();
            listener.put("type", protocol);
            if ("udp".equals(protocol)) {
                listener.put("metadata", chainUdpListenerMetadata());
            }
            service.put("listener", listener);
            services.add(service);
        }
        return services;
    }

    public static GostDto TcpPingThroughChain(Long nodeId, String chainName, String host, Integer port, Integer timeoutMs) {
        JSONObject data = new JSONObject();
        data.put("ip", host);
        data.put("port", port);
        data.put("count", 1);
        data.put("timeout", timeoutMs);
        data.put("chain", chainName);
        return WebSocketServer.send_msg(nodeId, data, "TcpPing");
    }

    public static GostDto DeleteRouteChain(Long nodeId, String name) {
        JSONObject data = new JSONObject();
        data.put("chain", name);
        return WebSocketServer.send_msg(nodeId, data, "DeleteChains");
    }

    public static GostDto AddAuther(Long nodeId, String name, String username, String password) {
        JSONObject data = new JSONObject();
        data.put("name", name);
        JSONArray auths = new JSONArray();
        JSONObject auth = new JSONObject(); auth.put("username", username); auth.put("password", password); auths.add(auth);
        data.put("auths", auths);
        return WebSocketServer.send_msg(nodeId, data, "AddAuthers");
    }

    public static GostDto DeleteAuther(Long nodeId, String name) {
        JSONObject data = new JSONObject(); data.put("auther", name);
        return WebSocketServer.send_msg(nodeId, data, "DeleteAuthers");
    }
}
