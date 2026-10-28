package com.admin.common.utils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ForwardChainTransportConfigTest {
    private String target(JSONObject service) {
        return service.getJSONObject("forwarder").getJSONArray("nodes").getJSONObject(0).getString("addr");
    }

    @Test
    void standardIngressKeepsBothBusinessProtocolsOnTheSelectedChain() {
        JSONArray services = GostUtil.buildChainForwardServices("fc_1", 28100, "target", 26000, "route-2", null, null);
        assertEquals(2, services.size());
        for (int i = 0; i < services.size(); i++) {
            JSONObject service = services.getJSONObject(i);
            assertEquals("route-2", service.getJSONObject("handler").getString("chain"));
            assertEquals("target:26000", target(service));
        }
    }

    @Test
    void splitIngressSendsOnlyUdpToTheFirstHopAndKeepsTcpProbeChain() {
        JSONArray services = GostUtil.buildChainForwardServices("fc_1", 28100, "target", 26000, "route-2", "hop1", 28203);
        JSONObject tcp = services.getJSONObject(0), udp = services.getJSONObject(1);
        assertEquals("fc_1_tcp", tcp.getString("name"));
        assertEquals("route-2", tcp.getJSONObject("handler").getString("chain"));
        assertEquals("target:26000", target(tcp));
        assertEquals("fc_1_udp", udp.getString("name"));
        assertFalse(udp.getJSONObject("handler").containsKey("chain"));
        assertEquals("udp", udp.getJSONObject("handler").getString("type"));
        assertEquals("hop1:28203", target(udp));
        assertEquals(tcp.getString("addr"), udp.getString("addr"));
    }

    @Test
    void changingSelectedPathChangesUdpNextHopAlongsideTcpRoute() {
        JSONArray oldServices = GostUtil.buildChainForwardServices("fc_1", 28100, "target", 26000, "route-0", "hop1-a", 28200);
        JSONArray newServices = GostUtil.buildChainForwardServices("fc_1", 28100, "target", 26000, "route-3", "hop1-b", 28301);
        assertNotEquals(target(oldServices.getJSONObject(1)), target(newServices.getJSONObject(1)));
        assertEquals("hop1-b:28301", target(newServices.getJSONObject(1)));
        assertEquals("route-3", newServices.getJSONObject(0).getJSONObject("handler").getString("chain"));
        assertEquals(oldServices.getJSONObject(1).getString("name"), newServices.getJSONObject(1).getString("name"));
    }

    @Test
    void rawUdpStageUsesDatagramsWithoutRelayOrEncryptedTransport() {
        JSONObject service = GostUtil.plainUdpStageConfig("fcx_1_suffix_udp", 28200, "hop2", 28400);
        assertEquals("udp", service.getJSONObject("handler").getString("type"));
        assertFalse(service.getJSONObject("handler").containsKey("chain"));
        JSONObject listener = service.getJSONObject("listener");
        assertEquals("udp", listener.getString("type"));
        assertTrue(listener.getJSONObject("metadata").getBooleanValue("keepalive"));
        assertEquals(65535, listener.getJSONObject("metadata").getIntValue("readBufferSize"));
        assertInstanceOf(String.class, listener.getJSONObject("metadata").get("readBufferSize"));
        assertEquals("hop2:28400", target(service));
    }

    @Test
    void udpIngressAndEgressRetainIpv6AddressFormatting() {
        JSONObject terminal = GostUtil.plainUdpStageConfig("fcx_1_exit_udp", 28400, "2001:db8::2", 26000);
        assertEquals("[2001:db8::2]:26000", target(terminal));
        JSONArray ingress = GostUtil.buildChainForwardServices("fc_1", 28100, "target", 26000, "route", "2001:db8::1", 28200);
        assertEquals("[2001:db8::1]:28200", target(ingress.getJSONObject(1)));
    }
}
