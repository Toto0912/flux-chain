package com.admin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("forward_chain")
public class ForwardChain extends BaseEntity {
    private String name;
    private Long inNodeId;
    private Integer inPort;
    private String targetHost;
    private Integer targetPort;
    private String protocol;
    private String hopsJson;
    private String pathsJson;
    private String endpointPortsJson;
    private Integer probeIntervalMs;
    private Integer probeTimeoutMs;
    private Integer switchThresholdMs;
    private Integer selectedPathIndex;
    private Long lastProbeTime;
    private Long inFlow;
    private Long outFlow;
}
