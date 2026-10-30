package com.admin.service.impl;

import com.admin.common.lang.R;
import com.admin.entity.ForwardChain;
import com.admin.entity.Node;
import com.admin.entity.ViteConfig;
import com.admin.service.ViteConfigService;
import com.admin.mapper.ForwardChainMapper;
import com.admin.mapper.NodeMapper;
import com.admin.mapper.TunnelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NodeServiceImplTest {
    private NodeServiceImpl service;
    private NodeMapper nodes;
    private TunnelMapper tunnels;
    private ForwardChainMapper chains;

    @Test
    void installCommandUsesVersionedReleaseAndQuotesArguments() {
        Node node = new Node();
        node.setSecret("node's-key");
        when(nodes.selectById(any())).thenReturn(node);
        ViteConfigService configs = mock(ViteConfigService.class);
        ViteConfig config = new ViteConfig();
        config.setValue("[2001:db8::1]:6365");
        when(configs.getOne(any())).thenReturn(config);
        ReflectionTestUtils.setField(service, "viteConfigService", configs);
        R response = service.getInstallCommand(1L);
        assertEquals(0, response.getCode());
        String command = response.getData().toString();
        assertTrue(command.startsWith("curl -fL --retry 2 https://github.com/Toto0912/flux-chain/releases/download/v1.0.1/install.sh"));
        assertTrue(command.contains("-o install.sh && bash ./install.sh -a '"));
        assertTrue(command.contains("-s 'node'\"'\"'s-key'"));
        assertFalse(command.contains("bqlpfy"));
        assertFalse(command.contains("/latest/"));
    }

    @BeforeEach
    void setUp() {
        service = new NodeServiceImpl();
        nodes = mock(NodeMapper.class);
        tunnels = mock(TunnelMapper.class);
        chains = mock(ForwardChainMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", nodes);
        ReflectionTestUtils.setField(service, "tunnelMapper", tunnels);
        ReflectionTestUtils.setField(service, "forwardChainMapper", chains);
        when(nodes.selectById(any())).thenReturn(new Node());
        when(nodes.deleteById(any())).thenReturn(1);
        when(tunnels.selectCount(any())).thenReturn(0);
        when(chains.selectList(any())).thenReturn(Collections.emptyList());
    }

    @Test
    void preventsDeletingChainEntry() {
        useChain(1, "[{\"nodeIds\":[2,3]}]", 1);
        assertBlocked(1L);
    }

    @ParameterizedTest
    @ValueSource(longs = {2, 3, 4, 5})
    void preventsDeletingEveryCandidateInEveryHop(long nodeId) {
        useChain(1, "[{\"nodeIds\":[2,3]},{\"nodeIds\":[4,5]}]", 1);
        assertBlocked(nodeId);
    }

    @Test
    void pausedChainsStillProtectNodes() {
        useChain(1, "[{\"nodeIds\":[2,3]}]", 0);
        assertBlocked(3L);
    }

    @Test
    void unusedNodeCanBeDeletedAndIdsAreComparedExactly() {
        useChain(1, "[{\"nodeIds\":[12,21]}]", 1);
        R result = service.deleteNode(2L);
        assertEquals(0, result.getCode());
        verify(nodes).deleteById(2L);
    }

    @Test
    void existingTunnelProtectionStillApplies() {
        when(tunnels.selectCount(any())).thenReturn(1);
        R result = service.deleteNode(2L);
        assertNotEquals(0, result.getCode());
        assertTrue(result.getMsg().contains("隧道"));
        verify(nodes, never()).deleteById(any());
    }

    private void useChain(long entryId, String hopsJson, int status) {
        ForwardChain chain = new ForwardChain();
        chain.setId(7L);
        chain.setName("游戏加速链");
        chain.setInNodeId(entryId);
        chain.setHopsJson(hopsJson);
        chain.setStatus(status);
        when(chains.selectList(any())).thenReturn(Collections.singletonList(chain));
    }

    private void assertBlocked(long nodeId) {
        R result = service.deleteNode(nodeId);
        assertNotEquals(0, result.getCode());
        assertTrue(result.getMsg().contains("游戏加速链"));
        verify(nodes, never()).deleteById(any());
    }
}
