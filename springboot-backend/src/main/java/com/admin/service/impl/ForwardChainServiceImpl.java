package com.admin.service.impl;

import com.admin.common.dto.ForwardChainDto;
import com.admin.common.dto.ForwardChainProbeStatus;
import com.admin.common.dto.ForwardChainProbeStatus.State;
import com.admin.common.annotation.SerializeNodePorts;
import com.admin.common.dto.GostDto;
import com.admin.common.lang.R;
import com.admin.common.utils.GostUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.entity.ForwardChain;
import com.admin.entity.Node;
import com.admin.mapper.ForwardChainMapper;
import com.admin.service.ForwardChainService;
import com.admin.service.ForwardChainProbeExecutor;
import com.admin.service.NodePortUsageService;
import com.admin.service.NodeService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Slf4j
@Service
public class ForwardChainServiceImpl extends ServiceImpl<ForwardChainMapper, ForwardChain> implements ForwardChainService {
    private static final int DEFAULT_PROBE_INTERVAL_MS = 300000;
    private static final int DEFAULT_PROBE_TIMEOUT_MS = 3000;
    private static final int DEFAULT_SWITCH_THRESHOLD_MS = 1000;
    private static final long RETIRED_ENDPOINT_PORT_GRACE_MS = 60000L;
    private static final String SERVICE_PREFIX = "fc_";
    private static final String UDP_STUN = "udp_stun";
    private static final String UDP_ECHO = "udp_echo";
    private static final int UDP_ECHO_PORT = 19302;
    @Resource private NodeService nodeService;
    @Resource private NodePortUsageService portUsageService;
    @Resource private ForwardChainProbeExecutor probeExecutor;

    @Override
    public R listChains(Long currentAdminId) {
        // A terminal probe status is published only after the corresponding database update.
        Map<Long, ForwardChainProbeStatus> probeStatuses = probeExecutor.manualStatusSnapshot();
        List<ForwardChain> chains = baseMapper.selectDisplayList();
        if (chains.stream().anyMatch(chain -> chain.getUserId() == null)) {
            // Older rows did not record a creator. Assign them once to the authenticated administrator.
            update(new LambdaUpdateWrapper<ForwardChain>().isNull(ForwardChain::getUserId)
                    .set(ForwardChain::getUserId, currentAdminId));
            chains = baseMapper.selectDisplayList();
        }
        chains.forEach(chain -> chain.setManualProbe(probeStatuses.get(chain.getId())));
        return R.ok(chains);
    }

    @Override
    public R requestProbe(Long id) {
        if (id == null) return R.err("缺少转发链ID");
        ForwardChain chain = getOne(new LambdaQueryWrapper<ForwardChain>()
                .select(ForwardChain::getStatus).eq(ForwardChain::getId, id));
        if (chain == null) return R.err("转发链不存在");
        if (!Objects.equals(chain.getStatus(), 1)) return R.err("转发链已暂停，请启用后再探测");
        ForwardChainProbeStatus request = probeExecutor.submitManual(id, () -> runProbeCycle(id));
        return request.state() == State.BUSY ? R.err(request.message()) : R.ok(request);
    }

