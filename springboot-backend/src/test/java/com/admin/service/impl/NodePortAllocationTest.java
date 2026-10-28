package com.admin.service.impl;

import com.admin.common.dto.ForwardChainDto;
import com.admin.entity.*;
import com.admin.mapper.*;
import com.admin.service.NodePortUsageService;
import com.admin.service.NodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NodePortAllocationTest {
    private NodePortUsageService ports;
    private ForwardServiceImpl forwards;
    private ForwardChainServiceImpl chains;
    private ForwardMapper forwardMapper;
    private ForwardChainMapper chainMapper;
    private TunnelMapper tunnelMapper;

    @BeforeEach
    void setUp() {
        ports = new NodePortUsageService();
        forwardMapper = mock(ForwardMapper.class);
        chainMapper = mock(ForwardChainMapper.class);
        tunnelMapper = mock(TunnelMapper.class);
        ReflectionTestUtils.setField(ports, "forwardMapper", forwardMapper);
        ReflectionTestUtils.setField(ports, "forwardChainMapper", chainMapper);
        ReflectionTestUtils.setField(ports, "tunnelMapper", tunnelMapper);
        when(forwardMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(chainMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(tunnelMapper.selectList(any())).thenReturn(Collections.emptyList());
        NodeService nodes = mock(NodeService.class);
        when(nodes.getNodeById(any())).thenAnswer(invocation -> node(invocation.getArgument(0)));
        when(nodes.getById(any())).thenAnswer(invocation -> node(((Number) invocation.getArgument(0)).longValue()));
        forwards = new ForwardServiceImpl();
        ReflectionTestUtils.setField(forwards, "nodeService", nodes);
        ReflectionTestUtils.setField(forwards, "portUsageService", ports);
        chains = new ForwardChainServiceImpl();
        ReflectionTestUtils.setField(chains, "nodeService", nodes);
        ReflectionTestUtils.setField(chains, "portUsageService", ports);
        ReflectionTestUtils.setField(chains, "baseMapper", chainMapper);
    }

    @Test
    void ordinaryAutoAllocationSkipsPausedChainIngress() {
        ForwardChain chain = chain(7, 1, 28100, "{}");
        chain.setStatus(0);
        when(chainMapper.selectList(any())).thenReturn(List.of(chain));
        assertEquals(28101, allocateForward(1L, null));
    }

    @Test
    void ordinaryAllocationIncludesBothEndpointFormatsAndUnexpiredRetiredPorts() {
        long future = System.currentTimeMillis() + 60000;
        String config = "{\"format\":2,\"endpoints\":[{\"nodeId\":1,\"port\":28101}],\"retiredPorts\":{\"1\":[{\"port\":28102,\"expiresAt\":" + future + "},{\"port\":28103,\"expiresAt\":1}]}}";
        when(chainMapper.selectList(any())).thenReturn(List.of(chain(7, 9, 28900, "{\"1\":28100}"), chain(8, 9, 28901, config)));
        assertEquals(28103, allocateForward(1L, null));
    }

    @Test
    void manualOrdinaryPortCannotUseAChainEndpoint() {
        when(chainMapper.selectList(any())).thenReturn(List.of(chain(7, 9, 28900, "{\"1\":28100}")));
        assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(forwards, "isInPortAvailable", tunnel(11, 1, 2), 28100, null));
    }

    @Test
    void ordinaryExitAllocationSkipsChainAndOtherForwardPorts() {
        when(tunnelMapper.selectList(any())).thenReturn(List.of(tunnel(11, 1, 2)));
        when(forwardMapper.selectList(any())).thenReturn(List.of(forward(8, 11, 28100, 28201)));
        when(chainMapper.selectList(any())).thenReturn(List.of(chain(7, 2, 28200, "{}")));
        assertEquals(Integer.valueOf(28202), ReflectionTestUtils.invokeMethod(forwards, "allocateOutPort", tunnel(12, 1, 2), null));
    }

    @Test
    void chainEndpointAllocationSkipsAllOtherListenerTypes() {
        when(tunnelMapper.selectList(any())).thenReturn(List.of(tunnel(11, 2, 3), tunnel(12, 3, 2)));
        when(forwardMapper.selectList(any())).thenReturn(List.of(forward(8, 11, 28200, 28300), forward(9, 12, 28301, 28201)));
        when(chainMapper.selectList(any())).thenReturn(List.of(chain(6, 2, 28202, "{\"2\":28203}")));
        ForwardChain current = chain(7, 1, 28100, "{}");
        Map<String, Integer> allocated = ReflectionTestUtils.invokeMethod(chains, "allocateEndpointPorts", current, List.of(hop(2)), Collections.emptyMap());
        assertNotNull(allocated);
        assertEquals(28204, allocated.get("2"));
    }

    @Test
    void chainEntryCannotUseAnotherChainsIntermediatePort() {
        when(chainMapper.selectList(any())).thenReturn(List.of(chain(6, 2, 28200, "{\"1\":28100}")));
        ForwardChainDto dto = new ForwardChainDto();
        dto.setInNodeId(1L);
        dto.setInPort(28100);
        dto.setTargetPort(28777);
        dto.setHops(List.of(hop(3)));
        assertNotEquals(0, chains.createChain(dto).getCode());
        verify(chainMapper, never()).insert(any());
    }

    @Test
    void editingExcludesOnlyItsOwnResourceType() {
        when(tunnelMapper.selectList(any())).thenReturn(List.of(tunnel(11, 1, 2)));
        when(forwardMapper.selectList(any())).thenReturn(List.of(forward(7, 11, 28100, 28200)));
        when(chainMapper.selectList(any())).thenReturn(List.of(chain(7, 1, 28101, "{}")));
        assertEquals(Set.of(28101), ports.collectUsedPorts(7L, null).get(1L));
        assertEquals(Set.of(28100), ports.collectUsedPorts(null, 7L).get(1L));
    }

    @Test
    void reportsNoFreePortWhenRangeIsFull() {
        when(chainMapper.selectList(any())).thenReturn(List.of(chain(7, 1, 28100, "{\"1\":28101}"), chain(8, 1, 28102, "{\"1\":28103}"), chain(9, 1, 28104, "{\"1\":28105}")));
        assertNull(allocateForward(1L, null));
    }

    @Test
    void chainAutoAllocationSkipsForwardsPausedChainsAndRelayReservations() {
        when(tunnelMapper.selectList(any())).thenReturn(List.of(tunnel(11, 1, 2), tunnel(12, 2, 1)));
        when(forwardMapper.selectList(any())).thenReturn(List.of(forward(8, 11, 28100, 28200), forward(9, 12, 28201, 28101)));
        ForwardChain paused = chain(7, 1, 28102, "{\"1\":28103}");
        paused.setStatus(0);
        long future = System.currentTimeMillis() + 60000;
        when(chainMapper.selectList(any())).thenReturn(List.of(paused, chain(8, 2, 28202,
                "{\"format\":2,\"endpoints\":[],\"retiredPorts\":{\"1\":[{\"port\":28104,\"expiresAt\":" + future + "}]}}")));
        ForwardChainDto dto = autoChain();
        assertNull(ReflectionTestUtils.invokeMethod(chains, "validate", dto, null));
        assertEquals(28105, dto.getInPort());
    }

    @Test
    void chainAutoAllocationReportsExhaustionWithoutSaving() {
        when(chainMapper.selectList(any())).thenReturn(List.of(chain(7, 1, 28100, "{\"1\":28101}"), chain(8, 1, 28102, "{\"1\":28103}"), chain(9, 1, 28104, "{\"1\":28105}")));
        assertTrue(chains.createChain(autoChain()).getMsg().contains("已用尽"));
        verify(chainMapper, never()).insert(any());
    }

    @Test
    void blankPortUpdateRetainsExistingEntryPort() {
        ForwardChain existing = chain(7, 1, 28104, "{\"2\":28200}");
        when(chainMapper.selectById(7L)).thenReturn(existing);
        when(chainMapper.selectList(any())).thenReturn(List.of(existing));
        ForwardChainDto dto = autoChain();
        assertNull(ReflectionTestUtils.invokeMethod(chains, "validate", dto, 7L));
        assertEquals(28104, dto.getInPort());
    }

    @Test
    void movingEntryAvoidsItsOwnFormerRelayPort() {
        ForwardChain existing = chain(7, 2, 28200, "{\"1\":28100}");
        when(chainMapper.selectById(7L)).thenReturn(existing);
        when(chainMapper.selectList(any())).thenReturn(List.of(existing));
        ForwardChainDto dto = autoChain();
        assertNull(ReflectionTestUtils.invokeMethod(chains, "validate", dto, 7L));
        assertEquals(28101, dto.getInPort());
    }

    @Test
    void chainDtoAllowsMissingPortButRejectsInvalidExplicitPorts() {
        try (javax.validation.ValidatorFactory factory = javax.validation.Validation.buildDefaultValidatorFactory()) {
            ForwardChainDto dto = autoChain();
            assertTrue(factory.getValidator().validate(dto).isEmpty());
            for (int invalid : List.of(0, -1, 65536)) {
                dto.setInPort(invalid);
                assertTrue(factory.getValidator().validate(dto).stream().anyMatch(v -> v.getPropertyPath().toString().equals("inPort")));
            }
        }
    }

    private ForwardChainDto autoChain() {
        ForwardChainDto dto = new ForwardChainDto();
        dto.setName("自动分配测试"); dto.setInNodeId(1L);
        dto.setTargetHost("target"); dto.setTargetPort(28777); dto.setHops(List.of(hop(3)));
        return dto;
    }

    @Test
    void aSameNodeTunnelUsesDifferentIngressAndExitPorts() {
        Object allocation = ReflectionTestUtils.invokeMethod(forwards, "allocatePorts", tunnel(11, 1, 1), null, null);
        assertNotNull(allocation);
        assertEquals(28100, ReflectionTestUtils.getField(allocation, "inPort"));
        assertEquals(28101, ReflectionTestUtils.getField(allocation, "outPort"));
    }

    private Integer allocateForward(Long nodeId, Long excludeId) {
        return ReflectionTestUtils.invokeMethod(forwards, "allocatePortForNode", nodeId, excludeId);
    }

    private Node node(long id) {
        Node node = new Node(); node.setId(id); node.setStatus(1); node.setServerIp("127.0.0.1");
        node.setPortSta(28000 + (int) id * 100); node.setPortEnd(node.getPortSta() + 5); return node;
    }

    private ForwardChain chain(long id, long entry, int port, String endpoints) {
        ForwardChain chain = new ForwardChain(); chain.setId(id); chain.setInNodeId(entry); chain.setInPort(port); chain.setEndpointPortsJson(endpoints); return chain;
    }

    private Forward forward(long id, int tunnel, int in, int out) {
        Forward forward = new Forward(); forward.setId(id); forward.setTunnelId(tunnel); forward.setInPort(in); forward.setOutPort(out); return forward;
    }

    private Tunnel tunnel(long id, long in, long out) {
        Tunnel tunnel = new Tunnel(); tunnel.setId(id); tunnel.setInNodeId(in); tunnel.setOutNodeId(out); tunnel.setType(2); return tunnel;
    }

    private ForwardChainDto.Hop hop(long id) {
        ForwardChainDto.Hop hop = new ForwardChainDto.Hop(); hop.setNodeIds(List.of(id)); return hop;
    }
}
