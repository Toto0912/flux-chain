package com.admin.common.dto;

import lombok.Data;
import javax.validation.constraints.*;
import java.util.List;

@Data
public class ForwardChainDto {
    private Long id;
    @NotBlank @Size(min = 2, max = 100)
    private String name;
    @NotNull @Positive
    private Long inNodeId;
    @Min(1) @Max(65535)
    private Integer inPort;
    @NotBlank
    private String targetHost;
    @NotNull @Min(1) @Max(65535)
    private Integer targetPort;
    private String protocol = "tls";
    @NotEmpty
    private List<Hop> hops;
    @Min(10000) @Max(3600000)
    private Integer probeIntervalMs = 300000;
    @Min(1000) @Max(5000)
    private Integer probeTimeoutMs = 3000;
    @Min(1)
    private Integer switchThresholdMs = 1000;

    @Data
    public static class Hop {
        @NotEmpty
        private List<@NotNull @Positive Long> nodeIds;
    }
}