    @Override
    @Transactional
    public R updateOrder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return R.err("缺少转发链排序数据");
        Set<Long> requestedIds = new HashSet<>(ids);
        if (requestedIds.contains(null) || requestedIds.size() != ids.size()) {
            return R.err("转发链排序数据无效");
        }
        List<Long> currentIds = baseMapper.lockChainIds();
        if (currentIds.size() != ids.size() || !requestedIds.containsAll(currentIds)) {
            return R.err("转发链列表已变更，请刷新后重试");
        }
        baseMapper.updateDisplayOrder(ids);
        return R.ok();
    }

    @Override
    @Transactional
    @SerializeNodePorts
    public synchronized R createChain(ForwardChainDto dto, Long userId) {
        String error = validate(dto, null);
        if (error != null) return R.err(error);
        ForwardChain chain = new ForwardChain();
        chain.setUserId(userId);
        copySettings(dto, chain);
        chain.setPathsJson("[]");
        chain.setEndpointPortsJson("{}");
        chain.setSelectedPathIndex(0);
        chain.setLastProbeTime(0L);
        chain.setInFlow(0L);
        chain.setOutFlow(0L);
        chain.setStatus(1);
        long now = System.currentTimeMillis();
        chain.setCreatedTime(now);
        chain.setUpdatedTime(now);
        if (!save(chain)) return R.err("保存转发链失败");
        R deployed = deploy(chain);
        if (deployed.getCode() != 0) {
            teardown(chain);
            removeById(chain.getId());
            return deployed;
        }
        return R.ok(getById(chain.getId()));
    }

    @Override
    @Transactional
    @SerializeNodePorts
    public synchronized R updateChain(ForwardChainDto dto) {
        if (dto.getId() == null) return R.err("缺少转发链ID");
        ForwardChain existing = getById(dto.getId());
        if (existing == null) return R.err("转发链不存在");
        String error = validate(dto, existing.getId());
        if (error != null) return R.err(error);
        ForwardChain old = JSON.parseObject(JSON.toJSONString(existing), ForwardChain.class);
        Map<Long, Map<Integer, Long>> retiredPorts = readRetiredPorts(old);
        Map<Long, Set<Integer>> oldEndpointPorts = readEndpointPorts(old);
        retirePorts(retiredPorts, oldEndpointPorts, System.currentTimeMillis() + RETIRED_ENDPOINT_PORT_GRACE_MS);
        teardown(existing);
        copySettings(dto, existing);
        boolean keepPath = (usesUdpProbe(old) || usesUdpProbe(existing)) && sameTopology(old, existing);
        existing.setPathsJson("[]");
        existing.setEndpointPortsJson("{}");
        existing.setSelectedPathIndex(keepPath ? old.getSelectedPathIndex() : Integer.valueOf(0));
        existing.setLastProbeTime(0L);
        existing.setUpdatedTime(System.currentTimeMillis());
        updateById(existing);
        // GOST can take a short time to release a listener after DeleteService.
        // Avoid rebinding the just-removed ports while deploying the replacement.
        R deployed = deploy(existing, activeRetiredPorts(retiredPorts));
        if (deployed.getCode() != 0) {
            Map<Long, Set<Integer>> failedAttemptPorts = readEndpointPorts(existing);
            retirePorts(retiredPorts, failedAttemptPorts, System.currentTimeMillis() + RETIRED_ENDPOINT_PORT_GRACE_MS);
            teardown(existing);
            updateById(old);
            R restored = deploy(old, activeRetiredPorts(retiredPorts));
            if (restored.getCode() == 0) {
                persistRetiredPorts(old, retiredPorts);
                updateById(old);
            }
            if (restored.getCode() != 0) log.error("恢复转发链 {} 旧配置失败: {}", old.getId(), restored.getMsg());
            return R.err("新配置下发失败，已尝试恢复旧配置：" + deployed.getMsg());
        }
        persistRetiredPorts(existing, retiredPorts);
        updateById(existing);
        return R.ok(getById(existing.getId()));
    }

    @Override
    @Transactional
    @SerializeNodePorts
    public synchronized R deleteChain(Long id) {
        ForwardChain chain = getById(id);
        if (chain == null) return R.err("转发链不存在");
        teardown(chain);
        if (!removeById(id)) return R.err("删除转发链失败");
        probeExecutor.forgetChain(id);
        return R.ok();
    }

    @Override
    public synchronized R setStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) return R.err("状态值无效");
        ForwardChain chain = getById(id);
        if (chain == null) return R.err("转发链不存在");
        Node entry = nodeService.getById(chain.getInNodeId());
        if (entry == null) return R.err("入口节点不存在");
        GostDto result = status == 1
                ? GostUtil.ResumeService(entry.getId(), SERVICE_PREFIX + id)
                : GostUtil.PauseService(entry.getId(), SERVICE_PREFIX + id);
        if (!success(result)) return R.err(result == null ? "节点无响应" : result.getMsg());
        chain.setStatus(status);
        chain.setUpdatedTime(System.currentTimeMillis());
        updateById(chain);
        if (status == 0) probeExecutor.cancelQueued(id);
        return R.ok(chain);
    }

    private String validate(ForwardChainDto dto, Long excludeId) {
        if (dto.getInNodeId() == null || dto.getTargetPort() == null) return "入口节点和目标端口必填";
        if (dto.getHops() == null || dto.getHops().isEmpty()) return "至少配置一个跳点";
        if (dto.getProbeIntervalMs() == null) dto.setProbeIntervalMs(DEFAULT_PROBE_INTERVAL_MS);
        if (dto.getProbeTimeoutMs() == null) dto.setProbeTimeoutMs(DEFAULT_PROBE_TIMEOUT_MS);
        if (dto.getSwitchThresholdMs() == null) dto.setSwitchThresholdMs(DEFAULT_SWITCH_THRESHOLD_MS);
        if (dto.getProbeIntervalMs() < ForwardChainDto.MIN_PROBE_INTERVAL_MS || dto.getProbeIntervalMs() > 3600000) return "探测间隔必须在5秒到60分钟之间";
        if (dto.getProbeTimeoutMs() < 1000 || dto.getProbeTimeoutMs() > 5000) return "探测超时必须在1秒到5秒之间";
        if (dto.getSwitchThresholdMs() < 1 || dto.getSwitchThresholdMs() > dto.getProbeTimeoutMs()) return "切换阈值必须在1毫秒到探测超时时间之间";
        if (!Arrays.asList("tls", "quic", "tcp", "udp").contains(dto.getProtocol())) return "节点链路协议仅支持TLS、QUIC、TCP或UDP";
        ForwardChain existing = excludeId == null ? null : getById(excludeId);
        if (dto.getProbeMode() == null) {
            dto.setProbeMode("udp".equals(dto.getProtocol()) && existing != null && usesUdpProbe(existing)
                    ? existing.getProbeMode() : "tcp");
        }
        String probeMode = dto.getProbeMode();
        boolean udpProbe = UDP_STUN.equals(probeMode) || UDP_ECHO.equals(probeMode);
        boolean checkPortCapacity = udpProbe || (existing != null && usesUdpProbe(existing));
        if (!udpProbe && !"tcp".equals(probeMode)) return "探测方式仅支持 TCP 建连、UDP STUN 或 UDP 回显";
        if (udpProbe && !"udp".equals(dto.getProtocol())) return "UDP 探测需要使用原生 UDP 节点链路";
        Node entry = nodeService.getById(dto.getInNodeId());
        if (!usableNode(entry)) return "入口节点不存在或未连接";
        if (udpProbe && !supportsUdpProbe(entry, probeMode)) return udpProbeUpgradeMessage(entry, probeMode);
        Map<Long, Set<Integer>> usedPorts = new HashMap<>();
        mergePorts(usedPorts, portUsageService.collectUsedPorts(null, excludeId));
        // An update may move the entry onto a former hop; its listeners are still active until teardown.
        if (existing != null) mergePorts(usedPorts, readEndpointPorts(existing));
        Set<Integer> usedEntryPorts = usedPorts.getOrDefault(dto.getInNodeId(), Collections.emptySet());
        if (dto.getInPort() == null) {
            // Preserve an unchanged entry on edit; new entries take the first free port in the node's range.
            if (existing != null && Objects.equals(existing.getInNodeId(), dto.getInNodeId()) && !usedEntryPorts.contains(existing.getInPort())) {
                dto.setInPort(existing.getInPort());
            } else {
                for (int port = entry.getPortSta(); port <= entry.getPortEnd(); port++) {
                    if (!usedEntryPorts.contains(port)) { dto.setInPort(port); break; }
                }
            }
            if (dto.getInPort() == null) return "入口节点可用端口已用尽，无法自动分配";
        }
        if (dto.getInPort() < entry.getPortSta() || dto.getInPort() > entry.getPortEnd()) return "入口监听端口不在节点可用端口范围内";
        if (usedEntryPorts.contains(dto.getInPort())) return "该节点上的入口端口已被转发服务或转发链占用，请选择其他端口";
        Set<Long> seenAcrossHops = new HashSet<>();
        Map<Long, Node> candidates = new HashMap<>();
        for (ForwardChainDto.Hop hop : dto.getHops()) {
            if (hop == null || hop.getNodeIds() == null || hop.getNodeIds().isEmpty()) return "每一跳至少选择一个候选节点";
            Set<Long> seenInHop = new HashSet<>();
            for (Long nodeId : hop.getNodeIds()) {
                if (nodeId == null || !seenInHop.add(nodeId)) return "同一跳中不能重复选择节点";
                if (Objects.equals(nodeId, dto.getInNodeId())) return "候选跳点不能与入口节点相同";
                if (!seenAcrossHops.add(nodeId)) return "同一节点不能在不同跳中重复使用：" + nodeId;
                Node candidate = nodeService.getById(nodeId);
                if (!usableNode(candidate)) return "存在不存在或未连接的候选节点：" + nodeId;
                if (udpProbe && !supportsUdpProbe(candidate, probeMode)) return udpProbeUpgradeMessage(candidate, probeMode);
                if (checkPortCapacity) candidates.put(nodeId, candidate);
            }
        }
        return checkPortCapacity ? validatePortCapacity(dto, candidates, usedPorts, udpProbe) : null;
    }

    private String validatePortCapacity(ForwardChainDto dto, Map<Long, Node> candidates,
                                        Map<Long, Set<Integer>> usedPorts, boolean udpProbe) {
        // Segmented chains reserve one business port per distinct suffix, plus a UDP probe port when needed.
        boolean segmented = "udp".equals(dto.getProtocol()) || "quic".equals(dto.getProtocol());
        int portsPerNode = udpProbe ? 2 : 1;
        List<ForwardChainDto.Hop> hops = dto.getHops();
        for (int i = hops.size() - 1; i >= 0; i--) {
            List<Long> nodeIds = hops.get(i).getNodeIds();
            for (Long id : nodeIds) {
                Node node = candidates.get(id);
                int start = node.getPortSta();
                int end = node.getPortEnd();
                int free = end - start + 1;
                for (int used : usedPorts.getOrDefault(id, Collections.emptySet())) {
                    if (used >= start && used <= end) free--;
                }
                if (free < portsPerNode) {
                    return "节点 " + node.getName() + " 可用端口不足，无法" + (udpProbe ? "部署 UDP 探测" : "重新部署转发链");
                }
            }
            if (segmented) portsPerNode = (int) Math.min(65536, (long) portsPerNode * nodeIds.size());
        }
        return null;
    }

    private boolean usableNode(Node node) {
        return node != null && node.getStatus() != null && node.getStatus() == 1 && node.getServerIp() != null && !node.getServerIp().trim().isEmpty();
    }

    private boolean supportsUdpProbe(Node node, String probeMode) {
        return UDP_ECHO.equals(probeMode) ? WebSocketServer.supportsUdpEchoProbe(node.getId()) :
                WebSocketServer.supportsUdpStunProbe(node.getId());
    }

    private String udpProbeUpgradeMessage(Node node, String probeMode) {
        String label = UDP_ECHO.equals(probeMode) ? "UDP 回显" : "UDP STUN";
        return "节点 " + node.getName() + " 尚不支持 " + label + " 探测，请更新节点程序";
    }

    private void copySettings(ForwardChainDto dto, ForwardChain chain) {
        chain.setName(dto.getName().trim());
        chain.setInNodeId(dto.getInNodeId());
        chain.setInPort(dto.getInPort());
        chain.setTargetHost(dto.getTargetHost().trim());
        chain.setTargetPort(dto.getTargetPort());
        chain.setProtocol(dto.getProtocol() == null ? "tls" : dto.getProtocol());
        chain.setProbeMode(dto.getProbeMode());
        chain.setHopsJson(JSON.toJSONString(dto.getHops()));
        chain.setProbeIntervalMs(dto.getProbeIntervalMs());
        chain.setProbeTimeoutMs(dto.getProbeTimeoutMs());
        chain.setSwitchThresholdMs(dto.getSwitchThresholdMs());
    }

    private R deploy(ForwardChain chain) {
        return deploy(chain, Collections.emptyMap());
    }

    private R deploy(ForwardChain chain, Map<Long, Set<Integer>> portsToAvoid) {
        try {
            List<ForwardChainDto.Hop> hops = JSON.parseArray(chain.getHopsJson(), ForwardChainDto.Hop.class);
            if ("quic".equals(chain.getProtocol()) || "udp".equals(chain.getProtocol())) return deploySegmentedChain(chain, hops, portsToAvoid);
            Map<String, Integer> endpointPorts = allocateEndpointPorts(chain, hops, portsToAvoid);
            if (endpointPorts == null) return R.err("候选节点端口范围已用尽");
            chain.setEndpointPortsJson(JSON.toJSONString(endpointPorts));
            List<Node> provisionedNodes = new ArrayList<>();
            for (String nodeKey : endpointPorts.keySet()) {
                Node node = nodeService.getById(Long.valueOf(nodeKey));
                String endpointName = endpointName(chain.getId(), node.getId());
                String autherName = autherName(chain.getId(), node.getId());
                String username = authUsername(chain.getId(), node.getId());
                String password = authPassword(chain.getId(), node);
                GostDto authResult = GostUtil.AddAuther(node.getId(), autherName, username, password);
                if (!success(authResult)) {
                    for (Node done : provisionedNodes) { GostUtil.DeleteNamedService(done.getId(), endpointName(chain.getId(), done.getId())); GostUtil.DeleteAuther(done.getId(), autherName(chain.getId(), done.getId())); }
                    return R.err("候选节点认证配置失败：" + node.getName() + "，" + authResult.getMsg());
                }
                GostDto result = GostUtil.AddChainEndpoint(node.getId(), endpointName, endpointPorts.get(nodeKey), chain.getProtocol(), autherName);
                if (!success(result)) {
                    GostUtil.DeleteAuther(node.getId(), autherName);
                    for (Node done : provisionedNodes) { GostUtil.DeleteNamedService(done.getId(), endpointName(chain.getId(), done.getId())); GostUtil.DeleteAuther(done.getId(), autherName(chain.getId(), done.getId())); }
                    return R.err("候选节点转发端点配置失败：" + node.getName() + "，" + result.getMsg());
                }
                provisionedNodes.add(node);
            }
            JSONArray paths = buildPaths(chain, hops, endpointPorts);
            chain.setPathsJson(paths.toJSONString());
            chain.setSelectedPathIndex(validPathIndex(chain, paths));
            updateById(chain);
            Node entry = nodeService.getById(chain.getInNodeId());
            for (int i = 0; i < paths.size(); i++) {
                JSONObject path = paths.getJSONObject(i);
                JSONArray pathHops = path.getJSONArray("hops");
                GostDto result = GostUtil.AddRouteChain(entry.getId(), path.getString("name"), buildGostHops(pathHops, chain.getProtocol(), chain.getId()), chain.getProtocol());
                if (!success(result)) return R.err("创建完整路径失败：" + result.getMsg());
            }
            GostDto serviceResult = configureIngress(chain, paths.getJSONObject(chain.getSelectedPathIndex()), false);
            if (!success(serviceResult)) return R.err("入口TCP/UDP转发服务创建失败：" + serviceResult.getMsg());
            if (Objects.equals(chain.getStatus(), 0)) {
                GostDto paused = GostUtil.PauseService(entry.getId(), SERVICE_PREFIX + chain.getId());
                if (!success(paused)) return R.err("暂停已禁用转发链失败：" + paused.getMsg());
            }
            chain.setLastProbeTime(0L);
            updateById(chain);
            return R.ok();
        } catch (Exception e) {
            log.error("部署转发链 {} 失败", chain.getId(), e);
            return R.err("部署转发链失败：" + e.getMessage());
        }
    }

    /**
     * GOST only supports UDP based data channels (including QUIC) at the first
     * node of a single forwarding chain. To provide native QUIC on every
     * multi-hop segment, terminate one QUIC relay service at every hop and let
     * that service open a fresh one-hop QUIC chain to the next hop.
     * UDP mode shares this suffix topology, with raw datagram forwarders and
     * companion TCP relay chains for TCP traffic and the existing TCP probes.
     */
    private R deploySegmentedChain(ForwardChain chain, List<ForwardChainDto.Hop> hops, Map<Long, Set<Integer>> portsToAvoid) {
        boolean splitUdp = "udp".equals(chain.getProtocol());
        boolean udpProbe = usesUdpProbe(chain);
        String linkProtocol = splitUdp ? "tcp" : "quic";
        String linkLabel = splitUdp ? "TCP配套" : "QUIC";
        List<List<Long>> combinations = new ArrayList<>();
        expand(hops, 0, new ArrayList<>(), combinations);
        Map<Long, Set<Integer>> usedPorts = collectUsedPorts(chain);
        mergePorts(usedPorts, portsToAvoid);
        LinkedHashMap<String, JSONObject> endpoints = new LinkedHashMap<>();
        JSONArray paths = new JSONArray();

        for (int pathIndex = 0; pathIndex < combinations.size(); pathIndex++) {
            List<Long> combination = combinations.get(pathIndex);
            JSONArray pathHops = new JSONArray();
            for (int hopIndex = 0; hopIndex < combination.size(); hopIndex++) {
                List<Long> suffix = combination.subList(hopIndex, combination.size());
                String suffixKey = suffix.stream().map(String::valueOf).collect(Collectors.joining("_"));
                JSONObject endpoint = endpoints.get(suffixKey);
                if (endpoint == null) {
                    Long nodeId = combination.get(hopIndex);
                    Node node = nodeService.getById(nodeId);
                    Set<Integer> used = usedPorts.computeIfAbsent(nodeId, key -> new HashSet<>());
                    Integer port = allocateStagePort(node, used);
                    if (port == null) return R.err("候选节点端口范围已用尽");
                    String hash = sha256Hex(suffixKey);
                    endpoint = new JSONObject();
                    endpoint.put("suffixKey", suffixKey);
                    endpoint.put("nodeId", nodeId);
                    endpoint.put("name", stageEndpointName(chain.getId(), hash));
                    if (splitUdp) endpoint.put("udpServiceName", stageEndpointName(chain.getId(), hash) + "_udp");
                    endpoint.put("port", port);
                    if (udpProbe) {
                        Integer stunPort = allocateStagePort(node, used);
                        if (stunPort == null) return R.err("节点 " + node.getName() + " 没有可用的 UDP 探测中继端口");
                        endpoint.put("stunPort", stunPort);
                    }
                    endpoint.put("chainName", suffix.size() > 1 ? stageChainName(chain.getId(), hash) : null);
                    JSONArray suffixIds = new JSONArray();
                    suffixIds.addAll(suffix);
                    endpoint.put("suffixNodeIds", suffixIds);
                    endpoints.put(suffixKey, endpoint);
                }

                Node node = nodeService.getById(combination.get(hopIndex));
                JSONObject pathHop = new JSONObject();
                pathHop.put("nodeId", node.getId());
                pathHop.put("nodeName", node.getName());
                pathHop.put("host", node.getServerIp());
                pathHop.put("port", endpoint.getInteger("port"));
                if (udpProbe && hopIndex == 0) pathHop.put("stunPort", endpoint.getInteger("stunPort"));
                pathHop.put("endpointName", endpoint.getString("name"));
                pathHop.put("suffixKey", suffixKey);
                pathHops.add(pathHop);
            }

            JSONObject path = new JSONObject();
            path.put("name", pathName(chain.getId(), pathIndex));
            path.put("hops", pathHops);
            path.put("available", false);
            path.put("latencyMs", null);
            path.put("lastProbeTime", null);
            path.put("error", null);
            paths.add(path);
        }

        JSONObject endpointConfig = new JSONObject();
        endpointConfig.put("format", 2);
        JSONArray endpointArray = new JSONArray();
        endpointArray.addAll(endpoints.values());
        endpointConfig.put("endpoints", endpointArray);
        chain.setEndpointPortsJson(endpointConfig.toJSONString());
        chain.setPathsJson(paths.toJSONString());
        chain.setSelectedPathIndex(validPathIndex(chain, paths));
        updateById(chain);

        Set<Long> nodeIds = endpoints.values().stream().map(item -> item.getLong("nodeId")).collect(Collectors.toCollection(LinkedHashSet::new));
        for (Long nodeId : nodeIds) {
            Node node = nodeService.getById(nodeId);
            GostDto auth = GostUtil.AddAuther(nodeId, autherName(chain.getId(), nodeId), authUsername(chain.getId(), nodeId), authPassword(chain.getId(), node));
            if (!success(auth)) return R.err("候选节点认证配置失败：" + node.getName() + "，" + (auth == null ? "节点无响应" : auth.getMsg()));
        }

        Map<String, JSONObject> endpointBySuffix = endpoints;
        for (JSONObject endpoint : endpoints.values()) {
            JSONArray suffix = endpoint.getJSONArray("suffixNodeIds");
            if (suffix.size() <= 1) continue;
            List<Long> nextSuffix = new ArrayList<>();
            for (int i = 1; i < suffix.size(); i++) nextSuffix.add(suffix.getLong(i));
            String nextKey = nextSuffix.stream().map(String::valueOf).collect(Collectors.joining("_"));
            JSONObject next = endpointBySuffix.get(nextKey);
            Node currentNode = nodeService.getById(endpoint.getLong("nodeId"));
            Node nextNode = nodeService.getById(next.getLong("nodeId"));
            JSONArray linkHops = new JSONArray();
            JSONObject node = new JSONObject();
            node.put("name", "next-hop");
            node.put("addr", formatAddress(nextNode.getServerIp(), next.getInteger("port")));
            JSONObject connector = new JSONObject();
            connector.put("type", "relay");
            connector.put("metadata", GostUtil.chainRelayConnectorMetadata());
            JSONObject auth = new JSONObject();
            auth.put("username", authUsername(chain.getId(), nextNode.getId()));
            auth.put("password", authPassword(chain.getId(), nextNode));
            connector.put("auth", auth);
            JSONObject dialer = new JSONObject();
            dialer.put("type", linkProtocol);
            JSONObject metadata = new JSONObject();
            metadata.put("keepAlive", true);
            metadata.put("ttl", "10s");
            if (!splitUdp) dialer.put("metadata", metadata);
            node.put("connector", connector);
            node.put("dialer", dialer);
            JSONArray nodes = new JSONArray();
            nodes.add(node);
            JSONObject hop = new JSONObject();
            hop.put("name", "hop-0");
            hop.put("nodes", nodes);
            linkHops.add(hop);
            GostDto route = GostUtil.AddRouteChain(currentNode.getId(), endpoint.getString("chainName"), linkHops, linkProtocol);
            if (!success(route)) return R.err("创建节点间" + linkLabel + "链路失败：" + currentNode.getName() + " → " + nextNode.getName() + "，" + (route == null ? "节点无响应" : route.getMsg()));
        }

        Node entry = nodeService.getById(chain.getInNodeId());
        String stunKey = udpProbe ? udpStunKey(chain, entry) : null;
        for (JSONObject endpoint : endpoints.values()) {
            JSONArray suffix = endpoint.getJSONArray("suffixNodeIds");
            String targetHost;
            Integer targetPort;
            JSONObject next = null;
            if (suffix.size() <= 1) {
                targetHost = chain.getTargetHost();
                targetPort = chain.getTargetPort();
            } else {
                List<Long> nextSuffix = new ArrayList<>();
                for (int i = 1; i < suffix.size(); i++) nextSuffix.add(suffix.getLong(i));
                String nextKey = nextSuffix.stream().map(String::valueOf).collect(Collectors.joining("_"));
                next = endpointBySuffix.get(nextKey);
                Node nextNode = nodeService.getById(next.getLong("nodeId"));
                targetHost = nextNode.getServerIp();
                targetPort = next.getInteger("port");
            }
            Node node = nodeService.getById(endpoint.getLong("nodeId"));
            GostDto service = GostUtil.AddChainStageEndpoint(node.getId(), endpoint.getString("name"), endpoint.getInteger("port"),
                    linkProtocol, autherName(chain.getId(), node.getId()), targetHost, targetPort, endpoint.getString("chainName"));
            if (!success(service)) return R.err("创建" + linkLabel + "中继服务失败：" + node.getName() + "，" + (service == null ? "节点无响应" : service.getMsg()));
            if (splitUdp) {
                GostDto udpService = GostUtil.AddPlainUdpStage(node.getId(), endpoint.getString("udpServiceName"),
                        endpoint.getInteger("port"), targetHost, targetPort);
                if (!success(udpService)) return R.err("创建UDP中继服务失败：" + node.getName() + "，" + (udpService == null ? "节点无响应" : udpService.getMsg()));
            }
            if (udpProbe) {
                String nextProbe = next == null ? null : formatAddress(targetHost, next.getInteger("stunPort"));
                String echoTarget = next == null && UDP_ECHO.equals(chain.getProbeMode())
                        ? formatAddress(targetHost, UDP_ECHO_PORT) : null;
                GostDto probeService = GostUtil.AddUdpProbeStage(node.getId(), stunServiceName(endpoint),
                        endpoint.getInteger("stunPort"), nextProbe, echoTarget, stunKey);
                if (!success(probeService)) return R.err("创建 UDP 探测中继失败：" + node.getName() + "，" + (probeService == null ? "节点无响应" : probeService.getMsg()));
            }
        }

        for (int i = 0; i < paths.size(); i++) {
            JSONArray pathHops = paths.getJSONObject(i).getJSONArray("hops");
            JSONArray firstHop = new JSONArray();
            firstHop.add(pathHops.getJSONObject(0));
            GostDto route = GostUtil.AddRouteChain(entry.getId(), paths.getJSONObject(i).getString("name"),
                    buildGostHops(firstHop, linkProtocol, chain.getId()), linkProtocol);
            if (!success(route)) return R.err("创建入口" + linkLabel + "链路失败：" + (route == null ? "节点无响应" : route.getMsg()));
        }

        GostDto ingress = configureIngress(chain, paths.getJSONObject(chain.getSelectedPathIndex()), false);
        if (!success(ingress)) return R.err("入口TCP/UDP转发服务创建失败：" + (ingress == null ? "节点无响应" : ingress.getMsg()));
        if (Objects.equals(chain.getStatus(), 0)) {
            GostDto paused = GostUtil.PauseService(entry.getId(), SERVICE_PREFIX + chain.getId());
            if (!success(paused)) return R.err("暂停已禁用转发链失败：" + paused.getMsg());
        }
        chain.setLastProbeTime(0L);
        updateById(chain);
        return R.ok();
    }

    private Integer allocateStagePort(Node node, Set<Integer> used) {
        for (int port = node.getPortSta(); port <= node.getPortEnd(); port++) {
            if (used.add(port)) return port;
        }
        return null;
    }

    private int validPathIndex(ForwardChain chain, JSONArray paths) {
        Integer index = chain.getSelectedPathIndex();
        return index != null && index >= 0 && index < paths.size() ? index : 0;
    }

    private GostDto configureIngress(ForwardChain chain, JSONObject path, boolean update) {
        if ("udp".equals(chain.getProtocol())) {
            JSONObject firstHop = path.getJSONArray("hops").getJSONObject(0);
            return GostUtil.AddSplitChainForwardServices(chain.getInNodeId(), SERVICE_PREFIX + chain.getId(),
                    chain.getInPort(), chain.getTargetHost(), chain.getTargetPort(), path.getString("name"),
                    firstHop.getString("host"), firstHop.getInteger("port"), update);
        }
        return GostUtil.AddChainForwardServices(chain.getInNodeId(), SERVICE_PREFIX + chain.getId(),
                chain.getInPort(), chain.getTargetHost(), chain.getTargetPort(), path.getString("name"), update);
    }

    private JSONArray buildGostHops(JSONArray pathHops, String protocol, Long chainId) {
        JSONArray hops = new JSONArray();
        for (int i = 0; i < pathHops.size(); i++) {
            JSONObject item = pathHops.getJSONObject(i);
            JSONObject node = new JSONObject();
            node.put("name", "node-" + i);
            node.put("addr", formatAddress(item.getString("host"), item.getInteger("port")));
            JSONObject connector = new JSONObject(); connector.put("type", "relay");
            connector.put("metadata", GostUtil.chainRelayConnectorMetadata());
            Node endpointNode = nodeService.getById(item.getLong("nodeId"));
            JSONObject auth = new JSONObject(); auth.put("username", authUsername(chainId, endpointNode.getId())); auth.put("password", authPassword(chainId, endpointNode));
            connector.put("auth", auth);
            JSONObject dialer = new JSONObject(); dialer.put("type", protocol);
            if ("quic".equals(protocol)) { JSONObject md = new JSONObject(); md.put("keepAlive", true); md.put("ttl", "10s"); dialer.put("metadata", md); }
            node.put("connector", connector); node.put("dialer", dialer);
            JSONArray nodes = new JSONArray(); nodes.add(node);
            JSONObject hop = new JSONObject(); hop.put("name", "hop-" + i); hop.put("nodes", nodes);
            hops.add(hop);
        }
        return hops;
    }

    private JSONArray buildPaths(ForwardChain chain, List<ForwardChainDto.Hop> hops, Map<String, Integer> ports) {
        JSONArray paths = new JSONArray();
        List<List<Long>> combinations = new ArrayList<>();
        expand(hops, 0, new ArrayList<>(), combinations);
        int index = 0;
        for (List<Long> combination : combinations) {
            JSONArray pathHops = new JSONArray();
            for (Long nodeId : combination) {
                Node node = nodeService.getById(nodeId);
                JSONObject h = new JSONObject();
                h.put("nodeId", nodeId); h.put("nodeName", node.getName()); h.put("host", node.getServerIp()); h.put("port", ports.get(nodeId.toString()));
                pathHops.add(h);
            }
            JSONObject path = new JSONObject();
            path.put("name", pathName(chain.getId(), index));
            path.put("hops", pathHops);
            path.put("available", false);
            path.put("latencyMs", null);
            path.put("lastProbeTime", null);
            path.put("error", null);
            paths.add(path);
            index++;
        }
        return paths;
    }

    private void expand(List<ForwardChainDto.Hop> hops, int index, List<Long> current, List<List<Long>> out) {
        if (index == hops.size()) { out.add(new ArrayList<>(current)); return; }
        for (Long id : hops.get(index).getNodeIds()) { current.add(id); expand(hops, index + 1, current, out); current.remove(current.size() - 1); }
    }

    private Map<String, Integer> allocateEndpointPorts(ForwardChain chain, List<ForwardChainDto.Hop> hops, Map<Long, Set<Integer>> portsToAvoid) {
        Set<Long> ids = hops.stream().flatMap(h -> h.getNodeIds().stream()).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Integer> ports = new LinkedHashMap<>();
        Map<Long, Set<Integer>> used = collectUsedPorts(chain);
        mergePorts(used, portsToAvoid);
        for (Long nodeId : ids) {
            Node node = nodeService.getById(nodeId);
            Set<Integer> portsUsed = used.computeIfAbsent(nodeId, k -> new HashSet<>());
            Integer chosen = null;
            for (int p = node.getPortSta(); p <= node.getPortEnd(); p++) if (!portsUsed.contains(p)) { chosen = p; break; }
            if (chosen == null) return null;
            portsUsed.add(chosen);
            ports.put(nodeId.toString(), chosen);
        }
        return ports;
    }

    private Map<Long, Set<Integer>> readEndpointPorts(ForwardChain chain) {
        return portUsageService.collectChainEndpointPorts(chain);
    }

    private Map<Long, Map<Integer, Long>> readRetiredPorts(ForwardChain chain) {
        JSONObject config = JSON.parseObject(chain.getEndpointPortsJson() == null ? "{}" : chain.getEndpointPortsJson());
        return readRetiredPorts(config);
    }

    private Map<Long, Map<Integer, Long>> readRetiredPorts(JSONObject config) {
        Map<Long, Map<Integer, Long>> retired = new HashMap<>();
        JSONObject byNode = config.getJSONObject("retiredPorts");
        if (byNode == null) return retired;
        long now = System.currentTimeMillis();
        for (String nodeKey : byNode.keySet()) {
            Long nodeId;
            try { nodeId = Long.valueOf(nodeKey); } catch (NumberFormatException ignored) { continue; }
            JSONArray entries = byNode.getJSONArray(nodeKey);
            if (entries == null) continue;
            for (int i = 0; i < entries.size(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                Integer port = entry.getInteger("port");
                Long expiresAt = entry.getLong("expiresAt");
                if (port != null && expiresAt != null && expiresAt > now) {
                    retired.computeIfAbsent(nodeId, k -> new HashMap<>()).put(port, expiresAt);
                }
            }
        }
        return retired;
    }

    private void retirePorts(Map<Long, Map<Integer, Long>> retired, Map<Long, Set<Integer>> ports, long expiresAt) {
        ports.forEach((nodeId, nodePorts) -> {
            Map<Integer, Long> nodeRetired = retired.computeIfAbsent(nodeId, k -> new HashMap<>());
            nodePorts.forEach(port -> nodeRetired.merge(port, expiresAt, Math::max));
        });
    }

    private Map<Long, Set<Integer>> activeRetiredPorts(Map<Long, Map<Integer, Long>> retired) {
        Map<Long, Set<Integer>> ports = new HashMap<>();
        long now = System.currentTimeMillis();
        retired.forEach((nodeId, nodePorts) -> nodePorts.forEach((port, expiresAt) -> {
            if (expiresAt > now) ports.computeIfAbsent(nodeId, k -> new HashSet<>()).add(port);
        }));
        return ports;
    }

    private void persistRetiredPorts(ForwardChain chain, Map<Long, Map<Integer, Long>> retired) {
        JSONObject config = JSON.parseObject(chain.getEndpointPortsJson() == null ? "{}" : chain.getEndpointPortsJson());
        JSONObject byNode = new JSONObject();
        long now = System.currentTimeMillis();
        retired.forEach((nodeId, nodePorts) -> {
            JSONArray entries = new JSONArray();
            nodePorts.forEach((port, expiresAt) -> {
                if (expiresAt <= now) return;
                JSONObject entry = new JSONObject();
                entry.put("port", port);
                entry.put("expiresAt", expiresAt);
                entries.add(entry);
            });
            if (!entries.isEmpty()) byNode.put(String.valueOf(nodeId), entries);
        });
        if (byNode.isEmpty()) config.remove("retiredPorts");
        else config.put("retiredPorts", byNode);
        chain.setEndpointPortsJson(config.toJSONString());
    }

    private void mergePorts(Map<Long, Set<Integer>> target, Map<Long, Set<Integer>> source) {
        if (source == null) return;
        source.forEach((nodeId, ports) -> target.computeIfAbsent(nodeId, k -> new HashSet<>()).addAll(ports));
    }

    private Map<Long, Set<Integer>> collectUsedPorts(ForwardChain chain) {
        Map<Long, Set<Integer>> used = portUsageService.collectUsedPorts(null, chain.getId());
        used.computeIfAbsent(chain.getInNodeId(), k -> new HashSet<>()).add(chain.getInPort());
        return used;
    }

    private void teardown(ForwardChain chain) {
        Node entry = nodeService.getById(chain.getInNodeId());
        if (entry != null) {
            GostUtil.DeleteService(entry.getId(), SERVICE_PREFIX + chain.getId());
            JSONArray paths = JSON.parseArray(chain.getPathsJson() == null ? "[]" : chain.getPathsJson());
            for (int i = 0; i < paths.size(); i++) GostUtil.DeleteRouteChain(entry.getId(), paths.getJSONObject(i).getString("name"));
        }
        JSONObject ports = JSON.parseObject(chain.getEndpointPortsJson() == null ? "{}" : chain.getEndpointPortsJson());
        if (Objects.equals(ports.getInteger("format"), 2)) {
            JSONArray endpoints = ports.getJSONArray("endpoints");
            Set<Long> nodeIds = new HashSet<>();
            if (endpoints != null) for (int i = 0; i < endpoints.size(); i++) {
                JSONObject endpoint = endpoints.getJSONObject(i);
                Long nodeId = endpoint.getLong("nodeId");
                nodeIds.add(nodeId);
                GostUtil.DeleteNamedService(nodeId, endpoint.getString("name"));
                if (endpoint.getString("udpServiceName") != null) GostUtil.DeleteNamedService(nodeId, endpoint.getString("udpServiceName"));
                if (endpoint.getInteger("stunPort") != null) GostUtil.DeleteNamedService(nodeId, stunServiceName(endpoint));
                if (endpoint.getString("chainName") != null) GostUtil.DeleteRouteChain(nodeId, endpoint.getString("chainName"));
            }
            for (Long nodeId : nodeIds) GostUtil.DeleteAuther(nodeId, autherName(chain.getId(), nodeId));
        } else {
            for (String key : ports.keySet()) {
                Long nodeId;
                try { nodeId = Long.valueOf(key); } catch (NumberFormatException ignored) { continue; }
                GostUtil.DeleteNamedService(nodeId, endpointName(chain.getId(), nodeId));
                GostUtil.DeleteAuther(nodeId, autherName(chain.getId(), nodeId));
            }
        }
    }

    private synchronized void probeDueChains() {
        long now = System.currentTimeMillis();
        // Oldest/unstarted cycles get the next free queue slot; a busy panel
        // must not continually requeue earlier IDs ahead of unstarted chains.
        for (ForwardChain chain : list(new QueryWrapper<ForwardChain>()
                .select("id", "last_probe_time", "probe_interval_ms").eq("status", 1).orderByAsc("last_probe_time", "id"))) {
            if (chain.getLastProbeTime() != null && now - chain.getLastProbeTime() < chain.getProbeIntervalMs()) continue;
            probeExecutor.submitIfIdle(chain.getId(), () -> runProbeCycle(chain.getId()));
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
    public void scheduleProbes() { probeDueChains(); }

    private ForwardChainProbeStatus runProbeCycle(Long id) {
        ForwardChain chain;
        synchronized (this) {
            chain = getById(id);
            if (chain == null || !Objects.equals(chain.getStatus(), 1)) {
                return new ForwardChainProbeStatus(State.CANCELLED, "转发链已暂停或删除，本轮探测已取消");
            }
            // Measure intervals from actual execution, not time spent in the queue.
            update(new LambdaUpdateWrapper<ForwardChain>().eq(ForwardChain::getId, id)
                    .set(ForwardChain::getLastProbeTime, System.currentTimeMillis()));
        }
        JSONArray paths = JSON.parseArray(chain.getPathsJson());
        if (paths == null || paths.isEmpty()) {
            return new ForwardChainProbeStatus(State.FAILED, "转发链没有可探测的候选路径");
        }
        try {
            // Collect the entire round before publishing results and choosing a path.
            String stunKey = usesUdpProbe(chain) ? udpStunKey(chain, nodeService.getById(chain.getInNodeId())) : null;
            probeExecutor.probePaths(paths.size(), index -> probePath(chain, paths.getJSONObject(index), stunKey));
            return applyProbeResults(chain, paths);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("转发链 {} 探测已中断", id);
            return new ForwardChainProbeStatus(State.CANCELLED, "本轮探测已中断");
        }
    }

    private synchronized ForwardChainProbeStatus applyProbeResults(ForwardChain chain, JSONArray fresh) {
        Long id = chain.getId();
        ForwardChain updated = getById(id);
        if (!sameDeployment(chain, updated)) {
            return new ForwardChainProbeStatus(State.CANCELLED, "转发链配置或状态已变更，本轮结果已作废");
        }

        int oldSelected = updated.getSelectedPathIndex() == null ? 0 : updated.getSelectedPathIndex();
        List<Double> latencies = new ArrayList<>();
        List<Boolean> availability = new ArrayList<>();
        for (int i = 0; i < fresh.size(); i++) {
            JSONObject path = fresh.getJSONObject(i);
            availability.add(Boolean.TRUE.equals(path.getBoolean("available")));
            latencies.add(path.getDouble("latencyMs"));
        }
        int selected = com.admin.common.utils.ForwardChainSelection.choosePath(latencies, availability, oldSelected, updated.getSwitchThresholdMs());
        ForwardChainProbeStatus result;
        if (selected != oldSelected) {
            GostDto changed = configureIngress(updated, fresh.getJSONObject(selected), true);
            if (success(changed)) {
                result = new ForwardChainProbeStatus(State.SWITCHED, "手动探测完成，已切换路径");
            } else {
                log.warn("转发链 {} 路径切换失败: {}", id, changed == null ? "节点无响应" : changed.getMsg());
                selected = oldSelected;
                result = new ForwardChainProbeStatus(State.FAILED, "路径切换失败，保留当前路径");
            }
        } else {
            String message = availability.contains(true) ? "手动探测完成，保持当前路径" : "所有路径均不可用，保留当前路径";
            result = new ForwardChainProbeStatus(State.KEPT, message);
        }
        boolean saved = update(new LambdaUpdateWrapper<ForwardChain>().eq(ForwardChain::getId, id)
                .set(ForwardChain::getPathsJson, fresh.toJSONString())
                .set(ForwardChain::getSelectedPathIndex, selected)
                .set(ForwardChain::getUpdatedTime, System.currentTimeMillis()));
        return saved ? result : new ForwardChainProbeStatus(State.FAILED, "保存探测结果失败");
    }

    private void probePath(ForwardChain chain, JSONObject path, String stunKey) {
        Long entryId = chain.getInNodeId();
        path.put("available", false);
        path.put("latencyMs", null);
        path.put("error", "该路径探测未完成");
        try {
            boolean udpProbe = usesUdpProbe(chain);
            GostDto result;
            if (udpProbe) {
                JSONObject first = path.getJSONArray("hops").getJSONObject(0);
                Integer port = first.getInteger("stunPort");
                if (port == null) throw new IllegalStateException("路径缺少 UDP 探测中继");
                result = GostUtil.UdpProbeThroughChain(entryId, formatAddress(first.getString("host"), port), stunKey,
                        chain.getProbeTimeoutMs(), chain.getProbeMode());
            } else {
                result = GostUtil.TcpPingThroughChain(entryId, path.getString("name"), chain.getTargetHost(), chain.getTargetPort(), chain.getProbeTimeoutMs());
            }
            JSONObject data = result == null || result.getData() == null ? new JSONObject() : JSON.parseObject(JSON.toJSONString(result.getData()));
            Double latency = data.getDouble(udpProbe ? "latencyMs" : "averageTime");
            boolean ok = success(result) && Boolean.TRUE.equals(data.getBoolean("success"))
                    && latency != null && Double.isFinite(latency) && latency >= 0;
            path.put("available", ok);
            path.put("latencyMs", ok ? latency : null);
            String error = data.getString("errorMessage");
            if (error == null) error = success(result) ? "未收到有效探测结果" : result == null ? "节点无响应" : result.getMsg();
            path.put("error", ok ? null : error);
        } catch (Exception e) {
            path.put("available", false);
            path.put("latencyMs", null);
            path.put("error", "探测失败：" + e.getMessage());
        }
        path.put("lastProbeTime", System.currentTimeMillis());
    }

    private boolean sameDeployment(ForwardChain snapshot, ForwardChain current) {
        // A probe started before a protocol/port edit must never restore its old
        // path endpoints or retarget the new raw UDP ingress to a retired port.
        return current != null && Objects.equals(current.getStatus(), 1)
                && Objects.equals(snapshot.getUpdatedTime(), current.getUpdatedTime())
                && sameTopology(snapshot, current)
                && Objects.equals(snapshot.getProbeMode(), current.getProbeMode())
                && Objects.equals(snapshot.getEndpointPortsJson(), current.getEndpointPortsJson());
    }

    private boolean sameTopology(ForwardChain snapshot, ForwardChain current) {
        return Objects.equals(snapshot.getProtocol(), current.getProtocol())
                && Objects.equals(snapshot.getInNodeId(), current.getInNodeId())
                && Objects.equals(snapshot.getInPort(), current.getInPort())
                && Objects.equals(snapshot.getTargetHost(), current.getTargetHost())
                && Objects.equals(snapshot.getTargetPort(), current.getTargetPort())
                && Objects.equals(snapshot.getHopsJson(), current.getHopsJson());
    }

    private boolean usesUdpProbe(ForwardChain chain) {
        return UDP_STUN.equals(chain.getProbeMode()) || UDP_ECHO.equals(chain.getProbeMode());
    }

    private String udpStunKey(ForwardChain chain, Node entry) {
        return sha256Hex("fluxchain-udp-stun:" + chain.getId() + ":" + entry.getSecret());
    }

    private String stunServiceName(JSONObject endpoint) {
        return endpoint.getString("name") + "_stun";
    }

    private String autherName(Long chainId, Long nodeId) { return "fc_auth_" + chainId + "_" + nodeId; }
    private String authUsername(Long chainId, Long nodeId) { return "fc_" + chainId + "_" + nodeId; }
    private String authPassword(Long chainId, Node node) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((node.getSecret() + ":flux-chain:" + chainId + ":" + node.getId()).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256不可用", e); }
    }

    private String endpointName(Long chainId, Long nodeId) { return "fcx_" + chainId + "_" + nodeId; }
    private String stageEndpointName(Long chainId, String suffixHash) { return "fcx_" + chainId + "_" + suffixHash; }
    private String stageChainName(Long chainId, String suffixHash) { return "fcstage_" + chainId + "_" + suffixHash; }
    private String pathName(Long chainId, int index) { return "fcroute_" + chainId + "_" + index; }
    private String formatAddress(String host, Integer port) { return host != null && host.contains(":") && !host.startsWith("[") ? "[" + host + "]:" + port : host + ":" + port; }
    private String sha256Hex(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256不可用", e); }
    }
    private boolean success(GostDto result) { return result != null && result.getCode() != null && result.getCode() == 0; }
}
