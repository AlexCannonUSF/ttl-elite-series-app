package com.ttl.tabletennis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "ttlScraperExecutor")
    public Executor ttlScraperExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("ttl-scraper-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "ttlIngestionBusExecutor")
    public Executor ttlIngestionBusExecutor(
            @Value("${ttl.ingestion.executor.core-pool-size:2}") int corePoolSize,
            @Value("${ttl.ingestion.executor.max-pool-size:2}") int maxPoolSize,
            @Value("${ttl.ingestion.executor.queue-capacity:1000}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Local H2 serializes writers and the application pool intentionally has
        // only four connections. Keeping ingestion below that ceiling reserves
        // capacity for HTTP requests and the paper/settlement schedulers while
        // still draining market bursts concurrently. A caller-runs rejection
        // policy turns an oversized burst into publisher backpressure instead of
        // silently dropping persistence work or creating a connection stampede.
        int safeCorePoolSize = Math.max(1, corePoolSize);
        int safeMaxPoolSize = Math.max(safeCorePoolSize, maxPoolSize);
        executor.setCorePoolSize(safeCorePoolSize);
        executor.setMaxPoolSize(safeMaxPoolSize);
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("ttl-ingest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "ttlRedisConsumerScheduler")
    public ThreadPoolTaskScheduler ttlRedisConsumerScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ttl-redis-consumer-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("scheduling-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
