package com.admin.service;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ForwardChainProbeExecutorTest {
    private void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    @Test
    void repeatedTicksDoNotOverlapOrQueueAnotherCycleForTheSameChain() throws Exception {
        try (ForwardChainProbeExecutor executor = new ForwardChainProbeExecutor()) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1), cycleCompleted = new CountDownLatch(1);
            AtomicInteger invocations = new AtomicInteger();
            try {
                assertTrue(executor.submitIfIdle(1L, () -> {
                    invocations.incrementAndGet(); entered.countDown(); await(release); cycleCompleted.countDown();
                }));
                await(entered);
                for (int tick = 0; tick < 100; tick++) assertFalse(executor.submitIfIdle(1L, invocations::incrementAndGet));
                assertTrue(executor.submitIfIdle(2L, done::countDown));
                await(done); // Other chains can still run while this one is slow.
                assertEquals(1, invocations.get());
            } finally { release.countDown(); await(cycleCompleted); }
        }
    }

    @Test
    void queuedChainsAreDeduplicatedAndFullQueuesCanRetry() throws Exception {
        ThreadPoolExecutor chains = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        try (ForwardChainProbeExecutor executor = new ForwardChainProbeExecutor(chains, Executors.newSingleThreadExecutor(), 1)) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
            try {
                assertTrue(executor.submitIfIdle(1L, () -> { started.countDown(); await(release); }));
                await(started);
                assertTrue(executor.submitIfIdle(2L, () -> {}));
                assertFalse(executor.submitIfIdle(2L, () -> fail("Duplicate queued cycle")));
                assertFalse(executor.submitIfIdle(3L, () -> {}));
                release.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!executor.submitIfIdle(3L, done::countDown)) {
                    assertTrue(System.nanoTime() < deadline); Thread.yield();
                }
                await(done);
            } finally { release.countDown(); }
        }
    }

    @Test
    void all125PathsFinishWithBoundedOutstandingWorkEvenWhenOnePathFails() throws Exception {
        ThreadPoolExecutor paths = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
        try (ForwardChainProbeExecutor executor = new ForwardChainProbeExecutor(Executors.newSingleThreadExecutor(), paths, 2)) {
            Set<Integer> visited = ConcurrentHashMap.newKeySet();
            AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
            executor.probePaths(125, index -> {
                int current = active.incrementAndGet(); peak.accumulateAndGet(current, Math::max);
                try {
                    assertTrue(paths.getQueue().size() <= 2);
                    assertTrue(visited.add(index));
                    if (index == 5) throw new IllegalStateException("one failed path");
                } finally { active.decrementAndGet(); }
            });
            assertEquals(125, visited.size());
            assertTrue(peak.get() <= 2);
            assertTrue(IntStream.range(0, 125).allMatch(visited::contains));
        }
    }

    @Test
    void failedCyclesReleaseTheirReservationAndShutdownRejectsNewWork() throws Exception {
        ExecutorService chains = Executors.newSingleThreadExecutor();
        ForwardChainProbeExecutor executor = new ForwardChainProbeExecutor(chains, Executors.newSingleThreadExecutor(), 1);
        try {
            assertTrue(executor.submitIfIdle(1L, () -> { throw new IllegalStateException("cycle failed"); }));
            chains.submit(() -> {}).get(5, TimeUnit.SECONDS); // First cycle's finally has run.
            CountDownLatch completed = new CountDownLatch(1);
            assertTrue(executor.submitIfIdle(1L, completed::countDown));
            await(completed);
        } finally { executor.close(); }
        assertFalse(executor.submitIfIdle(1L, () -> fail("closed executor")));
    }
}
