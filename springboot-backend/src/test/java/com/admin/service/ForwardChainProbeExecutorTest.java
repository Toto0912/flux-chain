package com.admin.service;

import com.admin.common.dto.ForwardChainProbeStatus;
import com.admin.common.dto.ForwardChainProbeStatus.State;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ForwardChainProbeExecutorTest {
    private static final ForwardChainProbeStatus KEPT = new ForwardChainProbeStatus(State.KEPT, "保留当前路径");

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
                    return KEPT;
                }));
                await(entered);
                for (int tick = 0; tick < 100; tick++) {
                    assertFalse(executor.submitIfIdle(1L, () -> fail("重复定时探测")));
                    assertEquals(State.RUNNING, executor.submitManual(1L, () -> fail("重复手动探测")).state());
                }
                assertTrue(executor.submitIfIdle(2L, () -> { done.countDown(); return KEPT; }));
                await(done); // Other chains can still run while this one is slow.
                assertEquals(1, invocations.get());
            } finally { release.countDown(); await(cycleCompleted); }
        }
    }

    @Test
    void reservedManualSlotAndPromotionWorkEvenWhenTheQueueIsFull() throws Exception {
        try (ForwardChainProbeExecutor executor = new ForwardChainProbeExecutor(1, 2, 3)) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            BlockingQueue<Long> starts = new LinkedBlockingQueue<>();
            try {
                assertTrue(executor.submitIfIdle(1L, () -> { started.countDown(); await(release); return KEPT; }));
                await(started);
                assertTrue(executor.submitIfIdle(2L, () -> { starts.add(2L); return KEPT; }));
                assertTrue(executor.submitIfIdle(3L, () -> { starts.add(3L); return KEPT; }));
                assertFalse(executor.submitIfIdle(2L, () -> fail("Duplicate queued cycle")));
                assertFalse(executor.submitIfIdle(4L, () -> fail("Automatic use of reserved manual slot")));
                assertEquals(State.QUEUED, executor.submitManual(5L, () -> { starts.add(5L); return KEPT; }).state());
                assertEquals(State.BUSY, executor.submitManual(6L, () -> fail("Queue was full")).state());
                for (int click = 0; click < 100; click++) {
                    assertEquals(State.QUEUED, executor.submitManual(3L, () -> fail("Queued work was replaced")).state());
                }
                release.countDown();
                for (long id : List.of(5L, 3L, 2L)) {
                    assertEquals(id, starts.poll(5, TimeUnit.SECONDS));
                }
                assertNotEquals(State.BUSY, executor.submitManual(6L, () -> KEPT).state());
            } finally { release.countDown(); }
        }
    }

    @Test
    void oldestAutomaticRoundStartsAfterTwoManualRounds() throws Exception {
        try (ForwardChainProbeExecutor executor = new ForwardChainProbeExecutor(1, 2, 8)) {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            BlockingQueue<Long> starts = new LinkedBlockingQueue<>();
            try {
                assertTrue(executor.submitIfIdle(9L, () -> { started.countDown(); await(release); return KEPT; }));
                await(started);
                for (long id : List.of(1L, 2L)) {
                    assertTrue(executor.submitIfIdle(id, () -> { starts.add(id); return KEPT; }));
                }
                for (long id : List.of(3L, 4L, 5L)) {
                    assertEquals(State.QUEUED, executor.submitManual(id, () -> { starts.add(id); return KEPT; }).state());
                }
                release.countDown();
                for (long id : List.of(3L, 4L, 1L, 5L, 2L)) {
                    assertEquals(id, starts.poll(5, TimeUnit.SECONDS));
                }
            } finally { release.countDown(); }
        }
    }

    @Test
    void all125PathsFinishWithBoundedOutstandingWorkEvenWhenOnePathFails() throws Exception {
        try (ForwardChainProbeExecutor executor = new ForwardChainProbeExecutor(1, 2, 2)) {
            Set<Integer> visited = ConcurrentHashMap.newKeySet();
            AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
            executor.probePaths(125, index -> {
                int current = active.incrementAndGet(); peak.accumulateAndGet(current, Math::max);
                try {
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
        ForwardChainProbeExecutor executor = new ForwardChainProbeExecutor(1, 1, 2);
        try {
            assertEquals(State.RUNNING, executor.submitManual(1L, () -> { throw new IllegalStateException("cycle failed"); }).state());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (executor.manualStatusSnapshot().get(1L).state() != State.FAILED) {
                assertTrue(System.nanoTime() < deadline);
                Thread.sleep(2);
            }
            CountDownLatch completed = new CountDownLatch(1);
            assertTrue(executor.submitIfIdle(1L, () -> { completed.countDown(); return KEPT; }));
            await(completed);
        } finally { executor.close(); }
        assertEquals(State.BUSY, executor.submitManual(1L, () -> fail("closed executor")).state());
    }
}
