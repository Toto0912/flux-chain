package com.admin.common.utils;

import com.admin.entity.Node;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ForwardLoopGuardTest {
    private Node node() {
        Node node = new Node(); node.setId(1L);
        node.setIp("192.0.2.10"); node.setServerIp("entry.example,2001:db8::10");
        return node;
    }

    @Test
    void recognizesLoopbackAndUnspecifiedAddressesWithoutDns() {
        for (String host : new String[]{"127.0.0.1", "127.2.3.4", "0.0.0.0", "localhost", "LOCALHOST.",
                "[::]", "[::1]", "[0:0:0:0:0:0:0:0]", "[0:0:0:0:0:0:0:1]", "[::ffff:127.0.0.1]"}) {
            assertEquals(Set.of(1002), ForwardLoopGuard.forbiddenPorts(host + ":1002", node(), 1L), host);
        }
    }

    @Test
    void matchesConfiguredNodeAddressesAndSpecificListenAddresses() {
        String targets = " 192.0.2.10:1002, [2001:0db8:0:0:0:0:0:10]:1003, ENTRY.EXAMPLE.:1004,192.0.2.11:1005,198.51.100.20:1006 ";
        assertEquals(Set.of(1002, 1003, 1004, 1005),
                ForwardLoopGuard.forbiddenPorts(targets, node(), 2L, "192.0.2.11", "0.0.0.0"));
    }

    @Test
    void loopbackBelongsToExitNodeRatherThanIngressOfTwoNodeTunnel() {
        assertTrue(ForwardLoopGuard.forbiddenPorts("127.0.0.1:1002,[::]:1003", node(), 2L).isEmpty());
        assertEquals(Set.of(1002), ForwardLoopGuard.forbiddenPorts("192.0.2.10:1002", node(), 2L));
    }

    @Test
    void unrelatedTargetsAndDifferentPortsDoNotProduceFalseReservations() {
        assertEquals(Set.of(8000), ForwardLoopGuard.forbiddenPorts("127.0.0.1:8000,198.51.100.20:1002,other.example:1003", node(), 1L));
        assertTrue(ForwardLoopGuard.forbiddenPorts(null, node(), 1L).isEmpty());
        assertTrue(ForwardLoopGuard.forbiddenPorts("", node(), 1L).isEmpty());
    }
}
