package com.admin.common.aop;

import com.admin.common.annotation.SerializeNodePorts;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.test.util.AopTestUtils;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NodePortAllocationAspectTest {
    static class Reservations implements PlatformTransactionManager {
        final Set<Integer> committed = ConcurrentHashMap.newKeySet();
        final ThreadLocal<Integer> staged = new ThreadLocal<>();
        final CountDownLatch firstCommit = new CountDownLatch(1), releaseCommit = new CountDownLatch(1);
        final CountDownLatch secondEntered = new CountDownLatch(1);
        final AtomicInteger commits = new AtomicInteger(), rollbacks = new AtomicInteger();
        volatile boolean blockFirstCommit;

        int reserve() {
            int port = 28100;
            while (committed.contains(port)) port++;
            staged.set(port);
            return port;
        }
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
        @Override public void commit(TransactionStatus status) {
            if (commits.incrementAndGet() == 1 && blockFirstCommit) {
                firstCommit.countDown();
                try { if (!releaseCommit.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("commit blocked"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            Integer port = staged.get();
            if (port != null && !committed.add(port)) throw new IllegalStateException("duplicate committed port");
            staged.remove();
        }
        @Override public void rollback(TransactionStatus status) { staged.remove(); rollbacks.incrementAndGet(); }
    }

    static class OrdinaryMutations {
        final Reservations reservations;
        OrdinaryMutations(Reservations reservations) { this.reservations = reservations; }
        @SerializeNodePorts @Transactional public int create() { return reservations.reserve(); }
        @SerializeNodePorts @Transactional public int failingUpdate() {
            reservations.reserve(); throw new IllegalStateException("node rejected configuration");
        }
    }
    static class ChainMutations {
        final Reservations reservations;
        ChainMutations(Reservations reservations) { this.reservations = reservations; }
        @SerializeNodePorts @Transactional public int create() {
            reservations.secondEntered.countDown(); return reservations.reserve();
        }
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Config {
        @Bean NodePortAllocationAspect aspect() { return new NodePortAllocationAspect(); }
        @Bean Reservations transactionManager() { return new Reservations(); }
        @Bean OrdinaryMutations ordinary(Reservations manager) { return new OrdinaryMutations(manager); }
        @Bean ChainMutations chains(Reservations manager) { return new ChainMutations(manager); }
    }

    @Test
    void differentForwardTypesWaitForCommitBeforeChoosingTheirPort() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)) {
            Reservations manager = context.getBean(Reservations.class);
            OrdinaryMutations ordinary = context.getBean(OrdinaryMutations.class);
            ChainMutations chains = context.getBean(ChainMutations.class);
            manager.blockFirstCommit = true;
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> first = workers.submit(ordinary::create);
                assertTrue(manager.firstCommit.await(5, TimeUnit.SECONDS));
                CountDownLatch secondRequested = new CountDownLatch(1);
                Future<Integer> second = workers.submit(() -> { secondRequested.countDown(); return chains.create(); });
                assertTrue(secondRequested.await(5, TimeUnit.SECONDS));
                assertFalse(manager.secondEntered.await(200, TimeUnit.MILLISECONDS), "Lock must include transaction commit");
                manager.releaseCommit.countDown();
                assertEquals(28100, first.get(5, TimeUnit.SECONDS));
                assertEquals(28101, second.get(5, TimeUnit.SECONDS));
                assertEquals(Set.of(28100, 28101), manager.committed);
            } finally { manager.releaseCommit.countDown(); workers.shutdownNow(); }
        }
    }

    @Test
    void rollbackReleasesConfigurationLockAndDoesNotReserveTheFailedPort() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)) {
            Reservations manager = context.getBean(Reservations.class);
            assertThrows(IllegalStateException.class, () -> context.getBean(OrdinaryMutations.class).failingUpdate());
            assertEquals(1, manager.rollbacks.get());
            assertEquals(28100, context.getBean(ChainMutations.class).create());
            assertEquals(Set.of(28100), manager.committed);
        }
    }

    @Test
    void chainProbeMonitorIsHeldUntilDeploymentCommitCompletes() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class)) {
            Reservations manager = context.getBean(Reservations.class);
            ChainMutations chains = context.getBean(ChainMutations.class);
            Object target = AopTestUtils.getUltimateTargetObject(chains);
            manager.blockFirstCommit = true;
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> deployed = workers.submit(chains::create);
                assertTrue(manager.firstCommit.await(5, TimeUnit.SECONDS));
                CountDownLatch readerRequested = new CountDownLatch(1);
                Future<Boolean> probeWrite = workers.submit(() -> {
                    readerRequested.countDown();
                    synchronized (target) { return true; }
                });
                assertTrue(readerRequested.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> probeWrite.get(200, TimeUnit.MILLISECONDS));
                manager.releaseCommit.countDown();
                assertEquals(28100, deployed.get(5, TimeUnit.SECONDS));
                assertTrue(probeWrite.get(5, TimeUnit.SECONDS));
            } finally { manager.releaseCommit.countDown(); workers.shutdownNow(); }
        }
    }
}
