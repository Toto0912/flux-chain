package com.admin.service.impl;

import com.admin.entity.ForwardChain;
import com.admin.mapper.ForwardChainMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ForwardChainProbeGenerationTest {
    private ForwardChain chain(String protocol, String ports) {
        ForwardChain chain = new ForwardChain();
        chain.setId(1L);
        chain.setStatus(1);
        chain.setProtocol(protocol);
        chain.setInNodeId(1L);
        chain.setInPort(28100);
        chain.setTargetHost("target");
        chain.setTargetPort(26000);
        chain.setHopsJson("[{\"nodeIds\":[2]}]");
        chain.setEndpointPortsJson(ports);
        return chain;
    }

    private void staleProbeDoesNotWrite(ForwardChain snapshot, ForwardChain current) {
        ForwardChainServiceImpl service = new ForwardChainServiceImpl();
        ForwardChainMapper mapper = mock(ForwardChainMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.selectById(any())).thenReturn(current);
        ReflectionTestUtils.invokeMethod(service, "applyProbeResults", snapshot);
        verify(mapper, never()).updateById(any());
    }

    @Test void protocolSwitchCannotApplyOldProbePaths() {
        staleProbeDoesNotWrite(chain("tcp", "{\"2\":28200}"), chain("udp", "{\"2\":28200}"));
    }

    @Test void redeploymentWithDifferentPortsRejectsInFlightProbes() {
        staleProbeDoesNotWrite(chain("udp", "{\"2\":28200}"), chain("udp", "{\"2\":28201}"));
    }

    @Test void changedTargetRejectsInFlightProbes() {
        ForwardChain current = chain("udp", "{\"2\":28200}");
        current.setTargetHost("different-target");
        staleProbeDoesNotWrite(chain("udp", "{\"2\":28200}"), current);
    }

    @Test void pausedChainRejectsInFlightProbes() {
        ForwardChain current = chain("udp", "{\"2\":28200}");
        current.setStatus(0);
        staleProbeDoesNotWrite(chain("udp", "{\"2\":28200}"), current);
    }

    @Test void sameDeploymentAllowsUpdatedStatisticsAndSelection() {
        ForwardChain snapshot = chain("udp", "{\"2\":28200}");
        ForwardChain current = chain("udp", "{\"2\":28200}");
        current.setInFlow(65536L);
        current.setSelectedPathIndex(3);
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(new ForwardChainServiceImpl(), "sameDeployment", snapshot, current));
    }
}
