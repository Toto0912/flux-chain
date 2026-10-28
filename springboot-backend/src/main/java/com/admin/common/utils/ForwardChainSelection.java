package com.admin.common.utils;

import java.util.List;

public final class ForwardChainSelection {
    private ForwardChainSelection() {}

    public static int choosePath(List<Double> latencyMs, List<Boolean> available, int currentIndex, double switchThresholdMs) {
        if (latencyMs == null || available == null || latencyMs.size() != available.size() || latencyMs.isEmpty()) return currentIndex;
        int best = -1;
        double bestLatency = Double.POSITIVE_INFINITY;
        for (int i = 0; i < latencyMs.size(); i++) {
            Double latency = latencyMs.get(i);
            if (Boolean.TRUE.equals(available.get(i)) && latency != null && Double.isFinite(latency) && latency < bestLatency) {
                best = i;
                bestLatency = latency;
            }
        }
        if (best < 0) return currentIndex;
        if (currentIndex < 0 || currentIndex >= latencyMs.size()) return best;
        if (currentIndex == best) return currentIndex;
        if (!Boolean.TRUE.equals(available.get(currentIndex)) || latencyMs.get(currentIndex) == null) return best;
        return latencyMs.get(currentIndex) - bestLatency > switchThresholdMs ? best : currentIndex;
    }

    public static long countCombinations(List<Integer> candidateCounts) {
        if (candidateCounts == null || candidateCounts.isEmpty()) return 0;
        long total = 1;
        for (Integer count : candidateCounts) {
            if (count == null || count <= 0) return 0;
            total = Math.multiplyExact(total, count.longValue());
        }
        return total;
    }
}
