package com.admin.service.impl;

import com.admin.common.dto.ForwardUpdateDto;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.service.NodePortUsageService;
import com.admin.service.NodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ForwardSelfLoopValidationTest {
    private ForwardServiceImpl service;
    private NodePortUsageService ports;
    private Node entry;
    private Node exit;

    @BeforeEach
    void setUp() {
        service = new ForwardServiceImpl();
        NodeService nodes = mock(NodeService.class);
        entry = node(1L, 1000, 60000, "192.0.2.10");
        exit = node(2L, 3000, 60000, "192.0.2.20");
        when(nodes.getNodeById(1L)).thenReturn(entry);
        when(nodes.getNodeById(2L)).thenReturn(exit);
        ports = mock(NodePortUsageService.class);
        when(ports.collectUsedPorts(any(), any())).thenReturn(Map.of(1L, Set.of(1000, 1001)));
        ReflectionTestUtils.setField(service, "nodeService", nodes);
        ReflectionTestUtils.setField(service, "portUsageService", ports);
    }

    @Test
    void autoPortSkipsOccupiedPortsAndLocalDestinationPort() {
        for (String address : new String[]{"127.0.0.1:1002", "[::]:1002", "[::1]:1002", "192.0.2.10:1002"}) {
            Object allocated = allocate(tunnel(1), null, address);
            assertEquals(false, ReflectionTestUtils.getField(allocated, "hasError"));
            assertEquals(1003, ReflectionTestUtils.getField(allocated, "inPort"), address);
        }
    }

    @Test
    void autoPortSkipsAllLocalTargetsButKeepsSmallestSafePort() {
        Object allocated = allocate(tunnel(1), null, "127.0.0.1:1002,[::]:1003,127.0.0.1:1005");
        assertEquals(1004, ReflectionTestUtils.getField(allocated, "inPort"));
        assertEquals(1002, ReflectionTestUtils.getField(allocate(tunnel(1), null, "198.51.100.20:1002"), "inPort"));
    }

    @Test
    void explicitSelfLoopPortIsRejectedAndDifferentLocalPortIsAllowed() {
        Object rejected = allocate(tunnel(1), 1002, "other.example:8000,127.0.0.1:1002");
        assertEquals(true, ReflectionTestUtils.getField(rejected, "hasError"));
        assertTrue(((String) ReflectionTestUtils.getField(rejected, "errorMessage")).contains("循环转发"));
        assertEquals(false, ReflectionTestUtils.getField(allocate(tunnel(1), 1002, "127.0.0.1:1003"), "hasError"));
    }

    @Test
    void rejectsExhaustedRangeInsteadOfFallingBackToSelfLoop() {
        entry.setPortEnd(1002);
        assertEquals(true, ReflectionTestUtils.getField(allocate(tunnel(1), null, "[::]:1002"), "hasError"));
    }

    @Test
    void tunnelLoopbackTargetDoesNotReserveTheSamePortOnADifferentIngressNode() {
        Object allocated = allocate(tunnel(2), 1002, "127.0.0.1:1002");
        assertEquals(false, ReflectionTestUtils.getField(allocated, "hasError"));
        assertEquals(1002, ReflectionTestUtils.getField(allocated, "inPort"));
        assertEquals(3000, ReflectionTestUtils.getField(allocated, "outPort"));
    }

    @Test
    void tunnelExitPortAlsoAvoidsItsOwnFinalDestination() {
        when(ports.collectUsedPorts(any(), any())).thenReturn(Map.of(1L, Set.of(1000, 1001), 2L, Set.of(3000, 3001)));
        Object allocated = allocate(tunnel(2), null, "[::]:3002");
        assertEquals(1002, ReflectionTestUtils.getField(allocated, "inPort"));
        assertEquals(3003, ReflectionTestUtils.getField(allocated, "outPort"));
        assertEquals(1003, ReflectionTestUtils.getField(allocate(tunnel(2), null, "192.0.2.10:1002"), "inPort"));
    }

    @Test
    void targetOnlyEditIsRejectedBeforeExistingRecordIsChanged() {
        Forward existing = existing();
        for (Integer port : new Integer[]{null, 1002}) {
            ForwardUpdateDto dto = update(port, "127.0.0.1:1002");
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> ReflectionTestUtils.invokeMethod(service, "updateForwardEntity", dto, existing, tunnel(1)));
            assertTrue(failure.getMessage().contains("循环转发"));
            assertEquals("target:8000", existing.getRemoteAddr());
            assertEquals(1002, existing.getInPort());
        }
    }

    @Test
    void safeTargetEditPreservesExistingPort() {
        Forward updated = ReflectionTestUtils.invokeMethod(service, "updateForwardEntity", update(null, "127.0.0.1:8001"), existing(), tunnel(1));
        assertNotNull(updated);
        assertEquals(1002, updated.getInPort());
        assertEquals("127.0.0.1:8001", updated.getRemoteAddr());
    }

    private Object allocate(Tunnel tunnel, Integer port, String target) {
        return ReflectionTestUtils.invokeMethod(service, "allocatePorts", tunnel, port, null, target);
    }

    private Tunnel tunnel(int type) {
        Tunnel tunnel = new Tunnel(); tunnel.setId(11L); tunnel.setType(type);
        tunnel.setInNodeId(1L); tunnel.setOutNodeId(type == 1 ? 1L : 2L);
        return tunnel;
    }

    private Node node(long id, int start, int end, String address) {
        Node node = new Node(); node.setId(id); node.setPortSta(start); node.setPortEnd(end); node.setServerIp(address);
        return node;
    }

    private Forward existing() {
        Forward forward = new Forward(); forward.setId(5L); forward.setTunnelId(11); forward.setInPort(1002); forward.setRemoteAddr("target:8000");
        return forward;
    }

    private ForwardUpdateDto update(Integer port, String target) {
        ForwardUpdateDto dto = new ForwardUpdateDto(); dto.setId(5L); dto.setTunnelId(11); dto.setInPort(port); dto.setRemoteAddr(target);
        return dto;
    }
}
