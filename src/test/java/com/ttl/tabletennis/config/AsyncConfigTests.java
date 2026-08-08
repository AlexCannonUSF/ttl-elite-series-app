package com.ttl.tabletennis.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AsyncConfigTests {

    @Test
    void ingestionExecutorIsBoundedAndAppliesBackpressure() {
        Executor configured = new AsyncConfig().ttlIngestionBusExecutor(2, 2, 1000);
        ThreadPoolTaskExecutor executor = assertInstanceOf(ThreadPoolTaskExecutor.class, configured);
        try {
            assertEquals(2, executor.getCorePoolSize());
            assertEquals(2, executor.getMaxPoolSize());
            assertEquals(1000, executor.getQueueCapacity());
            assertInstanceOf(
                    ThreadPoolExecutor.CallerRunsPolicy.class,
                    executor.getThreadPoolExecutor().getRejectedExecutionHandler()
            );
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void ingestionExecutorNormalizesUnsafePoolValues() {
        Executor configured = new AsyncConfig().ttlIngestionBusExecutor(0, 0, 0);
        ThreadPoolTaskExecutor executor = assertInstanceOf(ThreadPoolTaskExecutor.class, configured);
        try {
            assertEquals(1, executor.getCorePoolSize());
            assertEquals(1, executor.getMaxPoolSize());
            assertEquals(1, executor.getQueueCapacity());
        } finally {
            executor.shutdown();
        }
    }
}
