package com.admin.config;

import com.admin.controller.FlowController;
import com.admin.entity.Node;
import com.admin.service.NodeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NodeSecretHeaderAuthTest {

    @Test
    void webSocketHandshakeAcceptsHeaderAndLegacyQueryAndRejectsMissingSecret() throws Exception {
        WebSocketInterceptor interceptor = new WebSocketInterceptor();
        NodeService nodeService = mock(NodeService.class);
        ReflectionTestUtils.setField(interceptor, "nodeService", nodeService);

        Node node = new Node();
        node.setId(42L);
        when(nodeService.getOne(any())).thenReturn(node);

        MockHttpServletRequest headerReq = new MockHttpServletRequest();
        headerReq.setParameter("type", "1");
        headerReq.setParameter("version", "1.1.6");
        headerReq.addHeader("X-Node-Secret", "header-secret");
        Map<String, Object> headerAttrs = new HashMap<>();
        assertTrue(interceptor.beforeHandshake(new ServletServerHttpRequest(headerReq), null, mock(WebSocketHandler.class), headerAttrs));
        assertEquals("header-secret", headerAttrs.get("nodeSecret"));
        assertEquals(42L, headerAttrs.get("id"));

        MockHttpServletRequest queryReq = new MockHttpServletRequest();
        queryReq.setParameter("type", "1");
        queryReq.setParameter("secret", "query-secret");
        Map<String, Object> queryAttrs = new HashMap<>();
        assertTrue(interceptor.beforeHandshake(new ServletServerHttpRequest(queryReq), null, mock(WebSocketHandler.class), queryAttrs));
        assertEquals("query-secret", queryAttrs.get("nodeSecret"));

        MockHttpServletRequest missingReq = new MockHttpServletRequest();
        missingReq.setParameter("type", "1");
        assertFalse(interceptor.beforeHandshake(new ServletServerHttpRequest(missingReq), null, mock(WebSocketHandler.class), new HashMap<>()));
    }

    @Test
    void flowControllerAcceptsHeaderSecretAndLegacyQuerySecret() {
        FlowController controller = new FlowController();
        NodeService nodeService = mock(NodeService.class);
        ReflectionTestUtils.setField(controller, "nodeService", nodeService);

        when(nodeService.count(any())).thenReturn(1);
        assertEquals("ok", controller.uploadFlowData("{\"n\":\"web_api\",\"u\":0,\"d\":0}", null, "header-secret"));
        assertEquals("ok", controller.uploadFlowData("{\"n\":\"web_api\",\"u\":0,\"d\":0}", "query-secret", null));
        verify(nodeService, times(2)).count(any());

        assertEquals("ok", controller.uploadFlowData("{\"n\":\"web_api\",\"u\":0,\"d\":0}", null, null));
        verify(nodeService, times(2)).count(any());

        assertEquals("ok", controller.config("{}", null, null));
        verify(nodeService, never()).getOne(any());
    }
}
