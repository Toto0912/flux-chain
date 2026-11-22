package com.admin.common.utils;

import com.admin.common.dto.ForwardTargetHealthConfig;
import com.admin.entity.Tunnel;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class OrdinaryForwardUdpConfigTest {
    @Test
    void healthLoadCountsFixedEchoOncePerHostAndGoogleOncePerForward() {
        String targets = "a:1234,a:5678,[2001:db8::1]:1234";
        ForwardTargetHealthConfig settings = new ForwardTargetHealthConfig();
        settings.setTcp(true);
        settings.setUdpMode(ForwardTargetHealthConfig.FIXED_ECHO);
        settings.setIntervalSeconds(5);
        settings.setTimeoutSeconds(2);
        assertEquals(2000L, settings.probeLoad(targets));

        settings.setTcp(false);
        settings.setUdpMode(ForwardTargetHealthConfig.GOOGLE_STUN);
        assertEquals(400L, settings.probeLoad(targets));
        settings.setIntervalSeconds(300);
        assertEquals(125L, settings.probeLoad(targets));
    }

    @Test
    void googleStunIgnoresPreviouslyStoredTargetAddresses() {
        String stored = """
                {"enabled":true,"tcp":false,"udpMode":"stun","udpTargets":{"a:1234":"old-probe:5678"}}
                """;
        String destinations = "a:1234,b:1234";
        ForwardTargetHealthConfig input = JSON.parseObject(stored, ForwardTargetHealthConfig.class);
        ForwardTargetHealthConfig updated = ForwardTargetHealthConfig.validate(input, destinations, "latency");
        assertTrue(updated.getUdpTargets().isEmpty());
        assertTrue(ForwardTargetHealthConfig.read(stored).getUdpTargets().isEmpty());
        JSONObject nodeSettings = input.nodeSettings(ForwardTargetHealthConfig.targetList(destinations));
        assertEquals("stun", nodeSettings.getString("udpMode"));
        assertFalse(nodeSettings.containsKey("udpTargets"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void directAndTunnelIngressAllowWholeUdpDatagramsAndKeepExistingSettings(int tunnelType) {
        Tunnel tunnel = new Tunnel();
        tunnel.setTcpListenAddr("0.0.0.0"); tunnel.setUdpListenAddr("[::]");
        JSONObject service = ReflectionTestUtils.invokeMethod(GostUtil.class, "createServiceConfig",
                "forward_1", 28100, 2, "target:28777", "udp", tunnelType, tunnel, "fifo", "eth0", "");
        assertNotNull(service);
        assertEquals("[::]:28100", service.getString("addr"));
        assertEquals("2", service.getString("limiter"));
        assertEquals("eth0", service.getJSONObject("metadata").getString("interface"));
        JSONObject metadata = service.getJSONObject("listener").getJSONObject("metadata");
        assertEquals("65535", metadata.get("readBufferSize"));
        assertTrue(metadata.getBooleanValue("keepAlive"));
        if (tunnelType == 2) assertEquals("forward_1_chains", service.getJSONObject("handler").getString("chain"));
        else assertEquals("target:28777", service.getJSONObject("forwarder").getJSONArray("nodes").getJSONObject(0).getString("addr"));
    }
}
