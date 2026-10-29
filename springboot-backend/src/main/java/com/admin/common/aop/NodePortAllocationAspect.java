package com.admin.common.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * The panel runs as a single backend instance. Hold one configuration lock
 * across allocation, node deployment, persistence and transaction completion.
 * Transfer traffic and path probes do not acquire this lock.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class NodePortAllocationAspect {
    private final ReentrantLock mutationLock = new ReentrantLock(true);

    // Runs outside Spring's transaction interceptor, so a waiting allocator
    // cannot read ports until the preceding transaction has actually committed.
    @Around("@annotation(com.admin.common.annotation.SerializeNodePorts)")
    public Object serialize(ProceedingJoinPoint operation) throws Throwable {
        mutationLock.lock();
        try {
            // Probe persistence uses the chain service's monitor. Keep that
            // monitor until commit too, so it cannot overwrite a deployment
            // using a snapshot read in the gap before transaction completion.
            synchronized (operation.getTarget()) {
                return operation.proceed();
            }
        } finally {
            mutationLock.unlock();
        }
    }
}
