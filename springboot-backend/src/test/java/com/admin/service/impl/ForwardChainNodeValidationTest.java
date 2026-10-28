package com.admin.service.impl;

import com.admin.common.dto.ForwardChainDto;
import com.admin.common.lang.R;
import com.admin.entity.ForwardChain;
import com.admin.entity.Node;
import com.admin.mapper.ForwardChainMapper;
import com.admin.service.NodePortUsageService;
import com.admin.service.NodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ForwardChainNodeValidationTest {
    private ForwardChainServiceImpl service;
    private ForwardChainMapper mapper;
    private NodePortUsageService portUsage;

    @BeforeEach
    void setUp() {
        service = new ForwardChainServiceImpl();
        mapper = mock(ForwardChainMapper.class);
        NodeService nodes = mock(NodeService.class);
        portUsage = mock(NodePortUsageService.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "nodeService", nodes);
        ReflectionTestUtils.setField(service, "portUsageService", portUsage);
        when(mapper.selectList(any())).thenReturn(Collections.emptyList());
        when(portUsage.collectUsedPorts(any(), any())).thenReturn(Collections.emptyMap());
        Node node = new Node();
        node.setStatus(1);
        node.setServerIp("127.0.0.1");
        node.setPortSta(28000);
        node.setPortEnd(29000);
        when(nodes.getById(any())).thenReturn(node);
    }

    @Test
    void rejectsRepeatedCandidatesAcrossHopsBeforeCreatingAnything() {
        R result = service.createChain(dto(new long[]{2, 3}, new long[]{3, 4}));
        assertTrue(result.getMsg().contains("不同跳"));
        assertNotEquals(0, result.getCode());
        verify(mapper, never()).insert(any());
    }

    @Test
    void rejectsNonAdjacentRepeatedCandidates() {
        R result = service.createChain(dto(new long[]{2}, new long[]{3}, new long[]{2, 4}));
        assertNotEquals(0, result.getCode());
        assertTrue(result.getMsg().contains("不同跳"));
    }

    @Test
    void rejectsRepeatedCandidatesWithinOneHop() {
        R result = service.createChain(dto(new long[]{2, 2}));
        assertNotEquals(0, result.getCode());
        assertTrue(result.getMsg().contains("同一跳"));
    }

    @Test
    void rejectsEntryAsCandidate() {
        R result = service.createChain(dto(new long[]{1, 2}));
        assertNotEquals(0, result.getCode());
        assertTrue(result.getMsg().contains("入口节点"));
    }

    @Test
    void invalidUpdateLeavesSavedChainUntouched() {
        ForwardChain existing = new ForwardChain();
        existing.setId(7L);
        when(mapper.selectById(7L)).thenReturn(existing);
        ForwardChainDto dto = dto(new long[]{2}, new long[]{2, 3});
        dto.setId(7L);
        assertNotEquals(0, service.updateChain(dto).getCode());
        verify(mapper, never()).updateById(any());
    }

    @Test
    void allowsUniqueCandidates() {
        assertNull(ReflectionTestUtils.invokeMethod(service, "validate", dto(new long[]{2, 3}, new long[]{4, 5}), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tls", "quic", "tcp", "udp"})
    void acceptsSupportedLinkProtocols(String protocol) {
        ForwardChainDto input = dto(new long[]{2, 3}, new long[]{4, 5});
        input.setProtocol(protocol);
        assertNull(ReflectionTestUtils.invokeMethod(service, "validate", input, null));
    }

    @Test
    void rejectsUnimplementedLinkProtocols() {
        ForwardChainDto input = dto(new long[]{2});
        input.setProtocol("dtls");
        assertNotNull(ReflectionTestUtils.invokeMethod(service, "validate", input, null));
        verify(mapper, never()).insert(any());
    }

    @Test
    void otherChainsCanReuseTheSameNodes() {
        when(portUsage.collectUsedPorts(any(), any())).thenReturn(Map.of(1L, Set.of(28101)));
        assertNull(ReflectionTestUtils.invokeMethod(service, "validate", dto(new long[]{2, 3}), null));
    }

    private ForwardChainDto dto(long[]... hops) {
        ForwardChainDto dto = new ForwardChainDto();
        dto.setName("节点去重测试");
        dto.setInNodeId(1L);
        dto.setInPort(28100);
        dto.setTargetHost("127.0.0.1");
        dto.setTargetPort(28777);
        dto.setHops(Arrays.stream(hops).map(ids -> {
            ForwardChainDto.Hop hop = new ForwardChainDto.Hop();
            hop.setNodeIds(Arrays.stream(ids).boxed().toList());
            return hop;
        }).toList());
        return dto;
    }
}
