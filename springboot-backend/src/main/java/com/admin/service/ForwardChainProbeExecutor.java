package com.admin.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/** Bounded workers with one queued/running probe cycle per chain. */
@Slf4j
@Component
public class ForwardChainProbeExecutor implements AutoCloseable {
    private static final int CHAIN_CONCURRENCY = 2;
    private static final int PATH_CONCURRENCY = 16;
    private final ExecutorService chainWorkers;
    private final ExecutorService pathWorkers;
    private final Set<Long> pendingChains = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int pathConcurrency;

    public ForwardChainProbeExecutor() {
        this(new ThreadPoolExecutor(CHAIN_CONCURRENCY, CHAIN_CONCURRENCY, 0L, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(32)),
                new ThreadPoolExecutor(PATH_CONCURRENCY, PATH_CONCURRENCY, 0L, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(CHAIN_CONCURRENCY * PATH_CONCURRENCY)), PATH_CONCURRENCY);
    }

    ForwardChainProbeExecutor(ExecutorService chainWorkers, ExecutorService pathWorkers, int pathConcurrency) {
        this.chainWorkers = chainWorkers;
        this.pathWorkers = pathWorkers;
        this.pathConcurrency = pathConcurrency;
    }

    public boolean submitIfIdle(Long chainId, Runnable cycle) {
        if (closed.get() || !pendingChains.add(chainId)) return false;
        try {
            chainWorkers.execute(() -> {
                try {
                    cycle.run();
                } catch (Exception e) {
                    log.warn("转发链 {} 探测失败，将在下一周期重试", chainId, e);
                } finally {
                    pendingChains.remove(chainId);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            // A full queue is retried by the scheduler; it must not retain the ID.
            pendingChains.remove(chainId);
            return false;
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
    public void close() {
        closed.set(true);
        chainWorkers.shutdownNow();
        pathWorkers.shutdownNow();
        pendingChains.clear();
    }
}
