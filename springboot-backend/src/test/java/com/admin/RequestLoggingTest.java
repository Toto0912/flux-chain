package com.admin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.admin.common.aop.LogAspect;
import com.admin.common.dto.ForwardDto;
import com.admin.common.lang.R;
import com.admin.controller.ForwardController;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestLoggingTest {
    private Logger logger;
    private Level oldLevel;
    private ListAppender<ILoggingEvent> events;

    @BeforeEach
    void setup() {
        logger = (Logger) LoggerFactory.getLogger(LogAspect.class);
        oldLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(events);
        logger.setLevel(oldLevel);
        RequestContextHolder.resetRequestAttributes();
    }

    private JoinPoint invocation(String name, Class<?>... parameters) throws Exception {
        JoinPoint point = mock(JoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getName()).thenReturn(name);
        when(signature.getMethod()).thenReturn(ForwardController.class.getMethod(name, parameters));
        when(point.getSignature()).thenReturn(signature);
        when(point.getTarget()).thenReturn(new ForwardController());
        return point;
    }

    @Test
    void keepsConfigurationAuditWithoutSerializingCompleteData() throws Throwable {
        JoinPoint point = invocation("create", ForwardDto.class);
        new LogAspect().log(point, R.ok(Map.of("secret", "DETAIL_SHOULD_NOT_BE_LOGGED")));
        assertEquals(1, events.list.size());
        assertEquals(Level.INFO, events.list.get(0).getLevel());
        assertTrue(events.list.get(0).getFormattedMessage().contains("ForwardController.create"));
        assertFalse(events.list.get(0).getFormattedMessage().contains("DETAIL_SHOULD_NOT_BE_LOGGED"));
        verify(point, never()).getArgs();
    }

    @Test
    void pollingRequestsProduceNoInfoLogAndSkipDataSerialization() throws Throwable {
        JoinPoint point = invocation("readAll");
        new LogAspect().log(point, R.ok(Map.of("result", "large-polling-response")));
        assertTrue(events.list.isEmpty());
        verify(point, never()).getArgs();
    }
}
