package com.admin.service.impl;

import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.lang.R;
import com.admin.common.utils.UdpEchoKeyUtil;
import com.admin.common.utils.WebSocketServer;
import com.admin.controller.ForwardController;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.mapper.ForwardMapper;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.socket.WebSocketSession;

import javax.validation.Validation;
import javax.validation.Validator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForwardUdpEchoDiagnosisTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(com.admin.common.utils.JwtUtil.class, "SECRET_KEY", "test-secret-key");
        ReflectionTestUtils.setField(UdpEchoKeyUtil.class, "SECRET_KEY", "test-secret-key");
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void dtoValidationAcceptsUdpEchoAndRejectsUnknownMode() {
        ForwardDto create = new ForwardDto();
        create.setName("test");
        create.setTunnelId(1);
        create.setRemoteAddr("1.2.3.4:8080");
        create.setProbeMode("udp_echo");
        create.setUdpEchoPort(29302);
        assertTrue(validator.validate(create).isEmpty());

        create.setUdpEchoPort(0);
        assertFalse(validator.validate(create).isEmpty());
        create.setUdpEchoPort(65536);
        assertFalse(validator.validate(create).isEmpty());
        create.setUdpEchoPort(null);

        create.setProbeMode("invalid");
        assertFalse(validator.validate(create).isEmpty());

        ForwardUpdateDto update = new ForwardUpdateDto();
        update.setId(1L);
        update.setUserId(1);
        update.setName("test");
        update.setTunnelId(1);
        update.setRemoteAddr("1.2.3.4:8080");
        update.setProbeMode("udp_echo");
        update.setUdpEchoPort(29302);
        assertTrue(validator.validate(update).isEmpty());

        update.setUdpEchoPort(70000);
        assertFalse(validator.validate(update).isEmpty());
        update.setUdpEchoPort(null);

        update.setProbeMode("invalid");
        assertFalse(validator.validate(update).isEmpty());
    }

    @Test
    void supportsForwardUdpEchoChecksSessionCapability() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of("forwardUdpEcho", true, "udpEchoAuth", true));

        @SuppressWarnings("unchecked")
        Map<Long, WebSocketSession> sessions = (Map<Long, WebSocketSession>) ReflectionTestUtils.getField(WebSocketServer.class, "nodeSessions");
        assertNotNull(sessions);
        sessions.put(9991L, session);
        try {
            assertTrue(WebSocketServer.supportsForwardUdpEcho(9991L));
            assertTrue(WebSocketServer.supportsUdpEchoAuth(9991L));
            assertFalse(WebSocketServer.supportsForwardUdpEcho(9992L));
            assertFalse(WebSocketServer.supportsUdpEchoAuth(9992L));
        } finally {
            sessions.remove(9991L);
        }
    }

    @Test
    void udpEchoKeyDerivationAndEndpointReturnDeterministic64HexKey() {
        String keyA1 = UdpEchoKeyUtil.deriveKey("secret-alpha");
        String keyA2 = UdpEchoKeyUtil.deriveKey("secret-alpha");
        String keyB = UdpEchoKeyUtil.deriveKey("secret-beta");
        assertEquals(keyA1, keyA2);
        assertNotEquals(keyA1, keyB);
        assertTrue(keyA1.matches("^[0-9a-f]{64}$"));

        ForwardController controller = new ForwardController();
        R res = controller.udpEchoKey();
        assertEquals(0, res.getCode());
        @SuppressWarnings("unchecked")
        Map<String, String> data = (Map<String, String>) res.getData();
        assertEquals(UdpEchoKeyUtil.deriveKey("test-secret-key"), data.get("key"));
    }

    @Test
    void diagnoseForwardUdpEchoDeduplicatesHostsAndReportsUpgradeNeed() {
        ForwardServiceImpl service = new ForwardServiceImpl();
        ForwardMapper mapper = mock(ForwardMapper.class);
        TunnelService tunnelService = mock(TunnelService.class);
        NodeService nodeService = mock(NodeService.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(service, "nodeService", nodeService);

        Forward forward = new Forward();
        forward.setId(10L);
        forward.setName("udp-echo-test");
        forward.setTunnelId(20);
        forward.setUserId(1);
        forward.setStatus(1);
        forward.setProbeMode("udp_echo");
        forward.setRemoteAddr("198.51.100.10:8080,198.51.100.10:9090,[2001:db8::1]:443");

        Tunnel tunnel = new Tunnel();
        tunnel.setId(20L);
        tunnel.setType(1);
        tunnel.setInNodeId(1L);

        Node inNode = new Node();
        inNode.setId(1L);
        inNode.setName("entry-1");

        when(mapper.selectById(10L)).thenReturn(forward);
        when(tunnelService.getById(20)).thenReturn(tunnel);
        when(nodeService.getNodeById(1L)).thenReturn(inNode);

        // Provide admin JWT in Authorization header so getCurrentUserInfo() succeeds
        String token = com.admin.common.utils.JwtUtil.generateToken(new com.admin.entity.User() {{
            setId(1L);
            setUser("admin_user");
            setRoleId(0);
        }});
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        R response = service.diagnoseForward(10L);
        assertEquals(0, response.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) response.getData();
        assertEquals("udp_echo", report.get("probeMode"));
        @SuppressWarnings("unchecked")
        List<ForwardServiceImpl.DiagnosisResult> results = (List<ForwardServiceImpl.DiagnosisResult>) report.get("results");
        // Two distinct hosts: 198.51.100.10 and 2001:db8::1
        assertEquals(2, results.size());
        assertEquals("198.51.100.10", results.get(0).getTargetIp());
        assertEquals(19302, results.get(0).getTargetPort());
        assertEquals("入口 → 目标（UDP 回显）", results.get(0).getDescription());
        assertFalse(results.get(0).isSuccess());
        assertTrue(results.get(0).getMessage().contains("UDP 回显"));

        assertEquals("2001:db8::1", results.get(1).getTargetIp());
        assertEquals(19302, results.get(1).getTargetPort());

        // Custom udpEchoPort
        forward.setUdpEchoPort(29302);
        R customResponse = service.diagnoseForward(10L);
        assertEquals(0, customResponse.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> customReport = (Map<String, Object>) customResponse.getData();
        @SuppressWarnings("unchecked")
        List<ForwardServiceImpl.DiagnosisResult> customResults =
                (List<ForwardServiceImpl.DiagnosisResult>) customReport.get("results");
        assertEquals(29302, customResults.get(0).getTargetPort());
        assertEquals(29302, customResults.get(1).getTargetPort());
    }

    @Test
    void diagnoseTunnelForwardUdpEchoIncludesTunnelLinkAndTargetEcho() {
        ForwardServiceImpl service = new ForwardServiceImpl();
        ForwardMapper mapper = mock(ForwardMapper.class);
        TunnelService tunnelService = mock(TunnelService.class);
        NodeService nodeService = mock(NodeService.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(service, "nodeService", nodeService);

        Forward forward = new Forward();
        forward.setId(11L);
        forward.setName("tunnel-udp-echo-test");
        forward.setTunnelId(21);
        forward.setUserId(1);
        forward.setStatus(1);
        forward.setOutPort(30001);
        forward.setProbeMode("udp_echo");
        forward.setRemoteAddr("198.51.100.20:51820");

        Tunnel tunnel = new Tunnel();
        tunnel.setId(21L);
        tunnel.setType(2);
        tunnel.setInNodeId(1L);
        tunnel.setOutNodeId(2L);
        tunnel.setOutIp("192.0.2.2");

        Node inNode = new Node();
        inNode.setId(1L);
        inNode.setName("entry-1");
        Node outNode = new Node();
        outNode.setId(2L);
        outNode.setName("exit-1");

        when(mapper.selectById(11L)).thenReturn(forward);
        when(tunnelService.getById(21)).thenReturn(tunnel);
        when(nodeService.getNodeById(1L)).thenReturn(inNode);
        when(nodeService.getNodeById(2L)).thenReturn(outNode);

        String token = com.admin.common.utils.JwtUtil.generateToken(new com.admin.entity.User() {{
            setId(1L);
            setUser("admin_user");
            setRoleId(0);
        }});
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        R response = service.diagnoseForward(11L);
        assertEquals(0, response.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) response.getData();
        assertEquals("udp_echo", report.get("probeMode"));
        assertEquals("隧道转发", report.get("tunnelType"));
        @SuppressWarnings("unchecked")
        List<ForwardServiceImpl.DiagnosisResult> results = (List<ForwardServiceImpl.DiagnosisResult>) report.get("results");
        assertEquals(2, results.size());
        assertEquals("入口 → 出口（隧道 UDP 回显）", results.get(0).getDescription());
        assertEquals("出口 → 目标（UDP 回显）", results.get(1).getDescription());
        assertEquals("exit-1", results.get(1).getNodeName());
        assertEquals("198.51.100.20", results.get(1).getTargetIp());
        assertEquals(19302, results.get(1).getTargetPort());
    }
}
