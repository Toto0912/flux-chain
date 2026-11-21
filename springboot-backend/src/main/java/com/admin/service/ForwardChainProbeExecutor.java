package com.admin.service;

import com.admin.common.dto.ForwardChainProbeStatus;
import com.admin.common.dto.ForwardChainProbeStatus.State;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Bounded workers with one queued/running probe cycle per chain. */
@Slf4j
@Component
public class ForwardChainProbeExecutor implements AutoCloseable {
    private static final int CHAIN_CONCURRENCY = 2;
    private static final int PATH_CONCURRENCY = 16;
    private static final int CHAIN_QUEUE_CAPACITY = 32;
    private static final int MAX_MANUAL_STARTS = 2;
    private static final ForwardChainProbeStatus QUEUED = new ForwardChainProbeStatus(State.QUEUED, "手动探测排队中");
    private static final ForwardChainProbeStatus RUNNING = new ForwardChainProbeStatus(State.RUNNING, "手动探测中");
    private static final ForwardChainProbeStatus BUSY = new ForwardChainProbeStatus(State.BUSY, "探测队列已满，请稍后重试");

    private final ExecutorService chainWorkers;
    private final ExecutorService pathWorkers;
    private final Deque<Cycle> automaticQueue = new ArrayDeque<>();
    private final Deque<Cycle> manualQueue = new ArrayDeque<>();
    private final Map<Long, Cycle> cycles = new HashMap<>();
    private final Map<Long, ForwardChainProbeStatus> manualStatuses = new HashMap<>();
    private final int chainConcurrency;
    private final int pathConcurrency;
    private final int queueCapacity;
    private int runningCount;
    private int manualStarts;
    private boolean closed;

    public ForwardChainProbeExecutor() {
        this(CHAIN_CONCURRENCY, PATH_CONCURRENCY, CHAIN_QUEUE_CAPACITY);
    }

    ForwardChainProbeExecutor(int chainConcurrency, int pathConcurrency, int queueCapacity) {
        this.chainConcurrency = chainConcurrency;
        this.pathConcurrency = pathConcurrency;
        this.queueCapacity = queueCapacity;
        this.chainWorkers = new ThreadPoolExecutor(chainConcurrency, chainConcurrency, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(chainConcurrency));
        this.pathWorkers = new ThreadPoolExecutor(pathConcurrency, pathConcurrency, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(chainConcurrency * pathConcurrency));
    }

    public synchronized boolean submitIfIdle(Long chainId, Supplier<ForwardChainProbeStatus> probe) {
        if (closed || cycles.containsKey(chainId) || !hasSpace(false)) return false;
        Cycle cycle = new Cycle(chainId, probe);
        cycles.put(chainId, cycle);
        automaticQueue.addLast(cycle);
        dispatch();
        return true;
    }

    public synchronized ForwardChainProbeStatus submitManual(Long chainId, Supplier<ForwardChainProbeStatus> probe) {
        if (closed) return BUSY;
        Cycle cycle = cycles.get(chainId);
        if (cycle == null) {
            if (!hasSpace(true)) return BUSY;
            cycle = new Cycle(chainId, probe);
            cycles.put(chainId, cycle);
            manualQueue.addLast(cycle);
        } else if (!cycle.manual && !cycle.started) {
            automaticQueue.remove(cycle);
            manualQueue.addLast(cycle);
        }
        cycle.manual = true;
        manualStatuses.put(chainId, cycle.started ? RUNNING : QUEUED);
        dispatch();
        return manualStatuses.get(chainId);
    }

    public synchronized Map<Long, ForwardChainProbeStatus> manualStatusSnapshot() {
        return Map.copyOf(manualStatuses);
    }

    public synchronized void cancelQueued(Long chainId) {
        Cycle cycle = removeQueued(chainId);
        if (cycle != null && cycle.manual) {
            manualStatuses.put(chainId, new ForwardChainProbeStatus(State.CANCELLED, "转发链已暂停，本轮探测已取消"));
        }
    }

    public synchronized void forgetChain(Long chainId) {
        removeQueued(chainId);
        Cycle running = cycles.get(chainId);
        if (running != null) running.manual = false;
        manualStatuses.remove(chainId);
    }

    private Cycle removeQueued(Long chainId) {
        Cycle cycle = cycles.get(chainId);
        if (cycle == null || cycle.started) return null;
        (cycle.manual ? manualQueue : automaticQueue).remove(cycle);
        cycles.remove(chainId);
        return cycle;
    }

    private boolean hasSpace(boolean manual) {
        if (runningCount < chainConcurrency) return true;
        return manualQueue.size() + automaticQueue.size() < queueCapacity
                && (manual || automaticQueue.size() < queueCapacity - 1);
    }

    private void dispatch() {
        while (!closed && runningCount < chainConcurrency && (!automaticQueue.isEmpty() || !manualQueue.isEmpty())) {
            Cycle cycle;
            if (!manualQueue.isEmpty() && (automaticQueue.isEmpty() || manualStarts < MAX_MANUAL_STARTS)) {
                cycle = manualQueue.removeFirst();
                manualStarts = automaticQueue.isEmpty() ? 0 : manualStarts + 1;
            } else {
                cycle = automaticQueue.removeFirst();
                manualStarts = 0;
            }
            cycle.started = true;
            runningCount++;
            if (cycle.manual) manualStatuses.put(cycle.chainId, RUNNING);
            chainWorkers.execute(() -> run(cycle));
        }
    }

    private void run(Cycle cycle) {
        ForwardChainProbeStatus status = new ForwardChainProbeStatus(State.FAILED, "探测未完成，请稍后重试");
        try {
            status = cycle.probe.get();
        } catch (Exception error) {
            log.warn("转发链 {} 探测失败，将在下一周期重试", cycle.chainId, error);
        } finally {
            finish(cycle, status);
        }
    }

    private synchronized void finish(Cycle cycle, ForwardChainProbeStatus status) {
        if (closed) return;
        cycles.remove(cycle.chainId);
        runningCount--;
        if (cycle.manual) manualStatuses.put(cycle.chainId, status);
        dispatch();
    }

    private static final class Cycle {
        private final Long chainId;
        private final Supplier<ForwardChainProbeStatus> probe;
        private boolean manual;
        private boolean started;

        private Cycle(Long chainId, Supplier<ForwardChainProbeStatus> probe) {
            this.chainId = chainId;
            this.probe = probe;
        }
    }

    public void probePaths(int count, IntConsumer probe) throws InterruptedException {
        CompletionService<Void> completed = new ExecutorCompletionService<>(pathWorkers);
        Set<Future<Void>> pending = new HashSet<>();
        int next = 0;
        try {
            while (next < count || !pending.isEmpty()) {
                while (next < count && pending.size() < pathConcurrency) {
                    final int index = next++;
                    pending.add(completed.submit(() -> { probe.accept(index); return null; }));
                }
                Future<Void> finished = completed.take();
                pending.remove(finished);
                try {
                    finished.get();
                } catch (ExecutionException e) {
                    // One bad path must not prevent the remaining paths being tested.
                    log.warn("转发链路径探测异常", e.getCause());
                }
            }
        } finally {
            for (Future<Void> future : pending) future.cancel(true);
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        closed = true;
        automaticQueue.clear();
        manualQueue.clear();
        cycles.clear();
        manualStatuses.clear();
        chainWorkers.shutdownNow();
        pathWorkers.shutdownNow();
    }
}
