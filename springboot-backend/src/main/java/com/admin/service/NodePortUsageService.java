package com.admin.service;

import com.admin.entity.Forward;
import com.admin.entity.ForwardChain;
import com.admin.entity.Tunnel;
import com.admin.mapper.ForwardMapper;
import com.admin.mapper.ForwardChainMapper;
import com.admin.mapper.TunnelMapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;

/** Shared listener port reservations for ordinary forwards and forwarding chains. */
@Service
public class NodePortUsageService {
    @Resource private ForwardMapper forwardMapper;
    @Resource private ForwardChainMapper forwardChainMapper;
    @Resource private TunnelMapper tunnelMapper;

    public Map<Long, Set<Integer>> collectUsedPorts(Long excludeForwardId, Long excludeChainId) {
        Map<Long, Set<Integer>> used = new HashMap<>();
        Map<Long, Tunnel> tunnels = new HashMap<>();
        for (Tunnel tunnel : tunnelMapper.selectList(new QueryWrapper<Tunnel>())) tunnels.put(tunnel.getId(), tunnel);
        for (Forward forward : forwardMapper.selectList(new QueryWrapper<Forward>())) {
            if (excludeForwardId != null && Objects.equals(forward.getId(), excludeForwardId)) continue;
            if (forward.getTunnelId() == null) continue;
            Tunnel tunnel = tunnels.get(forward.getTunnelId().longValue());
            if (tunnel == null) continue;
            reserve(used, tunnel.getInNodeId(), forward.getInPort());
            reserve(used, tunnel.getOutNodeId(), forward.getOutPort());
        }
        // Paused configurations also retain their ports.
        for (ForwardChain chain : forwardChainMapper.selectList(new QueryWrapper<ForwardChain>())) {
            if (excludeChainId != null && Objects.equals(chain.getId(), excludeChainId)) continue;
            reserve(used, chain.getInNodeId(), chain.getInPort());
            collectChainEndpointPorts(chain).forEach((nodeId, ports) -> used.computeIfAbsent(nodeId, k -> new HashSet<>()).addAll(ports));
        }
        return used;
    }

    public Map<Long, Set<Integer>> collectChainEndpointPorts(ForwardChain chain) {
        Map<Long, Set<Integer>> ports = new HashMap<>();
        JSONObject config = JSON.parseObject(chain.getEndpointPortsJson() == null ? "{}" : chain.getEndpointPortsJson());
        if (Objects.equals(config.getInteger("format"), 2)) {
            JSONArray endpoints = config.getJSONArray("endpoints");
            if (endpoints != null) for (int i = 0; i < endpoints.size(); i++) {
                JSONObject endpoint = endpoints.getJSONObject(i);
                reserve(ports, endpoint.getLong("nodeId"), endpoint.getInteger("port"));
            }
        } else {
            for (String nodeKey : config.keySet()) {
                if ("retiredPorts".equals(nodeKey)) continue;
                reserve(ports, Long.valueOf(nodeKey), config.getInteger(nodeKey));
            }
        }
        JSONObject retired = config.getJSONObject("retiredPorts");
        long now = System.currentTimeMillis();
        if (retired != null) for (String nodeKey : retired.keySet()) {
            JSONArray entries = retired.getJSONArray(nodeKey);
            if (entries == null) continue;
            for (int i = 0; i < entries.size(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                Long expiresAt = entry.getLong("expiresAt");
                if (expiresAt != null && expiresAt > now) reserve(ports, Long.valueOf(nodeKey), entry.getInteger("port"));
            }
        }
        return ports;
    }

    private void reserve(Map<Long, Set<Integer>> ports, Long nodeId, Integer port) {
        if (nodeId != null && port != null) ports.computeIfAbsent(nodeId, k -> new HashSet<>()).add(port);
    }
}
