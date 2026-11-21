package com.admin.common.dto;

public record ForwardChainProbeStatus(State state, String message) {
    public enum State {
        QUEUED, RUNNING, SWITCHED, KEPT, FAILED, CANCELLED, BUSY
    }
}
