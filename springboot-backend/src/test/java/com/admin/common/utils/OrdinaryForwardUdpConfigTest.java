package com.admin.common.utils;

import com.admin.entity.Tunnel;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class OrdinaryForwardUdpConfigTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void directAndTunnelIngressAllowWholeUdpDatagramsAndKeepExistingSettings(int tunnelType) {
        Tunnel tunnel = new Tunnel();
        tunnel.setTcpListenAddr("0.0.0.0"); tunnel.setUdpListenAddr("[::]");
        JSONObject service = ReflectionTestUtils.invokeMethod(GostUtil.class, "createServiceConfig",
                "forward_1", 28100, 2, "target:28777", "udp", tunnelType, tunnel, "fifo", "eth0");
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
