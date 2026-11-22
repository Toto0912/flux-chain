package com.admin.service.impl;

import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.controller.ForwardController;
import com.admin.entity.Forward;
import com.admin.entity.Node;
import com.admin.entity.Tunnel;
import com.admin.entity.User;
import com.admin.mapper.ForwardMapper;
import com.admin.service.ForwardService;
import com.admin.service.NodeService;
import com.admin.service.TunnelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForwardDiagnosisLimitTest {

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(JwtUtil.class, "SECRET_KEY", "test-secret-key");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void regularUserEnforcesFiveSecondCooldownAndAdminBypassesCooldown() {
        AtomicLong now = new AtomicLong(100_000L);
        ForwardServiceImpl service = createService(now::get, 2L, "198.51.100.10:8080");

        authenticate(2L, "normal_user", 1);
        R first = service.diagnoseForward(10L);
        assertEquals(0, first.getCode());

        now.addAndGet(2_000L);
        R blocked = service.diagnoseForward(10L);
        assertNotEquals(0, blocked.getCode());
        assertEquals("诊断操作过于频繁，请 3 秒后再试", blocked.getMsg());

        now.addAndGet(3_000L);
        R allowedAgain = service.diagnoseForward(10L);
        assertEquals(0, allowedAgain.getCode());

        // Admin is not subject to per-user cooldown
        authenticate(1L, "admin_user", 0);
        assertEquals(0, service.diagnoseForward(10L).getCode());
        assertEquals(0, service.diagnoseForward(10L).getCode());
    }

    @Test
    void regularUserCannotRunConcurrentDiagnoses() {
        AtomicLong now = new AtomicLong(100_000L);
        ForwardServiceImpl service = createService(now::get, 2L, "198.51.100.10:8080");
        authenticate(2L, "normal_user", 1);

        @SuppressWarnings("unchecked")
        Set<Integer> active = (Set<Integer>) ReflectionTestUtils.getField(service, "activeUserDiagnoses");
        assertNotNull(active);
        active.add(2);
        try {
            R concurrent = service.diagnoseForward(10L);
            assertNotEquals(0, concurrent.getCode());
            assertTrue(concurrent.getMsg().contains("正在进行的诊断任务"));
        } finally {
            active.remove(2);
        }
    }

    @Test
    void diagnosisTruncatesAfterTwentySecondsWithWarningMessage() {
        AtomicLong now = new AtomicLong(100_000L);
        // Advance clock past 20s after the first target check
        LongSupplier advancingClock = new LongSupplier() {
            private int calls = 0;

            @Override
            public long getAsLong() {
                calls++;
                // 1: deadline calculation, 2: first target check (within budget), 3+: past 20s deadline
                if (calls >= 3) {
                    return now.get() + 21_000L;
                }
                return now.get();
            }
        };
        ForwardServiceImpl service = createService(advancingClock, 1L,
                "198.51.100.10:8080,198.51.100.11:8080,198.51.100.12:8080");
        authenticate(1L, "admin_user", 0);

        R response = service.diagnoseForward(10L);
        assertEquals(0, response.getCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) response.getData();
        assertEquals(Boolean.TRUE, report.get("truncated"));
        String warning = (String) report.get("warningMessage");
        assertNotNull(warning);
        assertTrue(warning.contains("20 秒"));
        assertTrue(warning.contains("剩余 2 项"));
        assertTrue(warning.contains("请稍后再试"));

        @SuppressWarnings("unchecked")
        List<ForwardServiceImpl.DiagnosisResult> results = (List<ForwardServiceImpl.DiagnosisResult>) report.get("results");
        assertEquals(1, results.size());
    }

    @Test
    void controllerRejectsDiagnosisWhenGlobalSemaphoreIsExhausted() throws Exception {
        ForwardController controller = new ForwardController();
        ForwardService forwardService = mock(ForwardService.class);
        ReflectionTestUtils.setField(controller, "forwardService", forwardService);

        Semaphore permits = (Semaphore) ReflectionTestUtils.getField(controller, "diagnosisRequests");
        assertNotNull(permits);
        permits.acquire(8);
        try {
            R busy = controller.diagnoseForward(Map.of("forwardId", 10L));
            assertNotEquals(0, busy.getCode());
            assertTrue(busy.getMsg().contains("系统繁忙"));
            assertTrue(busy.getMsg().contains("请稍后再试"));
            verify(forwardService, never()).diagnoseForward(any());
        } finally {
            permits.release(8);
        }
    }

    private ForwardServiceImpl createService(LongSupplier clock, long ownerUserId, String remoteAddr) {
        ForwardServiceImpl service = new ForwardServiceImpl();
        ForwardMapper mapper = mock(ForwardMapper.class);
        TunnelService tunnelService = mock(TunnelService.class);
        NodeService nodeService = mock(NodeService.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "tunnelService", tunnelService);
        ReflectionTestUtils.setField(service, "nodeService", nodeService);
        ReflectionTestUtils.setField(service, "clock", clock);

        Forward forward = new Forward();
        forward.setId(10L);
        forward.setName("limit-test");
        forward.setTunnelId(20);
        forward.setUserId((int) ownerUserId);
        forward.setStatus(1);
        forward.setProbeMode("udp_echo");
        forward.setRemoteAddr(remoteAddr);

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
        return service;
    }

    private void authenticate(long userId, String username, int roleId) {
        User user = new User();
        user.setId(userId);
        user.setUser(username);
        user.setRoleId(roleId);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", JwtUtil.generateToken(user));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
